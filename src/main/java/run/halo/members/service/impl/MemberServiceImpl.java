package run.halo.members.service.impl;

import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.Semaphore;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;
import run.halo.members.MemberGroup;
import run.halo.members.endpoint.MemberEndpoint;
import run.halo.members.exception.DuplicateMemberException;
import run.halo.members.exception.PendingCapacityExceededException;
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
        return Mono.defer(() -> {
            if (applicationCreationPermit.tryAcquire()) {
                return Mono.just(Boolean.TRUE);
            }
            return Mono.delay(java.time.Duration.ofMillis(10))
                .then(acquireCreationPermit());
        });
    }

    private Mono<Void> ensureNoActiveDuplicate(MemberEndpoint.MemberSubmitRequest request) {
        String normalizedEmail = StringUtils.lowerCase(StringUtils.trim(request.email()));
        String normalizedQq = StringUtils.trim(request.qq());
        return client.listAll(Member.class, new ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort())
            .filter(member -> member.getSpec() != null)
            .filter(member -> isApplicationOccupying(member.getSpec().getStatus()))
            .filter(member -> normalizedQq.equals(StringUtils.trim(member.getSpec().getQq()))
                || normalizedEmail.equals(
                    StringUtils.lowerCase(StringUtils.trim(member.getSpec().getEmail()))))
            .hasElements()
            .flatMap(duplicate -> duplicate
                ? Mono.error(new DuplicateMemberException(
                    "该 QQ 号或邮箱已有待审核/已通过的成员记录"))
                : Mono.empty());
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
