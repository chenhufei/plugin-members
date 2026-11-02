package run.halo.members.service.impl;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;
import run.halo.members.MemberGroup;
import run.halo.members.endpoint.MemberEndpoint;
import run.halo.members.exception.DuplicateMemberException;
import run.halo.members.exception.PendingCapacityExceededException;
import run.halo.members.exception.RateLimitExceededException;
import run.halo.members.service.MemberService;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.validation.MemberSubmitRequestValidator;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.members.MemberConstant.SUBMISSION_NOTIFICATION;
import static run.halo.members.MemberConstant.SUBMISSION_NOTIFICATION_PENDING;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION_PENDING;

/**
 * 成员服务实现
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    /**
     * Serializes anonymous application occupancy checks and creation in this Halo process.
     * This prevents concurrent requests from both passing duplicate/capacity checks.
     */
    private final Semaphore applicationCreationPermit = new Semaphore(1, true);

    /**
     * 排队等待上限。超时直接拒绝，而不是无限等待。
     *
     * <p>原实现是「拿不到就 delay(10ms) 递归调用自己」，既没有最大等待时间也没有队列上限：
     * 一个慢请求（全表 listAll 判重 + 建索引）就能把它后面所有匿名提交钉住，流量一大就是
     * 协程空转 + 请求堆积。这里改成带超时的 tryAcquire，超时立刻 429，把压力交回调用方。
     */
    private static final Duration PERMIT_WAIT_TIMEOUT = Duration.ofSeconds(3);

    private final ReactiveExtensionClient client;
    private final SettingConfigMember settingConfigMember;
    private final MemberSubmitRequestValidator requestValidator;

    @Override
    public Mono<Member> submitMember(MemberEndpoint.MemberSubmitRequest request) {
        return Mono.fromCallable(() -> requestValidator.validateAndSanitize(request))
            .flatMap(validatedRequest -> acquireCreationPermit()
                .flatMap(ignored -> settingConfigMember.getBasicConfig()
                    .flatMap(config -> ensureNoActiveDuplicate(validatedRequest)
                        .then(ensurePendingCapacity(config))
                        .then(resolveGroupName(validatedRequest, config))
                        .map(groupName -> createMember(validatedRequest, config, groupName))
                        .flatMap(client::create))
                    .doFinally(signal -> applicationCreationPermit.release())));
    }

    private Mono<Boolean> acquireCreationPermit() {
        return Mono.fromCallable(() -> {
                try {
                    return applicationCreationPermit.tryAcquire(
                        PERMIT_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new RateLimitExceededException("提交过于频繁，请稍后重试", error);
                }
            })
            // 阻塞等待必须离开事件循环线程
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(acquired -> acquired
                ? Mono.just(Boolean.TRUE)
                : Mono.error(new RateLimitExceededException("提交过于频繁，请稍后重试")));
    }

    /**
     * 重复检测：走 `spec.qq` / `spec.email` 索引，只捞出可能重复的那几条记录。
     *
     * <p>旧实现把整张成员表 listAll 进内存再 filter，每个匿名提交都要重复一遍，成员上万后
     * 单次提交的耗时与内存占用都随人数线性增长。改成索引查询后只读命中值对应的少量记录。
     *
     * <p>两个条件是 OR 关系（QQ 或邮箱命中即判重）。空值不参与查询——索引不为空值建条目，
     * 但拿空串去查会退化成全表匹配。索引尚未建好（插件刚升级时）或查询报错时回退到全表扫描，
     * 宁可慢一点也不能放过重复。
     */
    private Mono<Void> ensureNoActiveDuplicate(MemberEndpoint.MemberSubmitRequest request) {
        String normalizedEmail = StringUtils.trimToNull(StringUtils.lowerCase(request.email()));
        String normalizedQq = StringUtils.trimToNull(request.qq());

        Mono<Boolean> duplicateByQq = normalizedQq == null
            ? Mono.just(Boolean.FALSE)
            : existsOccupying(ListOptions.builder()
                    .andQuery(equal("spec.qq", normalizedQq)).build(),
                member -> normalizedQq.equals(StringUtils.trim(member.getSpec().getQq())));
        Mono<Boolean> duplicateByEmail = normalizedEmail == null
            ? Mono.just(Boolean.FALSE)
            : existsOccupying(ListOptions.builder()
                    .andQuery(equal("spec.email", normalizedEmail)).build(),
                member -> normalizedEmail.equals(
                    StringUtils.lowerCase(StringUtils.trim(member.getSpec().getEmail()))));

        return Mono.zip(duplicateByQq, duplicateByEmail)
            .flatMap(duplicate -> duplicate.getT1() || duplicate.getT2()
                ? Mono.error(new DuplicateMemberException(
                    "该 QQ 号或邮箱已有待审核/已通过的成员记录"))
                : Mono.empty());
    }

    private Mono<Boolean> existsOccupying(ListOptions options, Predicate<Member> matcher) {
        return client.listAll(Member.class, options, ExtensionUtil.defaultSort())
            .filter(member -> member.getSpec() != null)
            .filter(member -> isApplicationOccupying(member.getSpec().getStatus()))
            .hasElements()
            .onErrorResume(error -> {
                log.warn("成员索引查询失败，回退全表扫描: {}", error.toString());
                return client.listAll(Member.class, new ListOptions(),
                        ExtensionUtil.defaultSort())
                    .filter(member -> member.getSpec() != null)
                    .filter(member -> isApplicationOccupying(member.getSpec().getStatus()))
                    .filter(matcher)
                    .hasElements();
            });
    }

    private boolean isApplicationOccupying(String status) {
        return "PENDING".equals(status)
            || "APPROVED".equals(status)
            || "WITHDRAW_REQUESTED".equals(status);
    }

    private Mono<Void> ensurePendingCapacity(SettingConfigMember.BasicConfig config) {
        if (config.isAutoApprove()) {
            return Mono.empty();
        }
        return client.countBy(Member.class, ListOptions.builder()
                .andQuery(equal("spec.status", "PENDING"))
                .build())
            .flatMap(count -> count >= config.normalizedPendingCapacity()
                ? Mono.error(new PendingCapacityExceededException(
                    "待审核成员申请已达到容量上限，请稍后再试"))
                : Mono.empty());
    }

    private Mono<String> resolveGroupName(MemberEndpoint.MemberSubmitRequest request,
        SettingConfigMember.BasicConfig config) {
        String groupName = StringUtils.defaultIfBlank(request.groupName(), config.getDefaultGroupName());
        groupName = StringUtils.trimToEmpty(groupName);
        if (StringUtils.isBlank(groupName)) {
            return Mono.just("");
        }

        if (isForbiddenGroup(request.groupName(), config.getForbidSelectedGroupName())) {
            return Mono.error(new IllegalArgumentException("该分组不允许申请"));
        }

        String resolvedGroupName = groupName;
        return client.fetch(MemberGroup.class, resolvedGroupName)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("分组不存在或已删除")))
            .thenReturn(resolvedGroupName);
    }

    private boolean isForbiddenGroup(String selectedGroupName, String[] forbiddenGroups) {
        if (StringUtils.isBlank(selectedGroupName) || forbiddenGroups == null) {
            return false;
        }
        return Arrays.stream(forbiddenGroups)
            .map(StringUtils::trimToEmpty)
            .anyMatch(selectedGroupName::equals);
    }

    private Member createMember(MemberEndpoint.MemberSubmitRequest request,
        SettingConfigMember.BasicConfig config, String groupName) {
        String status = config.isAutoApprove() ? "APPROVED" : "PENDING";

        log.info("提交成员申请: 分组={}, 自动审核={}", groupName, config.isAutoApprove());

        Member member = new Member();
        member.setMetadata(new run.halo.app.extension.Metadata());
        member.getMetadata().setGenerateName("member-");
        member.getMetadata().setAnnotations(new HashMap<>());
        member.getMetadata().getAnnotations()
            .put(SUBMISSION_NOTIFICATION, SUBMISSION_NOTIFICATION_PENDING);
        if ("APPROVED".equals(status)) {
            member.getMetadata().getAnnotations()
                .put(REVIEW_NOTIFICATION, REVIEW_NOTIFICATION_PENDING);
        }

        Member.MemberSpec spec = new Member.MemberSpec();
        spec.setDisplayName(request.displayName());
        spec.setEmail(StringUtils.lowerCase(StringUtils.trim(request.email())));
        spec.setSchool(request.school());
        spec.setQq(request.qq());
        spec.setQqFriendLink(request.qqFriendLink());
        spec.setGroupName(StringUtils.trimToNull(groupName));
        spec.setStatus(status);
        spec.setOrigin("FORM");
        spec.setPriority(0);
        spec.setAvatar("https://q1.qlogo.cn/g?b=qq&nk=" + request.qq() + "&s=640");

        member.setSpec(spec);
        return member;
    }
}
