package run.halo.members.endpoint;

import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.members.Member;
import run.halo.members.MemberGroup;
import run.halo.members.NotificationReasonPublisher;
import run.halo.members.exception.ProblemResponses;
import run.halo.members.service.ClaimTokenService;
import run.halo.members.service.MailDeliveryGuard;
import run.halo.members.utils.EmailUtils;

/**
 * 认领管理的**站内**接口：只在账号已登录且具备成员管理权限时可用。
 *
 * <p>这一组接口原先与匿名认领流程混在 {@code api.plugin.halo.run} 分组里，而 Halo 核心的
 * {@code role-template-public-apis} 对该分组授的是通配 {@code resources: ["*"]} +
 * {@code verbs: ["get", "list"]}——也就是说插件自己写什么规则都不影响，该分组下的
 * **任何 GET 端点默认就是匿名可读的**。于是「列出全部认领令牌」连同 {@code claimUrl}
 * 能被未登录访客直接拉走（认领令牌等同于成员身份凭证）；而同样这些路由的 POST/DELETE
 * 反倒会被 RBAC 拦住，形成"看得见、改不了"的假象。
 *
 * <p>现在整体搬到 {@code api.member.plugin.halo.run}：该分组没有核心通配规则，
 * 匿名访问被 Halo 拦成登录跳转，登录用户还要过 {@code roleTemplate.yaml} 里
 * {@code members/claim*} 的 RBAC 规则。教训是**分组选错等于没有权限**，
 * 含敏感数据的 GET 不能落在 {@code api.plugin.halo.run} 上。
 *
 * @since 2.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberClaimAdminEndpoint implements CustomEndpoint {

    private final ReactiveExtensionClient client;
    private final ClaimTokenService claimTokenService;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final NotificationReasonPublisher notificationReasonPublisher;
    private final MailDeliveryGuard mailDeliveryGuard;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.member.plugin.halo.run/v1alpha1/MemberClaimAdmin";
        return route()
            .GET("members/-/incomplete", this::listIncompleteMembers,
                builder -> builder.operationId("ListIncompleteMembers")
                    .description("列出缺少 QQ 好友链接的成员（需成员管理权限）")
                    .tag(tag))
            .GET("members/-/claims", this::listClaims,
                builder -> builder.operationId("ListClaims")
                    .description("列出全部有效认领令牌（需成员管理权限）")
                    .tag(tag))
            .POST("members/-/claims-generate", this::generateClaims,
                builder -> builder.operationId("GenerateClaims")
                    .description("为指定成员生成认领令牌（需成员管理权限）")
                    .tag(tag))
            .POST("members/-/claims-distribute", this::distributeClaims,
                builder -> builder.operationId("DistributeClaims")
                    .description("生成并通过通知中心分发认领链接（需成员管理权限）")
                    .tag(tag))
            .DELETE("members/{memberName}/claim", this::invalidateClaim,
                builder -> builder.operationId("InvalidateClaim")
                    .description("作废指定成员的认领令牌（需成员管理权限）")
                    .tag(tag))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.member.plugin.halo.run/v1alpha1");
    }

    private String buildClaimUrl(String token) {
        return externalLinkProcessor.processLink("/claim?token=" + token);
    }

    private Mono<ServerResponse> errorResponse(int status, String message) {
        HttpStatus httpStatus = HttpStatus.resolve(status);
        return ProblemResponses.problem(httpStatus == null ? HttpStatus.BAD_REQUEST : httpStatus,
            "invalid-claim-request", "认领管理请求无效", message);
    }

    private Mono<ServerResponse> listIncompleteMembers(ServerRequest request) {
        return client.list(Member.class, member -> {
                var spec = member.getSpec();
                return spec.getQqFriendLink() == null || spec.getQqFriendLink().isBlank();
            }, null)
            .collectList()
            .flatMap(members -> resolveGroupLabels(members).map(groupLabels -> members.stream()
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
                    putGroup(item, spec, groupLabels);
                    return item;
                })
                .toList()
            ))
            .flatMap(items -> ServerResponse.ok().bodyValue(Map.of("items", items)));
    }

    private Mono<ServerResponse> listClaims(ServerRequest request) {
        return client.list(Member.class, member -> {
                var annotations = member.getMetadata().getAnnotations();
                return annotations != null
                    && annotations.containsKey("member.plugin.halo.run/claim-token");
            }, null)
            .collectList()
            .flatMap(members -> resolveGroupLabels(members).map(groupLabels -> members.stream()
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
                    putGroup(item, member.getSpec(), groupLabels);
                    return item;
                })
                .toList()
            ))
            .flatMap(items -> ServerResponse.ok().bodyValue(Map.of("items", items)));
    }

    /**
     * 一次性解析这批成员所属分组的展示名，避免在成员循环里逐个查分组造成 N+1。
     */
    private Mono<Map<String, String>> resolveGroupLabels(List<Member> members) {
        List<String> groupNames = members.stream()
            .map(member -> member.getSpec() == null ? null : member.getSpec().getGroupName())
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
        if (groupNames.isEmpty()) {
            return Mono.just(Map.of());
        }
        return client.list(MemberGroup.class,
                group -> groupNames.contains(group.getMetadata().getName()), null)
            .collectMap(group -> group.getMetadata().getName(),
                group -> group.getSpec() == null || StringUtils.isBlank(group.getSpec().getDisplayName())
                    ? "" : group.getSpec().getDisplayName())
            .defaultIfEmpty(Map.of());
    }

    /**
     * 分组展示名优先取分组的 displayName，缺失时回退到分组标识，两者都没有则视为未分组。
     */
    private void putGroup(Map<String, Object> item, Member.MemberSpec spec,
        Map<String, String> groupLabels) {
        if (spec == null) {
            item.put("groupName", "");
            item.put("groupDisplayName", "");
            return;
        }
        String groupName = StringUtils.isNotBlank(spec.getGroupName()) ? spec.getGroupName() : "";
        String displayName = groupName.isEmpty() ? "" : groupLabels.get(groupName);
        item.put("groupName", groupName);
        item.put("groupDisplayName", StringUtils.isNotBlank(displayName) ? displayName : "");
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

    public record GenerateClaimsRequest(
        List<String> memberNames
    ) {}

    public record DistributeClaimsRequest(
        List<String> memberNames
    ) {}
}
