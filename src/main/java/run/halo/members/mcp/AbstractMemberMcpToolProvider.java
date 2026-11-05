package run.halo.members.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * 成员 MCP 工具的公共基类：角色校验、参数解析、schema 构造、结果拼装。
 *
 * <p>原先 2600 余行的单体 provider 已按域拆成 5 个子类（成员档案 / 分组 / 审核 / 认领 / 统计），
 * 每个子类只声明自己用得到的服务，改一个工具不必再翻整个文件。
 * 这里只放与具体域无关的东西，业务规则仍归各域。
 *
 * @since 1.5.0
 */
public abstract class AbstractMemberMcpToolProvider implements McpToolProvider {

    static final String VIEW_ROLE = "role-template-member-view";
    static final String MANAGE_ROLE = "role-template-member-manage";

    static final String CATEGORY_QUERY = "成员查询";
    static final String CATEGORY_REVIEW = "成员审核";
    static final String GROUP_CATEGORY = "成员分组";
    static final String CATEGORY_CLAIM = "成员认领";
    static final String CATEGORY_AFFINITY = "成员关系";

    static final Set<String> MEMBER_STATUSES =
        Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAW_REQUESTED");

    /** 成员摘要可投影的字段，用于按需裁剪返回体积。 */
    static final List<String> SUMMARY_FIELDS = List.of(
        "name", "displayName", "school", "groupName", "status");

    protected final RoleService roleService;
    protected final ReactiveExtensionClient client;
    protected final MemberFinder memberFinder;

    protected AbstractMemberMcpToolProvider(RoleService roleService,
        ReactiveExtensionClient client, MemberFinder memberFinder) {
        this.roleService = roleService;
        this.client = client;
        this.memberFinder = memberFinder;
    }

    static String annotation(Member member, String key) {
        var annotations = member == null ? null : member.getMetadata().getAnnotations();
        if (annotations == null) {
            return null;
        }
        var value = annotations.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    Mono<Boolean> canView() {
        return hasRole(VIEW_ROLE);
    }

    Mono<Boolean> canManage() {
        return hasRole(MANAGE_ROLE);
    }

    /**
     * Halo 内置超级管理员会被 {@link RoleService#contains} 直接放行；其余账号按角色模板及其
     * 依赖关系判断，因此自定义角色只要声明依赖也能获得授权。
     */

    /**
     * Halo 内置超级管理员会被 {@link RoleService#contains} 直接放行；其余账号按角色模板及其
     * 依赖关系判断，因此自定义角色只要声明依赖也能获得授权。
     */
    Mono<Boolean> hasRole(String role) {
        return currentUsername()
            .flatMap(username -> roleService.getRolesByUsername(username)
                .collect(Collectors.toSet()))
            .flatMap(roleNames -> roleService.contains(roleNames, Set.of(role)))
            .defaultIfEmpty(false);
    }

    Mono<String> currentUsername() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(Authentication::isAuthenticated)
            .map(Authentication::getName)
            .filter(name -> !name.isBlank());
    }

    Mono<McpToolResult> execute(McpToolInvocation invocation, Mono<Boolean> permitted,
        Function<Map<String, Object>, Mono<McpToolResult>> action) {
        var arguments = invocation.arguments() == null
            ? Map.<String, Object>of() : invocation.arguments();
        return permitted.flatMap(granted -> {
            if (!Boolean.TRUE.equals(granted)) {
                return Mono.just(McpToolResult.error("FORBIDDEN",
                    "The access key owner needs the member view or manage permission."));
            }
            return action.apply(arguments);
        });
    }

    Map<String, Object> pageContent(ListResult<MemberVo> result, List<String> fields) {
        var items = result.getItems().stream()
            .map(member -> memberSummary(member, fields))
            .toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("page", result.getPage());
        content.put("size", result.getSize());
        content.put("total", result.getTotal());
        content.put("fields", fields);
        content.put("items", items);
        return content;
    }

    String summary(ListResult<MemberVo> result) {
        return "%d of %d members on page %d".formatted(
            result.getItems().size(), result.getTotal(), result.getPage());
    }

    static Map<String, Object> memberSummary(MemberVo member, List<String> fields) {
        var spec = member.getSpec();
        Map<String, Object> content = new LinkedHashMap<>();
        for (var field : fields) {
            switch (field) {
                case "name" -> content.put("name", member.getMetadata().getName());
                case "displayName" -> content.put("displayName", spec.getDisplayName());
                case "school" -> content.put("school", spec.getSchool());
                case "groupName" -> content.put("groupName", spec.getGroupName());
                default -> content.put("status", spec.getStatus());
            }
        }
        return content;
    }

    Map<String, Object> memberDetail(Member member) {
        var spec = member.getSpec();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", member.getMetadata().getName());
        content.put("displayName", spec.getDisplayName());
        content.put("email", spec.getEmail());
        content.put("school", spec.getSchool());
        content.put("qq", spec.getQq());
        content.put("groupName", spec.getGroupName());
        content.put("status", spec.getStatus());
        content.put("origin", spec.getOrigin());
        content.put("createdAt", member.getMetadata().getCreationTimestamp());
        var annotations = member.getMetadata().getAnnotations();
        if (annotations != null) {
            content.put("reviewDescription", annotations.get(MemberConstant.REVIEW_DESCRIPTION));
            content.put("withdrawReason", annotations.get(MemberConstant.WITHDRAW_REASON));
        }
        return content;
    }

