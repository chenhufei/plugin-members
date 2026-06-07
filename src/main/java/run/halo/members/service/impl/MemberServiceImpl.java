package run.halo.members.service.impl;

import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;
import run.halo.members.MemberGroup;
import run.halo.members.endpoint.MemberEndpoint;
import run.halo.members.service.MemberService;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.validation.MemberSubmitRequestValidator;

/**
 * 成员服务实现
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final ReactiveExtensionClient client;
    private final SettingConfigMember settingConfigMember;
    private final MemberSubmitRequestValidator requestValidator;

    @Override
    public Mono<Member> submitMember(MemberEndpoint.MemberSubmitRequest request) {
        return Mono.fromCallable(() -> requestValidator.validateAndSanitize(request))
            .flatMap(validatedRequest -> settingConfigMember.getBasicConfig()
                .flatMap(config -> resolveGroupName(validatedRequest, config)
                    .map(groupName -> createMember(validatedRequest, config, groupName))
                    .flatMap(client::create)
                ));
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

        log.info("提交成员申请: {}, 学校: {}, QQ: {}, 邮箱: {}, 分组: {}, 自动审核: {}",
            request.displayName(), request.school(), request.qq(),
            request.email(), groupName, config.isAutoApprove());

        Member member = new Member();
        member.setMetadata(new run.halo.app.extension.Metadata());
        member.getMetadata().setGenerateName("member-");

        Member.MemberSpec spec = new Member.MemberSpec();
        spec.setDisplayName(request.displayName());
        spec.setEmail(request.email());
        spec.setSchool(request.school());
        spec.setQq(request.qq());
        spec.setQqFriendLink(request.qqFriendLink());
        spec.setGroupName(StringUtils.trimToNull(groupName));
        spec.setStatus(status);
        spec.setPriority(0);
        spec.setAvatar("https://q1.qlogo.cn/g?b=qq&nk=" + request.qq() + "&s=640");

        member.setSpec(spec);
        return member;
    }
}
