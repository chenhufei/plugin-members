package run.halo.members.endpoint;

import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
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
            .GET("/plugins/PluginMembers/claim/{token}", this::getClaimInfo,
                builder -> builder.operationId("GetClaimInfo")
                    .description("Get member info for token-based claim form")
                    .tag(tag))
            .POST("/plugins/PluginMembers/claim/{token}", this::claimByToken,
                builder -> builder.operationId("ClaimByToken")
                    .description("Submit qqFriendLink with claim token")
                    .tag(tag))
            // Admin endpoints
            .GET("/plugins/PluginMembers/incomplete-members", this::listIncompleteMembers,
                builder -> builder.operationId("ListIncompleteMembers")
                    .description("List members with incomplete qqFriendLink (admin)")
                    .tag(tag))
            .GET("/plugins/PluginMembers/claims", this::listClaims,
                builder -> builder.operationId("ListClaims")
                    .description("List all active claim tokens (admin)")
                    .tag(tag))
            .POST("/plugins/PluginMembers/claims/generate", this::generateClaims,
                builder -> builder.operationId("GenerateClaims")
                    .description("Generate claim tokens for members (admin)")
                    .tag(tag))
            .DELETE("/plugins/PluginMembers/claims/{memberName}", this::invalidateClaim,
                builder -> builder.operationId("InvalidateClaim")
                    .description("Invalidate a claim token (admin)")
                    .tag(tag))
            .POST("/plugins/PluginMembers/claims/distribute", this::distributeClaims,
                builder -> builder.operationId("DistributeClaims")
                    .description("Generate and email claim links to members (admin)")
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

                if (!rateLimitService.isRequestAllowed(
                        clientIP + ":claim-send-code:" + qq, 3, Duration.ofMinutes(10))) {
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

    private Mono<ServerResponse> sendClaimCodeNow(String qq, String manualEmail) {
        return Mono.<ServerResponse>fromCallable(() -> {
            Member member = findMemberByQqSync(qq);
            if (member == null) {
                return errorResponseSync(404, "未找到该 QQ 号对应的成员");
            }

            // 优先使用用户手动填写的邮箱；未填写则回退到登记邮箱或 QQ 邮箱
            String resolvedEmail = manualEmail.isEmpty()
                ? EmailUtils.resolveEmail(member.getSpec().getEmail(), qq)
                : manualEmail.trim().toLowerCase();
            if (resolvedEmail.isEmpty()) {
                return errorResponseSync(409,
                    "该成员未登记邮箱，且未提供有效邮箱，无法发送验证码。");
            }
            boolean qqMail = StringUtils.isBlank(member.getSpec().getEmail()) && manualEmail.isEmpty();
            String verificationKey = verificationKey(resolvedEmail, qq);

            if (codeService.hasValidCode(verificationKey)) {
                // 本次并未真的发信，不能再报「已发送」，也不能再回显邮箱
                return errorResponseSync(429, "验证码已在 5 分钟内发送过，请稍后再试");
            }

            String code = codeService.generateCode(verificationKey);
            try {
                publishClaimCode(member, resolvedEmail, code);
            } catch (RuntimeException error) {
                // 回滚验证码，否则用户会在整个有效期内被"已有验证码"挡住，却收不到邮件
                codeService.invalidate(verificationKey);
                log.error("提交认领验证码通知失败, QQ: {}", qq, error);
                return errorResponseSync(502, "验证码发送失败，请稍后重试或联系管理员");
            }
            log.info("认领验证码已提交发送至 {}", EmailUtils.mask(resolvedEmail));

            return ServerResponse.ok().bodyValue(Map.of(
                "success", true,
                "message", "验证码已发送至 " + EmailUtils.mask(resolvedEmail)
                    + (qqMail ? "（QQ 邮箱）" : "") + "，5 分钟内有效",
                "targetEmail", EmailUtils.mask(resolvedEmail)
            )).block();
        }).subscribeOn(Schedulers.boundedElastic());
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

                if (!rateLimitService.isRequestAllowed(
                        clientIP + ":claim-verify:" + qq, 10, Duration.ofMinutes(10))) {
                    return errorResponse(429, "请求过于频繁，请稍后再试");
                }

                return settingConfigMember.getBasicConfig()
                    .flatMap(config -> {
                        // 图形验证码校验（可选）
                        if (config.isEnableClaimCaptcha()) {
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
                        String verificationEmail;
                        if (config.isEnableClaimEmail()) {
                            // 用户手动填写的邮箱优先
                            String manualEmail = body.email() == null ? "" : body.email().trim();
                            if (!manualEmail.isEmpty()) {
                                verificationEmail = manualEmail;
                            } else {
                                verificationEmail = EmailUtils.resolveEmail(null, qq);
                            }
                        } else {
                            verificationEmail = "";
                        }

                        if (!verificationEmail.isEmpty() && (body.code() == null || body.code().isBlank())) {
                            return errorResponse(400, "请输入邮箱验证码");
                        }
                        if (!verificationEmail.isEmpty()) {
                            String code = body.code().trim();
                            String verificationKey = verificationKey(verificationEmail, qq);
                            if (!codeService.verifyCode(verificationKey, code)) {
                                return errorResponse(400, "验证码错误或已过期");
                            }
                        }

                        return findMemberByQq(qq)
                            .flatMap(member -> {
                                var spec = member.getSpec();
                                if (spec.getQqFriendLink() != null && !spec.getQqFriendLink().isBlank()) {
                                    return errorResponse(409, "该成员的 QQ 链接已设置，无法重复认领");
                                }

                                String profileError = applyProfile(member, body.displayName(),
                                    body.school(), body.avatar());
                                if (profileError != null) {
                                    return errorResponse(400, profileError);
                                }
                                spec.setQqFriendLink(friendLink);

                                if (!config.isAutoApproveClaim()) {
                                    spec.setStatus("PENDING");
                                    return client.update(member)
                                        .flatMap(updated -> notificationReasonPublisher
                                            .publishClaimLinkNotice(member, buildClaimUrl("")))
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
                            .switchIfEmpty(errorResponse(404, "未找到该 QQ 号对应的成员"));
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
                return claimTokenService.validateToken(token)
                    .flatMap(member -> {
                        var spec = member.getSpec();
                        String profileError = applyProfile(member, body.displayName(),
                            body.school(), body.avatar());
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
    private String applyProfile(Member member, String displayName, String school, String avatar) {
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
        if (avatar == null) {
            // 老成员可能没有头像，认领时补上默认 QQ 头像，前台卡片才不是空白占位
            if (StringUtils.isBlank(spec.getAvatar())) {
                spec.setAvatar(AvatarUtils.defaultQqAvatar(spec.getQq()));
            }
            return null;
        }
        String value = avatar.trim();
        if (value.isEmpty()) {
            spec.setAvatar(AvatarUtils.defaultQqAvatar(spec.getQq()));
            return null;
        }
        String error = AvatarUtils.validateDataUrl(value);
        if (error != null) {
            return error;
        }
        spec.setAvatar(value);
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

    private void publishClaimCode(Member member, String email, String code) {
        String url = externalLinkProcessor.processLink("/members");
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
        notificationCenter.subscribe(subscriber, interestReason).block();
        notificationReasonEmitter.emit(USER_MEMBER_CLAIM_CODE,
            builder -> builder.attributes(attrs)
                .author(UserIdentity.anonymousWithEmail(email))
                .subject(subject)
        ).block();
    }

    private String escapeExpressionValue(String value) {
        return value.replace("'", "''");
    }

    private Mono<ServerResponse> listIncompleteMembers(ServerRequest request) {
        return client.list(Member.class, member -> {
                var spec = member.getSpec();
                return spec.getQqFriendLink() == null || spec.getQqFriendLink().isBlank();
            }, null)
            .map(member -> {
                var spec = member.getSpec();
                String qq = spec.getQq() != null ? spec.getQq() : "";
                String target = EmailUtils.resolveEmail(spec.getEmail(), qq);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("memberName", member.getMetadata().getName());
                item.put("displayName", spec.getDisplayName() != null ? spec.getDisplayName() : "");
                item.put("qq", qq);
                item.put("school", spec.getSchool() != null ? spec.getSchool() : "");
                item.put("hasEmail", StringUtils.isNotBlank(spec.getEmail()));
                item.put("emailSource", StringUtils.isNotBlank(spec.getEmail())
                    ? "MEMBER" : (target.isEmpty() ? "NONE" : "QQ"));
                item.put("targetEmail", EmailUtils.mask(target));
                item.put("groupName", spec.getGroupName() != null ? spec.getGroupName() : "");
                return item;
            })
            .collectList()
            .flatMap(items -> ServerResponse.ok().bodyValue(Map.of("items", items)));
    }

    private Mono<ServerResponse> listClaims(ServerRequest request) {
        return client.list(Member.class, member -> {
                var annotations = member.getMetadata().getAnnotations();
                return annotations != null 
                    && annotations.containsKey("member.plugin.halo.run/claim-token");
            }, null)
            .map(member -> {
                var annotations = member.getMetadata().getAnnotations();
                String token = annotations.get("member.plugin.halo.run/claim-token");
                String expiresStr = annotations.get("member.plugin.halo.run/claim-token-expires");
                Instant expires = null;
                if (expiresStr != null) {
                    try {
                        expires = Instant.parse(expiresStr);
                    } catch (Exception ignored) {}
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("memberName", member.getMetadata().getName());
                item.put("displayName", member.getSpec().getDisplayName());
                item.put("qq", member.getSpec().getQq());
                item.put("token", token);
                item.put("claimUrl", buildClaimUrl(token));
                item.put("expiresAt", expires != null ? expires.toString() : null);
                item.put("expired", expires != null && Instant.now().isAfter(expires));
                return item;
            })
            .collectList()
            .flatMap(items -> ServerResponse.ok().bodyValue(Map.of("items", items)));
    }

    private Mono<ServerResponse> generateClaims(ServerRequest request) {
        return request.bodyToMono(GenerateClaimsRequest.class)
            .flatMap(body -> {
                if (body.memberNames() == null || body.memberNames().isEmpty()) {
                    return errorResponse(400, "成员名称列表不能为空");
                }
                return Flux.fromIterable(body.memberNames())
                    .concatMap(name -> claimTokenService.generateToken(name, null)
                        .map(token -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("memberName", name);
                            item.put("token", token);
                            item.put("claimUrl", buildClaimUrl(token));
                            return item;
                        })
                        .onErrorResume(e -> Mono.just(Map.of(
                            "memberName", name,
                            "error", e.getMessage() != null ? e.getMessage() : "生成失败"
                        ))))
                    .collectList()
                    .flatMap(items -> ServerResponse.ok().bodyValue(Map.of(
                        "generated", items.stream().filter(i -> !i.containsKey("error")).count(),
                        "items", items
                    )));
            });
    }

    private Mono<ServerResponse> invalidateClaim(ServerRequest request) {
        String memberName = request.pathVariable("memberName");
        return claimTokenService.invalidateToken(memberName)
            .flatMap(member -> ServerResponse.ok().bodyValue(Map.of(
                "success", true,
                "message", "认领链接已作废"
            )))
            .switchIfEmpty(errorResponse(404, "成员不存在或无有效认领链接"));
    }

    private Mono<ServerResponse> distributeClaims(ServerRequest request) {
        return request.bodyToMono(DistributeClaimsRequest.class)
            .flatMap(body -> {
                if (body.memberNames() == null || body.memberNames().isEmpty()) {
                    return errorResponse(400, "成员名称列表不能为空");
                }
                // 分发即发信：站点邮件没配好就先说清楚，别生成一堆 token 却谁都收不到
                return mailDeliveryGuard.unavailabilityReason()
                    .flatMap(reason -> reason.isPresent()
                        ? errorResponse(409, reason.get())
                        : distributeTo(body.memberNames()));
            });
    }

    private Mono<ServerResponse> distributeTo(List<String> memberNames) {
        return Flux.fromIterable(memberNames)
            .concatMap(name -> claimTokenService.generateToken(name, null)
                .flatMap(token -> {
                    String claimUrl = buildClaimUrl(token);
                    return client.fetch(Member.class, name)
                        .flatMap(member -> notificationReasonPublisher
                            .publishClaimLinkNotice(member, claimUrl)
                            .thenReturn(claimItem(name, token, claimUrl, true, null))
                            .onErrorResume(error -> {
                                log.warn("分发认领链接邮件失败: {}", name, error);
                                return Mono.just(claimItem(name, token, claimUrl, false,
                                    describeSendFailure(error)));
                            }))
                        .switchIfEmpty(Mono.just(claimItem(name, token, claimUrl, false,
                            "成员不存在")));
                })
                .onErrorResume(error -> Mono.just(claimItem(name, null, null, false,
                    StringUtils.defaultIfBlank(error.getMessage(), "生成失败")))))
            .collectList()
            .flatMap(items -> ServerResponse.ok().bodyValue(Map.of(
                "generated", items.stream()
                    .filter(i -> StringUtils.isNotBlank((String) i.get("token"))).count(),
                "emailed", items.stream()
                    .filter(i -> Boolean.TRUE.equals(i.get("emailed"))).count(),
                "deliveryNote", "emailed 表示通知已提交给 Halo 通知中心，"
                    + "实际投递取决于站点邮件通知器与 SMTP 配置。",
                "items", items
            )));
    }

    private Map<String, Object> claimItem(String memberName, String token, String claimUrl,
        boolean emailed, String error) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("memberName", memberName);
        item.put("token", token == null ? "" : token);
        item.put("claimUrl", claimUrl == null ? "" : claimUrl);
        item.put("emailed", emailed);
        if (error != null) {
            item.put("error", error);
        }
        return item;
    }

    private String describeSendFailure(Throwable error) {
        if (error instanceof NotificationReasonPublisher.NoMemberEmailException) {
            return "成员未登记邮箱，无法发送认领链接邮件";
        }
        return "邮件通知提交失败：" + StringUtils.defaultIfBlank(error.getMessage(),
            error.getClass().getSimpleName());
    }

    private String buildClaimUrl(String token) {
        return externalLinkProcessor.processLink("/claim?token=" + token);
    }

    private Mono<ServerResponse> errorResponse(int status, String message) {
        return ServerResponse.status(status).bodyValue(Map.of(
            "success", false,
            "message", message
        ));
    }

    private ServerResponse errorResponseSync(int status, String message) {
        return ServerResponse.status(status).bodyValue(Map.of(
            "success", false,
            "message", message
        )).block();
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

    public record GenerateClaimsRequest(
        List<String> memberNames
    ) {}

    public record DistributeClaimsRequest(
        List<String> memberNames
    ) {}
}