    Map<String, Object> groupContent(MemberGroupVo group) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", group.getMetadata().getName());
        content.put("displayName", group.getSpec().getDisplayName());
        content.put("description", group.getSpec().getDescription());
        return content;
    }

    Map<String, Object> batchContent(MemberBatchService.BatchOperationResult result) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("total", result.total());
        content.put("success", result.success());
        content.put("failed", result.failed());
        content.put("failedMembers", result.failedMembers());
        content.put("message", result.message());
        return content;
    }

    static List<String> requireNames(Map<String, Object> args) {
        var names = optionalNames(args, "names");
        if (names == null || names.isEmpty()) {
            throw invalidArgument("Argument 'names' must be a non-empty array of strings.");
        }
        return names;
    }

    static List<String> optionalNames(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof List<?> list)) {
            return null;
        }
        var result = list.stream()
            .map(name -> name == null ? null : String.valueOf(name).trim())
            .filter(name -> name != null && !name.isEmpty())
            .distinct()
            .toList();
        return result.isEmpty() ? null : result;
    }

    static boolean booleanArg(Map<String, Object> args, String key,
        boolean defaultValue) {
        return switch (args.get(key)) {
            case Boolean value -> value;
            case String value -> "true".equalsIgnoreCase(value.trim());
            case null, default -> defaultValue;
        };
    }

    /**
     * 接受 ISO 8601 时间点（2026-01-01T00:00:00Z）或纯日期（2026-01-01，按 UTC 起始计）。
     */

    /**
     * 接受 ISO 8601 时间点（2026-01-01T00:00:00Z）或纯日期（2026-01-01，按 UTC 起始计）。
     */
    static Instant instantArg(Map<String, Object> args, String key) {
        var value = stringArg(args, key);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ex) {
                throw invalidArgument("Argument '" + key + "' must be an ISO 8601 instant "
                    + "or date.");
            }
        }
    }

    static String requireString(Map<String, Object> args, String key) {
        var value = stringArg(args, key);
        if (value == null) {
            throw invalidArgument("Argument '" + key + "' is required.");
        }
        return value;
    }

    static String stringArg(Map<String, Object> args, String key) {
        return switch (args.get(key)) {
            case null -> null;
            case String value -> value.isBlank() ? null : value.trim();
            case Number value -> value.toString();
            case Boolean value -> value.toString();
            default -> null;
        };
    }

    static int intArg(Map<String, Object> args, String key, int defaultValue) {
        return switch (args.get(key)) {
            case null -> defaultValue;
            case Number value -> value.intValue();
            case String value -> {
                try {
                    yield Integer.parseInt(value.trim());
                } catch (NumberFormatException ex) {
                    yield defaultValue;
                }
            }
            default -> defaultValue;
        };
    }

    static boolean requireBoolean(Map<String, Object> args, String key) {
        return switch (args.get(key)) {
            case Boolean value -> value;
            case String value -> switch (value.trim().toLowerCase()) {
                case "true" -> true;
                case "false" -> false;
                default -> throw invalidArgument(
                    "Argument '" + key + "' must be a boolean.");
            };
            default -> throw invalidArgument("Argument '" + key + "' is required.");
        };
    }

    /**
     * 归一化并校验 QQ 加好友链接：补/升级为 https，交给 {@link QqLinkUtils} 判官方形态。
     *
     * @return 合法时的规范链接；非法返回空串
     */

    /**
     * 归一化并校验 QQ 加好友链接：补/升级为 https，交给 {@link QqLinkUtils} 判官方形态。
     *
     * @return 合法时的规范链接；非法返回空串
     */
    static String normalizeQqLinkArg(String raw) {
        var value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.regionMatches(true, 0, "http://", 0, 7)) {
            value = "https://" + value.substring(7);
        } else if (!value.contains("://")) {
            value = "https://" + value;
        }
        return QqLinkUtils.validate(value) == null ? value : "";
    }

    static McpToolException invalidArgument(String message) {
        return new McpToolException("INVALID_ARGUMENT", message);
    }

    static Map<String, Object> objectSchema(Map<String, Object> properties,
        List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    static Map<String, Object> stringProperty(String description) {
        return property("string", description);
    }

    static Map<String, Object> integerProperty(String description) {
        return property("integer", description);
    }

    static Map<String, Object> booleanProperty(String description) {
        return property("boolean", description);
    }

    static Map<String, Object> stringArrayProperty(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", property("string", null));
        schema.put("description", description);
        return schema;
    }

    static Map<String, Object> enumProperty(List<String> values, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", values);
        if (description != null) {
            schema.put("description", description);
        }
        return schema;
    }

    static Map<String, Object> enumArrayProperty(List<String> values,
        String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", enumProperty(values, null));
        schema.put("description", description);
        return schema;
    }

    static Map<String, Object> property(String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        if (description != null) {
            schema.put("description", description);
        }
        return schema;
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static boolean withinRange(MemberVo member, Instant after, Instant before) {
        Instant created = member.getMetadata().getCreationTimestamp();
        if (created == null) {
            return after == null && before == null;
        }
        return (after == null || !created.isBefore(after))
            && (before == null || !created.isAfter(before));
    }

    static Map<String, Object> reviewItem(String name, String status, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("status", status);
        item.put("message", message);
        return item;
    }
}
