package run.halo.members.endpoint;

import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;
import run.halo.app.plugin.ApiVersion;
import run.halo.members.Member;
import run.halo.members.NotificationReasonPublisher;
import run.halo.members.cache.VerificationCodeService;
import run.halo.members.exception.ProblemResponses;
import run.halo.members.security.RateLimitService;
import run.halo.members.security.captcha.MemberCaptchaService;
import run.halo.members.service.ClaimTokenService;
import run.halo.members.service.MailDeliveryGuard;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.utils.AvatarUtils;
import run.halo.members.utils.EmailUtils;
import run.halo.members.utils.QqLinkUtils;
import run.halo.members.utils.RequestUtils;
import static run.halo.members.MemberConstant.USER_MEMBER_CLAIM_CODE;

/**
 * 成员 QQ 链接认领公开端点
 * 提供两种认领方式：
 * 1. 自助认领：成员输入 QQ 号 + qqFriendLink + 邮箱验证码
 * 2. Token 认领：成员点击管理员分发的认领链接
 *
 * @since 1.9.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ApiVersion("api.plugin.halo.run/v1alpha1")
public class MemberClaimEndpoint implements CustomEndpoint {

    /** 全站每 10 分钟允许发出的认领验证码总数，防「换 IP 继续刷」的分布式枚举。 */
    private static final int GLOBAL_SEND_CODE_LIMIT = 200;

    /** 全站每 10 分钟允许的认领校验请求总数。 */
    private static final int GLOBAL_CLAIM_VERIFY_LIMIT = 500;

    /** 统一的验证码发送响应：命中与未命中返回同一句话，避免按 QQ 号探测成员是否存在。 */
    private static final String CLAIM_CODE_ACCEPTED_MESSAGE =
        "如该 QQ 号已在本站登记，验证码将发送至登记邮箱（或该号 QQ 邮箱），5 分钟内有效。"
            + "未收到请联系管理员核对登记邮箱。";

    private final ReactiveExtensionClient client;
    private final ClaimTokenService claimTokenService;
    private final VerificationCodeService codeService;
    private final RateLimitService rateLimitService;
    private final NotificationReasonPublisher notificationReasonPublisher;
    private final NotificationReasonEmitter notificationReasonEmitter;
    private final NotificationCenter notificationCenter;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final MailDeliveryGuard mailDeliveryGuard;
    private final MemberCaptchaService captchaService;
    private final SettingConfigMember settingConfigMember;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/MemberClaim";
        return route()
            .POST("/plugins/PluginMembers/claim/send-code", this::sendClaimCode,
                builder -> builder.operationId("SendClaimCode")
                    .description("Send verification code to QQ email for self-service claim")
                    .tag(tag))
            .POST("/plugins/PluginMembers/claim", this::claimByQq,
                builder -> builder.operationId("ClaimByQq")
                    .description("Self-service claim: member enters QQ number and qqFriendLink")
                    .tag(tag))
            .GET("/plugins/PluginMembers/claim/verification-mode", this::getClaimVerificationMode,
                builder -> builder.operationId("GetClaimVerificationMode")
                    .description("Get verification mode used by self-service claim")
                    .tag(tag))
            .GET("/plugins/PluginMembers/claim/{token}", this::getClaimInfo,
                builder -> builder.operationId("GetClaimInfo")
                    .description("Get member info for token-based claim form")
                    .tag(tag))
            .POST("/plugins/PluginMembers/claim/{token}", this::claimByToken,
                builder -> builder.operationId("ClaimByToken")
                    .description("Submit qqFriendLink with claim token")
                    .tag(tag))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> sendClaimCode(ServerRequest request) {
        return request.bodyToMono(SendClaimCodeRequest.class)
            .flatMap(body -> {
                if (body.qq() == null || body.qq().isBlank()) {
                    return errorResponse(400, "QQ 号不能为空");
                }
                String qq = body.qq().trim();
                String manualEmail = body.email() == null ? "" : body.email().trim();
                String clientIP = RequestUtils.getClientIP(request);

                // 限流键不带 QQ 号：带上的效果是「换一个号换一个计数桶」，可以拿它逐号探测
                // 本站登记了哪些成员。改为按 IP 限速 + 一层全局兜底，防分布式换 IP 继续刷。
                if (!rateLimitService.isRequestAllowed(
                        clientIP + ":claim-send-code", 3, Duration.ofMinutes(10))
                    || !rateLimitService.isRequestAllowed(
                        "claim-send-code:global", GLOBAL_SEND_CODE_LIMIT, Duration.ofMinutes(10))) {
                    return errorResponse(429, "验证码发送过于频繁，请稍后再试");
                }

                return mailDeliveryGuard.unavailabilityReason()
                    .flatMap(reason -> reason.isPresent()
                        ? errorResponse(409, reason.get())
                        : sendClaimCodeNow(qq, manualEmail));
            })
            .onErrorResume(error -> {
                log.error("发送认领验证码失败", error);
                return errorResponse(500, "发送验证码失败，请稍后重试");
            });
    }

    /**
     * 发送认领验证码。
     *
     * <p>分两段：前半段要查库、拼地址，是阻塞的，整体挪到 boundedElastic 上跑完就撤；
     * 后半段「订阅 + 发通知」是纯响应式 IO，直接在链上组合，不再在该线程里逐个 block。
     * 旧实现在同一个 Callable 里连 block 三次（查成员、subscribe、emit），等于一个请求
     * 霸占一个 worker 直到邮件发完。boundedElastic 是全 JVM 共享的默认 10×CPU 个线程，
     * 并发发码时会被互相等满，连带的通知都发不出去。
     */
    private Mono<ServerResponse> sendClaimCodeNow(String qq, String manualEmail) {
        return Mono.fromCallable(() -> prepareClaimCode(qq, manualEmail))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(prepared -> {
                if (prepared.member() == null) {
                    return prepared.response();
                }
                return publishClaimCode(prepared.member(), prepared.email(),
                        prepared.code(), prepared.url())
                    .then(prepared.response())
                    .doOnSuccess(ignored ->
                        log.info("认领验证码已提交发送至 {}", EmailUtils.mask(prepared.email())))
                    .onErrorResume(error -> {
                        // 回滚验证码，否则用户会在整个有效期内被「已有验证码」挡住，却收不到邮件
                        codeService.invalidate(prepared.verificationKey());
                        log.error("提交认领验证码通知失败, QQ: {}", qq, error);
                        return errorResponse(502, "验证码发送失败，请稍后重试或联系管理员");
                    });
            });
    }

    /**
     * 发码前的同步准备：查成员、定收件地址、取退回地址、生成并登记验证码。
     *
     * <p>这里全是阻塞调用，只能跑在 boundedElastic 上，因此越短越好——不碰通知中心。
     */
    private PreparedClaimCode prepareClaimCode(String qq, String manualEmail) {
        Member member = findMemberByQqSync(qq);
        if (member == null) {
            // 不能回 404：404 就是在告诉调用方「这个 QQ 号没登记」，可以逐号试出
            // 本站有哪些成员。命中与未命中统一返回同一句话，实际是否发信只留在日志里。
            log.info("认领验证码请求未匹配到成员, QQ: {}", qq);
            return PreparedClaimCode.rejected(claimCodeAccepted());
        }

        // 收件地址必须是成员登记邮箱或该 QQ 号自带的 QQ 邮箱，见 claimVerificationEmail
        String resolvedEmail = claimVerificationEmail(member, manualEmail, qq);
        if (resolvedEmail == null || resolvedEmail.isEmpty()) {
            // 同上：邮箱不合规 / 未登记也不单独报错，否则又是一条存在性旁路
            log.info("认领验证码收件地址不可用, QQ: {}", qq);
            return PreparedClaimCode.rejected(claimCodeAccepted());
        }
        String verificationKey = verificationKey(resolvedEmail, qq);

        if (codeService.hasValidCode(verificationKey)) {
            // 本次并未真的发信，不能再报「已发送」；这条不泄露成员存在性，可以明说
            return PreparedClaimCode.rejected(
                errorResponse(429, "验证码已在 5 分钟内发送过，请稍后再试"));
        }

        // 先取退回地址再生成验证码：取地址失败时就没有验证码需要回滚
        String url = externalLinkProcessor.processLink("/members");
        String code = codeService.generateCode(verificationKey);
        return new PreparedClaimCode(member, resolvedEmail, code, verificationKey, url,
            claimCodeAccepted());
    }

    /**
     * 统一的「已受理」响应。
     *
     * <p>不再回显收件邮箱掩码，也不再区分「查无此号 / 邮箱不合规 / 已发送」——这三种情况
     * 返回同一个响应体，攻击方拿不到「该 QQ 号是否登记」的信号。
     */
    private Mono<ServerResponse> claimCodeAccepted() {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "success", true,
                "message", CLAIM_CODE_ACCEPTED_MESSAGE
            ));
    }

    private Mono<ServerResponse> getClaimVerificationMode(ServerRequest request) {
        return settingConfigMember.getBasicConfig()
            .flatMap(config -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mode", config.effectiveClaimVerifyMode())));
    }

    private Mono<ServerResponse> claimByQq(ServerRequest request) {
        return request.bodyToMono(ClaimByQqRequest.class)
            .flatMap(body -> {
                if (body.qq() == null || body.qq().isBlank()) {
                    return errorResponse(400, "QQ 号不能为空");
                }
                if (body.qqFriendLink() == null || body.qqFriendLink().isBlank()) {
                    return errorResponse(400, "QQ 链接不能为空");
                }

                String qq = body.qq().trim();
                String friendLink = body.qqFriendLink().trim();
                String linkError = validateFriendLink(friendLink);
                if (linkError != null) {
                    return errorResponse(400, linkError);
                }
                String clientIP = RequestUtils.getClientIP(request);

                // 同 sendClaimCode：限流键不带 QQ 号，否则换号即换计数桶
                if (!rateLimitService.isRequestAllowed(
                        clientIP + ":claim-verify", 10, Duration.ofMinutes(10))
                    || !rateLimitService.isRequestAllowed(
                        "claim-verify:global", GLOBAL_CLAIM_VERIFY_LIMIT, Duration.ofMinutes(10))) {
                    return errorResponse(429, "请求过于频繁，请稍后再试");
                }

                return settingConfigMember.getBasicConfig()
                    .flatMap(config -> {
                        String verifyMode = config.effectiveClaimVerifyMode();
                        // 图形验证码校验（按设置）
                        if ("captcha".equals(verifyMode)) {
                            String cid = body.captchaChallengeId() == null ? "" : body.captchaChallengeId().trim();
                            String cc = body.captchaCode() == null ? "" : body.captchaCode().trim();
                            if (!cid.isBlank() && !cc.isBlank()) {
                                if (!captchaService.verifyChallenge(cid, cc)) {
                                    return errorResponse(400, "验证码错误或已过期，请刷新后重试");
                                }
                            } else {
                                return errorResponse(400, "请输入图形验证码");
                            }
                        }

                        // 邮箱验证码校验（可选）
                        return findMemberByQq(qq)
                            .flatMap(member -> {
                                if ("email".equals(verifyMode)) {
                                    String manualEmail = body.email() == null ? "" : body.email().trim();
                                    String verificationEmail = claimVerificationEmail(member, manualEmail, qq);
                                    if (verificationEmail == null) {
                                        return errorResponse(400,
                                            "验证码只能发往该成员登记邮箱或 QQ 邮箱，请使用发送验证码时的邮箱");
                                    }
                                    if (verificationEmail.isEmpty()) {
                                        // 以前这里「邮箱为空 → 跳过校验」，等于对无邮箱成员完全放开认领
                                        return errorResponse(400,
                                            "该成员未登记验证邮箱，无法完成邮箱校验，请联系管理员");
                                    }
                                    String code = body.code() == null ? "" : body.code().trim();
                                    if (code.isEmpty()) {
                                        return errorResponse(400, "请输入邮箱验证码");
                                    }
                                    if (!codeService.verifyCode(verificationKey(verificationEmail, qq), code)) {
                                        return errorResponse(400, "验证码错误或已过期");
                                    }
                                }

                                var spec = member.getSpec();
                                if (spec.getQqFriendLink() != null && !spec.getQqFriendLink().isBlank()) {
                                    return errorResponse(409, "该成员的 QQ 链接已设置，无法重复认领");
                                }

                                String profileError = applyProfile(member, body.displayName(),
                                    body.school());
                                if (profileError != null) {
                                    return errorResponse(400, profileError);
                                }
                                spec.setQqFriendLink(friendLink);

                                if (!config.isAutoApproveClaim()) {
                                    spec.setStatus("PENDING");
                                    return client.update(member)
                                        // 资料已落库：通知发不出去不能再报 500，否则用户以为失败并重复提交
                                        .flatMap(updated -> notificationReasonPublisher
                                            .publishClaimLinkNotice(member, buildClaimUrl(""))
                                            .onErrorResume(error -> {
                                                log.warn("认领待审通知发送失败, member: {}",
                                                    member.getMetadata().getName(), error);
                                                return Mono.empty();
                                            }))
                                            .then(Mono.defer(() -> ServerResponse.ok().bodyValue(Map.of(
                                                "success", true,
                                                "message", "资料已提交，待管理员审核后显示"
                                            ))));
                                }

                                return client.update(member)
                                    .flatMap(updated -> ServerResponse.ok().bodyValue(Map.of(
                                        "success", true,
                                        "message", "QQ 链接已更新"
                                    )));
                            })
                            // 与「命中但后续校验失败」同一措辞，减少按 QQ 号探测存在性的信息量
                            .switchIfEmpty(errorResponse(404, "请检查 QQ 号，或该号暂未登记"));
                    });
            })
            .onErrorResume(error -> {
                log.error("自助认领失败", error);
                return errorResponse(500, "认领失败，请稍后重试");
            });
    }

    private Mono<ServerResponse> getClaimInfo(ServerRequest request) {
        String token = request.pathVariable("token");
        return claimTokenService.validateToken(token)
            .flatMap(member -> {
                var spec = member.getSpec();
                return ServerResponse.ok().bodyValue(Map.of(
                    "name", member.getMetadata().getName(),
                    "displayName", spec.getDisplayName() != null ? spec.getDisplayName() : "",
                    "qq", spec.getQq() != null ? spec.getQq() : "",
                    "school", spec.getSchool() != null ? spec.getSchool() : "",
                    "avatar", spec.getAvatar() != null ? spec.getAvatar() : "",
                    "qqFriendLink", spec.getQqFriendLink() != null
                        ? spec.getQqFriendLink() : ""
                ));
            })
            .switchIfEmpty(errorResponse(404, "认领链接无效或已过期"))
            .onErrorResume(error -> {
                log.error("获取认领信息失败", error);
                return errorResponse(500, "获取信息失败，请稍后重试");
            });
    }

    private Mono<ServerResponse> claimByToken(ServerRequest request) {
        String token = request.pathVariable("token");
        return request.bodyToMono(ClaimByTokenRequest.class)
            .flatMap(body -> {
                if (body.qqFriendLink() == null || body.qqFriendLink().isBlank()) {
                    return errorResponse(400, "QQ 链接不能为空");
                }
                String friendLink = body.qqFriendLink().trim();
                String linkError = validateFriendLink(friendLink);
                if (linkError != null) {
                    return errorResponse(400, linkError);
                }
                // token 路径原来既没有限流也没有「是否已被占用」检查：管理员把同一个认领链接
                // 群发出去时，谁先提交谁覆盖，后来者还能把已设置的链接再改一遍。
                String clientIP = RequestUtils.getClientIP(request);
                if (!rateLimitService.isRequestAllowed(
                        clientIP + ":claim-token", 10, Duration.ofMinutes(10))) {
                    return errorResponse(429, "请求过于频繁，请稍后再试");
                }
                return claimTokenService.validateToken(token)
                    .flatMap(member -> {
                        var spec = member.getSpec();
                        if (spec.getQqFriendLink() != null && !spec.getQqFriendLink().isBlank()) {
                            return errorResponse(409, "该成员的 QQ 链接已设置，无法重复认领");
                        }
                        String profileError = applyProfile(member, body.displayName(),
                            body.school());
                        if (profileError != null) {
                            return errorResponse(400, profileError);
                        }
                        spec.setQqFriendLink(friendLink);
                        return client.update(member)
                            .flatMap(updated ->
                                claimTokenService.invalidateToken(member.getMetadata().getName())
                                    .then(ServerResponse.ok().bodyValue(Map.of(
                                        "success", true,
                                        "message", "QQ 链接已更新，认领链接已失效"
                                    )))
                            );
                    })
                    .switchIfEmpty(errorResponse(404, "认领链接无效或已过期"));
            })
            .onErrorResume(error -> {
                log.error("Token 认领失败", error);
                return errorResponse(500, "认领失败，请稍后重试");
            });
    }

    /**
     * 认领时同步可编辑资料：null 表示不改动，空字符串表示清空（头像清空后回到默认 QQ 头像）。
     *
     * @return 校验错误信息；成功时返回 null
     */
    /**
     * 写入认领时允许访客修改的资料。
     *
     * <p>头像不再由访客提供：自定义头像功能已关闭，统一使用 QQ 头像，
     * 因此这里忽略任何客户端传来的头像内容（也不再校验数据 URL）。
     */
    private String applyProfile(Member member, String displayName, String school) {
        var spec = member.getSpec();
        if (displayName != null) {
            String name = displayName.trim();
            if (name.isEmpty()) {
                return "账号名称不能为空";
            }
            if (name.length() > 100) {
                return "账号名称不能超过100个字符";
            }
            spec.setDisplayName(name);
        }
        if (school != null) {
            String value = school.trim();
            if (value.length() > 100) {
                return "学校名称不能超过100个字符";
            }
            spec.setSchool(value.isEmpty() ? null : value);
        }
        // 头像固定为 QQ 头像：既补齐老成员缺失的头像，也覆盖历史上存过的自定义头像
        spec.setAvatar(AvatarUtils.defaultQqAvatar(spec.getQq()));
        return null;
    }

    private Mono<Member> findMemberByQq(String qq) {
        return client.list(Member.class,
            member -> qq.equals(member.getSpec().getQq()),
            null)
            .next();
    }

    private Member findMemberByQqSync(String qq) {
        return client.list(Member.class,
                member -> qq.equals(member.getSpec().getQq()),
                null)
            .blockFirst();
    }

    /**
     * 与成员自助提交保持一致的 QQ 加好友链接规则，避免任意链接写入公示卡片与邮件
     */
    private String validateFriendLink(String qqFriendLink) {
        return QqLinkUtils.validate(qqFriendLink);
    }

    private String verificationKey(String email, String qq) {
        // 与撤回验证码分命名空间：否则一次撤回验证码会挡住认领发信，也能跨流程复用
        return "claim:" + email + ":" + qq;
    }

    /**
     * 认领验证码只允许发往「成员登记邮箱」或「该 QQ 号自带的 QQ 邮箱」。
     *
     * <p>发码与校验原本都把匿名请求方自填的邮箱当作 key：任何人只要知道成员公开的 QQ 号，
     * 就能把验证码发到自己信箱、通过校验，随后改写该成员的昵称、学校、头像和 QQ 链接。
     * 手填邮箱因此必须与成员记录一致才接受；两者都取不到时返回空串，由调用方直接拒绝，
     * 不能再像以前那样「邮箱为空 → 跳过校验」。
     *
     * @return 可用于收码的规范化邮箱；空串表示成员无可用邮箱；{@code null} 表示不接受该手填邮箱
     */
    /**
     * 认领验证码的收件地址。
     *
     * <p>自定义邮箱同样关闭：只发 QQ 邮箱（QQ号@qq.com）。
     * QQ 号异常无法拼出邮箱时，才回退到成员资料里登记的邮箱，
     * 否则这批老成员会永久无法完成认领。
     */
    static String claimVerificationEmail(Member member, String manualEmail, String qq) {
        String qqMail = EmailUtils.normalize(EmailUtils.resolveEmail(null, qq));
        String registered = EmailUtils.normalize(member.getSpec().getEmail());
        String manual = EmailUtils.normalize(manualEmail);
        if (!qqMail.isEmpty()) {
            if (manual.isEmpty() || manual.equals(qqMail)) {
                return qqMail;
            }
            return null;
        }
        if (manual.isEmpty()) {
            return registered;
        }
        return manual.equals(registered) ? registered : null;
    }

    /**
     * 订阅并投递验证码通知。
     *
     * <p>必须先 subscribe 再 emit：通知中心按订阅关系投递，顺序反了这条邮件就发不出去。
     * 两步用 {@code then} 串起来，调用方只拿到一个 Mono，不再在本线程里逐段 block。
     */
    private Mono<Void> publishClaimCode(Member member, String email, String code, String url) {
        var subject = Reason.Subject.builder()
            .apiVersion(member.getApiVersion())
            .kind(member.getKind())
            .name(member.getMetadata().getName())
            .title(member.getSpec().getDisplayName())
            .url(url)
            .build();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("email", email);
        attrs.put("displayName", member.getSpec().getDisplayName());
        attrs.put("code", code);
        var interestReason = new Subscription.InterestReason();
        interestReason.setReasonType(USER_MEMBER_CLAIM_CODE);
        interestReason.setExpression("props.email == '%s'".formatted(escapeExpressionValue(email)));
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
        return notificationCenter.subscribe(subscriber, interestReason)
            .then(notificationReasonEmitter.emit(USER_MEMBER_CLAIM_CODE,
                builder -> builder.attributes(attrs)
                    .author(UserIdentity.anonymousWithEmail(email))
                    .subject(subject)
            ));
    }

    /**
     * 发码前的同步准备结果。
     *
     * @param response 待回给调用方的响应：{@code member} 为 null 时就是最终响应（未命中 /
     *                 地址不可用 / 冷却期），否则是「发信成功后」要回的那个响应。
     *                 一律用 {@code Mono<ServerResponse>} 传递，避免在事件循环线程上 block。
     */
    private record PreparedClaimCode(Member member, String email, String code,
                                     String verificationKey, String url,
                                     Mono<ServerResponse> response) {
        static PreparedClaimCode rejected(Mono<ServerResponse> response) {
            return new PreparedClaimCode(null, null, null, null, null, response);
        }
    }

    private String escapeExpressionValue(String value) {
        return value.replace("'", "''");
    }

    private String buildClaimUrl(String token) {
        return externalLinkProcessor.processLink("/claim?token=" + token);
    }

    /**
     * 认领类错误的统一出口：状态码决定 problem type 与标题，message 作为 detail。
     *
     * <p>曾经这里回的是 {@code {success:false, message:…}}，与成员提交接口的
     * problem+json 不是一套形态。现在按状态归类，前端只读 {@code detail} 即可。
     */
    private Mono<ServerResponse> errorResponse(int status, String message) {
        HttpStatus httpStatus = HttpStatus.resolve(status);
        if (httpStatus == null) {
            httpStatus = HttpStatus.BAD_REQUEST;
        }
        return ProblemResponses.problem(httpStatus, claimProblemSlug(httpStatus),
            claimProblemTitle(httpStatus), message);
    }

    private static String claimProblemSlug(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "invalid-claim-request";
            case UNAUTHORIZED, FORBIDDEN -> "claim-forbidden";
            case NOT_FOUND -> "claim-target-not-found";
            case CONFLICT -> "claim-conflict";
            case TOO_MANY_REQUESTS -> "request-not-permitted";
            case BAD_GATEWAY -> "claim-notification-unavailable";
            case SERVICE_UNAVAILABLE -> "claim-service-unavailable";
            default -> "claim-request-failed";
        };
    }

    private static String claimProblemTitle(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "认领请求内容无效";
            case UNAUTHORIZED, FORBIDDEN -> "认领请求被拒绝";
            case NOT_FOUND -> "认领目标不存在";
            case CONFLICT -> "认领冲突";
            case TOO_MANY_REQUESTS -> "请求过于频繁";
            case BAD_GATEWAY -> "通知服务不可用";
            case SERVICE_UNAVAILABLE -> "服务暂时不可用";
            default -> "认领处理失败";
        };
    }

    public record SendClaimCodeRequest(
        String qq,
        String email
    ) {}

    public record ClaimByQqRequest(
        String qq,
        String qqFriendLink,
        String code,
        String displayName,
        String school,
        String avatar,
        String email,
        String captchaChallengeId,
        String captchaCode
    ) {}

    public record ClaimByTokenRequest(
        String qqFriendLink,
        String displayName,
        String school,
        String avatar
    ) {}

}
