package run.halo.members.mcp;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.members.Member;
import run.halo.members.MemberConstant;
import run.halo.members.MemberGroup;
import run.halo.members.finders.MemberFinder;
import run.halo.members.service.ClaimTokenService;
import run.halo.members.service.MemberBatchService;
import run.halo.members.service.MemberQueryService;
import run.halo.members.service.MemberStatisticsService;
import run.halo.members.service.MemberWithdrawReviewService;
import run.halo.members.service.SchoolAffinityService;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.utils.AvatarUtils;
import run.halo.members.utils.QqLinkUtils;
import run.halo.members.vo.MemberGroupVo;
import run.halo.members.vo.MemberVo;
import run.halo.mcpserver.api.McpToolAnnotations;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolException;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;

/**
 * 成员 MCP 工具 · 认领域：认领令牌的生成/查询/作废与 QQ 好友链接校验。
 *
 * @since 1.5.0
 */
public class MemberMcpClaimProvider extends AbstractMemberMcpToolProvider {

    private final MemberQueryService memberQueryService;
    private final ClaimTokenService claimTokenService;
    private final ExternalLinkProcessor externalLinkProcessor;

    public MemberMcpClaimProvider(RoleService roleService, ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberQueryService memberQueryService,
        ClaimTokenService claimTokenService,
        ExternalLinkProcessor externalLinkProcessor
        ) {
        super(roleService, client, memberFinder);
        this.memberQueryService = memberQueryService;
        this.claimTokenService = claimTokenService;
        this.externalLinkProcessor = externalLinkProcessor;
    }

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(
            generateClaimTokensTool(),
            listClaimTokensTool(),
            revokeClaimTokenTool(),
            validateQqLinkTool());
    }

    private McpToolDefinition generateClaimTokensTool() {
        return McpToolDefinition.builder()
            .name("generate_claim_tokens")
            .title("Generate claim tokens")
            .description("Generate QQ-link claim tokens for members who have a QQ number but "
                + "no qqFriendLink yet. Pass member names explicitly, or use "
                + "autoDetect=true to scan approved members. Returns each member with its "
                + "one-time claim URL, built from the site external URL and pointing at the "
                + "theme /claim page, that the admin can forward to the member.")
            .displayTitle("生成认领链接")
            .displayDescription("为有 QQ 号但缺少 QQ 链接的成员批量生成一次性认领链接。")
            .category(CATEGORY_CLAIM)
            .inputSchema(objectSchema(Map.of(
                "names", stringArrayProperty("Member account names to generate tokens for. "
                    + "Takes precedence over autoDetect."),
                "autoDetect", booleanProperty("When true and names is omitted, scan approved "
                    + "members whose QQ is set but qqFriendLink is empty."),
                "limit", integerProperty("Maximum members to process in one call. 1-200, "
                    + "default 50.")),
                List.of()))
            .annotations(McpToolAnnotations.defaults("Generate claim tokens"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                var names = optionalNames(args, "names");
                final int limit = Math.clamp(intArg(args, "limit", 50), 1, 200);
                final boolean autoDetect = names == null && booleanArg(args, "autoDetect", false);

                if (names == null && !autoDetect) {
                    throw invalidArgument(
                        "Provide 'names' or set autoDetect=true.");
                }

                if (names == null) {
                    var query = new MemberQueryService.MemberQuery(
                        1, MemberQueryService.MAX_SIZE, null, "APPROVED", null, null);
                    return memberQueryService.listMembers(query).flatMap(result -> {
                        var candidates = result.getItems().stream()
                            .filter(m -> {
                                var spec = m.getSpec();
                                return spec.getQq() != null && !spec.getQq().isBlank()
                                    && (spec.getQqFriendLink() == null
                                        || spec.getQqFriendLink().isBlank());
                            })
                            .limit(limit)
                            .map(m -> m.getMetadata().getName())
                            .toList();
                        return generateTokensForNames(candidates);
                    });
                }

                return generateTokensForNames(names.subList(0, Math.min(names.size(), limit)));
            }))
            .build();
    }

    private McpToolDefinition listClaimTokensTool() {
        return McpToolDefinition.builder()
            .name("list_claim_tokens")
            .title("List claim tokens")
            .description("List outstanding claim tokens with their expiry, so the admin can "
                + "reuse a link that is still valid instead of generating another one. "
                + "Tokens are credentials: return them only to the admin who asked.")
            .displayTitle("认领链接清单")
            .displayDescription("查看仍有效的认领令牌与过期时间，避免重复生成。令牌属于凭据，仅向管理员展示。")
            .category(CATEGORY_CLAIM)
            .inputSchema(objectSchema(Map.of(
                "names", stringArrayProperty("Optional member names to inspect."),
                "includeExpired", booleanProperty("Include expired tokens. Default false."),
                "limit", integerProperty("Maximum tokens to return. 1-500, default 100.")),
                List.of()))
            .annotations(McpToolAnnotations.readOnly("List claim tokens"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final var names = optionalNames(args, "names");
                final boolean includeExpired = booleanArg(args, "includeExpired", false);
                final int limit = Math.clamp(intArg(args, "limit", 100), 1, 500);
                return client.list(Member.class, member -> annotation(member,
                        ClaimTokenService.CLAIM_TOKEN_ANNOTATION) != null, null)
                    .map(this::tokenItem)
                    .filter(item -> includeExpired || !Boolean.TRUE.equals(item.get("expired")))
                    .filter(item -> names == null || names.contains(item.get("name")))
                    .take(limit)
                    .collectList()
                    .map(items -> {
                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("total", items.size());
                        content.put("items", items);
                        return McpToolResult.success(content,
                            items.size() + " claim token(s)");
                    });
            }))
            .build();
    }

    private Map<String, Object> tokenItem(Member member) {
        var token = annotation(member, ClaimTokenService.CLAIM_TOKEN_ANNOTATION);
        var expiresAt = annotation(member, ClaimTokenService.CLAIM_TOKEN_EXPIRES_ANNOTATION);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", member.getMetadata().getName());
        item.put("displayName", member.getSpec() == null ? null
            : member.getSpec().getDisplayName());
        item.put("token", token);
        item.put("claimUrl", token == null ? null : buildClaimUrl(token));
        item.put("expiresAt", expiresAt);
        item.put("expired", expiresAt != null && isExpired(expiresAt));
        return item;
    }

    private static boolean isExpired(String expiresAt) {
        try {
            return Instant.now().isAfter(Instant.parse(expiresAt));
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private McpToolDefinition revokeClaimTokenTool() {
        return McpToolDefinition.builder()
            .name("revoke_claim_token")
            .title("Revoke claim tokens")
            .description("Revoke claim tokens so the existing links stop working, for example "
                + "after the link was sent to the wrong person. Members without a token are "
                + "reported as NONE.")
            .displayTitle("作废认领链接")
            .displayDescription("作废指定成员的认领令牌，旧链接立即失效；无令牌的成员返回 NONE。")
            .category(CATEGORY_CLAIM)
            .inputSchema(objectSchema(Map.of(
                "names", stringArrayProperty("Member account names whose tokens should be "
                    + "revoked.")),
                List.of("names")))
            .annotations(McpToolAnnotations.defaults("Revoke claim tokens"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final var names = requireNames(args);
                return Flux.fromIterable(names)
                    .concatMap(name -> client.fetch(Member.class, name)
                        .flatMap(member -> {
                            if (annotation(member,
                                ClaimTokenService.CLAIM_TOKEN_ANNOTATION) == null) {
                                return Mono.just(reviewItem(name, "NONE",
                                    "No claim token to revoke."));
                            }
                            return claimTokenService.invalidateToken(name)
                                .map(saved -> reviewItem(name, "REVOKED",
                                    "Claim token revoked."));
                        })
                        .switchIfEmpty(Mono.fromSupplier(() -> reviewItem(name, "NOT_FOUND",
                            "Member " + name + " was not found.")))
                        .onErrorResume(error -> Mono.just(reviewItem(name, "FAILED",
                            error.getMessage() != null ? error.getMessage() : "unknown"))))
                    .collectList()
                    .map(items -> {
                        long revoked = items.stream()
                            .filter(item -> "REVOKED".equals(item.get("status")))
                            .count();
                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("total", items.size());
                        content.put("revoked", revoked);
                        content.put("items", items);
                        return McpToolResult.success(content,
                            revoked + " of " + items.size() + " claim token(s) revoked");
                    });
            }))
            .build();
    }

    private McpToolDefinition validateQqLinkTool() {
        return McpToolDefinition.builder()
            .name("validate_qq_link")
            .title("Validate QQ friend link")
            .description("Validate and normalize a QQ friend link before storing it. Accepts "
                + "https://qm.qq.com/q/<token> and https://qm.qq.com/cgi-bin/qm/qr?k=<token>, "
                + "and adds the https scheme when it is missing.")
            .displayTitle("校验 QQ 加好友链接")
            .displayDescription("校验并归一化 QQ 加好友链接，支持 /q/ 与 /cgi-bin/qm/qr 两种官方形态。")
            .category(CATEGORY_QUERY)
            .inputSchema(objectSchema(Map.of(
                "link", stringProperty("Candidate QQ friend link.")),
                List.of("link")))
            .annotations(McpToolAnnotations.readOnly("Validate QQ friend link"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(), args -> {
                var raw = requireString(args, "link");
                final var normalized = normalizeQqLinkArg(raw);
                var message = normalized.isEmpty() ? QqLinkUtils.RULE_MESSAGE : null;
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("valid", message == null);
                content.put("link", normalized.isEmpty() ? raw.trim() : normalized);
                content.put("message", message);
                return Mono.just(McpToolResult.success(content, message == null
                    ? "Link accepted" : message));
            }))
            .build();
    }

    private String buildClaimUrl(String token) {
        return externalLinkProcessor.processLink("/claim?token=" + token);
    }

    private Mono<McpToolResult> generateTokensForNames(List<String> names) {
        if (names.isEmpty()) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("generated", 0);
            content.put("items", List.of());
            return Mono.just(McpToolResult.success(content, "No members to generate tokens for"));
        }
        return Flux.fromIterable(names)
            .concatMap(name -> claimTokenService.generateToken(name, null)
                .map(token -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", name);
                    item.put("token", token);
                    item.put("claimUrl", buildClaimUrl(token));
                    return item;
                })
                .onErrorResume(error -> Mono.just(Map.of(
                    "name", name,
                    "error", error.getMessage() != null ? error.getMessage() : "unknown"))))
            .collectList()
            .map(items -> {
                long generated = items.stream().filter(i -> !i.containsKey("error")).count();
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("generated", generated);
                content.put("failed", items.size() - generated);
                content.put("items", items);
                return McpToolResult.success(content,
                    "%d claim token(s) generated".formatted(generated));
            });
    }
}
