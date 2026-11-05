package run.halo.members.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * 成员 MCP 工具 · 分组域：分组增删改与成员归组。
 *
 * <p>删除分组只允许删空分组，成员不会因为删组而失去归属。
 *
 * @since 1.5.0
 */
public class MemberMcpGroupProvider extends AbstractMemberMcpToolProvider {

    private final MemberQueryService memberQueryService;
    private final MemberBatchService memberBatchService;

    public MemberMcpGroupProvider(RoleService roleService, ReactiveExtensionClient client,
        MemberFinder memberFinder,
        MemberQueryService memberQueryService,
        MemberBatchService memberBatchService
        ) {
        super(roleService, client, memberFinder);
        this.memberQueryService = memberQueryService;
        this.memberBatchService = memberBatchService;
    }

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(
            listMemberGroupsTool(),
            createMemberGroupTool(),
            updateMemberGroupTool(),
            deleteMemberGroupTool(),
            assignMemberGroupTool(),
            autoAssignGroupTool());
    }

    private McpToolDefinition listMemberGroupsTool() {
        return McpToolDefinition.builder()
            .name("list_member_groups")
            .title("List member groups")
            .description("List all member groups used to organize published members.")
            .displayTitle("成员分组列表")
            .displayDescription("读取全部成员分组及其说明。")
            .category(GROUP_CATEGORY)
            .inputSchema(objectSchema(Map.of(), List.of()))
            .annotations(McpToolAnnotations.readOnly("List member groups"))
            .permission(invocation -> canView())
            .handler(invocation -> execute(invocation, canView(),
                args -> memberFinder.listAllGroups()
                    .collectList()
                    .map(groups -> {
                        var items = groups.stream().map(this::groupContent).toList();
                        return McpToolResult.success(
                            Map.of("total", items.size(), "items", items));
                    })))
            .build();
    }

    private McpToolDefinition createMemberGroupTool() {
        return McpToolDefinition.builder()
            .name("create_member_group")
            .title("Create member group")
            .description("Create a member group. The display name must be unique; move members "
                + "into it afterwards with assign_member_group.")
            .displayTitle("新建成员分组")
            .displayDescription("新建成员分组，名称不可重复；建好后可用调整分组工具移入成员。")
            .category(GROUP_CATEGORY)
            .inputSchema(objectSchema(Map.of(
                "displayName", stringProperty("Group name shown on the site."),
                "description", stringProperty("Optional group description."),
                "name", stringProperty("Optional metadata.name; generated from the display "
                    + "name when omitted.")),
                List.of("displayName")))
            .annotations(McpToolAnnotations.defaults("Create member group"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final var displayName = requireString(args, "displayName");
                final var description = stringArg(args, "description");
                final var explicitName = stringArg(args, "name");
                return memberFinder.listAllGroups()
                    .collectList()
                    .flatMap(groups -> {
                        var duplicated = groups.stream().anyMatch(group -> {
                            var spec = group.getSpec();
                            return spec != null && spec.getDisplayName() != null
                                && displayName.equalsIgnoreCase(spec.getDisplayName().trim());
                        });
                        if (duplicated) {
                            return Mono.just(McpToolResult.error("GROUP_EXISTS",
                                "Member group " + displayName + " already exists."));
                        }
                        var group = new MemberGroup();
                        var metadata = new Metadata();
                        metadata.setName(explicitName != null ? explicitName
                            : generateGroupName(displayName));
                        group.setMetadata(metadata);
                        var spec = new MemberGroup.MemberGroupSpec();
                        spec.setDisplayName(displayName);
                        spec.setDescription(description);
                        group.setSpec(spec);
                        return client.create(group)
                            .map(created -> McpToolResult.success(
                                groupContent(MemberGroupVo.from(created)),
                                "Member group " + displayName + " created"));
                    });
            }))
            .build();
    }

    private McpToolDefinition updateMemberGroupTool() {
        return McpToolDefinition.builder()
            .name("update_member_group")
            .title("Update member group")
            .description("Rename a member group or change its description. Members keep their "
                + "group membership because the rename is cascaded to every member that "
                + "referenced the old name.")
            .displayTitle("修改成员分组")
            .displayDescription("重命名分组或修改说明；重命名会同步更新成员的所属分组。")
            .category(GROUP_CATEGORY)
            .inputSchema(objectSchema(Map.of(
                "name", stringProperty("Group metadata.name."),
                "displayName", stringProperty("New group name shown on the site."),
                "description", stringProperty("New group description; pass an empty string to "
                    + "clear it.")),
                List.of("name")))
            .annotations(McpToolAnnotations.defaults("Update member group"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final var name = requireString(args, "name");
                final var newDisplayName = stringArg(args, "displayName");
                final var hasDescription = args.containsKey("description");
                final var newDescription = stringArg(args, "description");
                if (newDisplayName == null && !hasDescription) {
                    throw invalidArgument("Provide 'displayName' or 'description' to update.");
                }
                return client.fetch(MemberGroup.class, name)
                    .switchIfEmpty(Mono.error(new McpToolException("NOT_FOUND",
                        "Member group " + name + " was not found.")))
                    .flatMap(group -> {
                        var spec = group.getSpec() != null ? group.getSpec()
                            : new MemberGroup.MemberGroupSpec();
                        final String previousName = spec.getDisplayName();
                        if (newDisplayName != null) {
                            spec.setDisplayName(newDisplayName);
                        }
                        if (hasDescription) {
                            spec.setDescription(newDescription);
                        }
                        group.setSpec(spec);
                        return client.update(group).flatMap(updated -> {
                            var content = groupContent(MemberGroupVo.from(updated));
                            if (previousName == null || newDisplayName == null
                                || previousName.equals(newDisplayName)) {
                                content.put("membersMoved", 0L);
                                return Mono.just(McpToolResult.success(content,
                                    "Member group " + name + " updated"));
                            }
                            content.put("renamedFrom", previousName);
                            return renameGroupMembers(previousName, newDisplayName)
                                .map(moved -> {
                                    content.put("membersMoved", moved);
                                    return McpToolResult.success(content,
                                        "Member group renamed to " + newDisplayName
                                            + ", " + moved + " member(s) moved");
                                });
                        });
                    });
            }))
            .build();
    }

    private Mono<Long> renameGroupMembers(String previousName, String newName) {
        return client.list(Member.class, member -> member.getSpec() != null
                && previousName.equals(member.getSpec().getGroupName()), null)
            .concatMap(member -> {
                member.getSpec().setGroupName(newName);
                return client.update(member);
            })
            .count();
    }

    private McpToolDefinition deleteMemberGroupTool() {
        return McpToolDefinition.builder()
            .name("delete_member_group")
            .title("Delete member group")
            .description("Delete an empty member group. Groups that still contain members are "
                + "rejected, so move or ungroup those members first.")
            .displayTitle("删除成员分组")
            .displayDescription("删除空分组；分组内仍有成员时拒绝删除，避免成员失去归属。")
            .category(GROUP_CATEGORY)
            .inputSchema(objectSchema(Map.of(
                "name", stringProperty("Group metadata.name.")),
                List.of("name")))
            .annotations(McpToolAnnotations.defaults("Delete member group"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final var name = requireString(args, "name");
                return client.fetch(MemberGroup.class, name)
                    .switchIfEmpty(Mono.error(new McpToolException("NOT_FOUND",
                        "Member group " + name + " was not found.")))
                    .flatMap(group -> {
                        var spec = group.getSpec();
                        var displayName = spec == null ? null : spec.getDisplayName();
                        if (displayName == null || displayName.isBlank()) {
                            return client.delete(group).thenReturn(
                                McpToolResult.success(Map.of("name", name, "deleted", true),
                                    "Member group " + name + " deleted"));
                        }
                        return client.list(Member.class, member -> member.getSpec() != null
                                && displayName.equals(member.getSpec().getGroupName()), null)
                            .count()
                            .flatMap(count -> {
                                if (count > 0) {
                                    return Mono.just(McpToolResult.error("GROUP_NOT_EMPTY",
                                        "Member group " + displayName + " still has " + count
                                            + " member(s). Reassign them first."));
                                }
                                return client.delete(group).thenReturn(McpToolResult.success(
                                    Map.of("name", name, "displayName", displayName,
                                        "deleted", true),
                                    "Member group " + displayName + " deleted"));
                            });
                    });
            }))
            .build();
    }

    private static String generateGroupName(String displayName) {
        var slug = displayName.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+)|(-+$)", "");
        if (slug.isEmpty()) {
            slug = "group";
        }
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private McpToolDefinition assignMemberGroupTool() {
        return McpToolDefinition.builder()
            .name("assign_member_group")
            .title("Assign member group")
            .description("Move members into an existing member group in bulk.")
            .displayTitle("调整成员分组")
            .displayDescription("批量把成员移动到指定分组，需成员管理权限。")
            .category(CATEGORY_REVIEW)
            .inputSchema(objectSchema(Map.of(
                "names", stringArrayProperty("Member account names to move."),
                "groupName", stringProperty("Target member group name.")),
                List.of("names", "groupName")))
            .annotations(McpToolAnnotations.defaults("Assign member group"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                var names = requireNames(args);
                var groupName = requireString(args, "groupName");
                return memberBatchService.batchChangeGroup(names, groupName)
                    .map(result -> McpToolResult.success(batchContent(result),
                        result.message()));
            }))
            .build();
    }

    private McpToolDefinition autoAssignGroupTool() {
        return McpToolDefinition.builder()
            .name("auto_assign_group")
            .title("Auto assign member groups")
            .description("Move members into existing groups by school keyword rules. The rules "
                + "object maps a school keyword to a target group name and the first matching "
                + "rule wins. Defaults to a dry run preview; pass dryRun=false to apply. Use "
                + "assign_member_group when the member names are already known.")
            .displayTitle("按学校自动分组")
            .displayDescription("按学校关键词规则批量调整成员分组，默认先预览，再执行移动。")
            .category(GROUP_CATEGORY)
            .inputSchema(objectSchema(Map.of(
                "rules", rulesProperty("Object mapping a school keyword to an existing member "
                    + "group name, e.g. {\"清华\": \"union\"}. First matching rule wins."),
                "onlyUngrouped", booleanProperty("Only move members without a group. "
                    + "Default true."),
                "status", enumProperty(List.copyOf(MEMBER_STATUSES),
                    "Member status filter. Default APPROVED."),
                "dryRun", booleanProperty("When true (default), only list planned "
                    + "assignments without changing anything."),
                "limit", integerProperty("Maximum members to move. 1-200, default 50.")),
                List.of("rules")))
            .annotations(McpToolAnnotations.defaults("Auto assign member groups"))
            .permission(invocation -> canManage())
            .handler(invocation -> execute(invocation, canManage(), args -> {
                final Map<String, String> rules = requireRules(args);
                final boolean onlyUngrouped = booleanArg(args, "onlyUngrouped", true);
                final String statusArg = stringArg(args, "status");
                final String status = statusArg == null ? "APPROVED" : statusArg;
                final boolean dryRun = booleanArg(args, "dryRun", true);
                final int limit = Math.clamp(intArg(args, "limit", 50), 1, 200);

                return memberFinder.listAllGroups().collectList().flatMap(groups -> {
                    Set<String> knownGroups = groups.stream()
                        .map(group -> group.getMetadata().getName())
                        .collect(Collectors.toSet());
                    var missingGroups = rules.values().stream()
                        .filter(groupName -> !knownGroups.contains(groupName))
                        .distinct()
                        .toList();
                    if (!missingGroups.isEmpty()) {
                        throw invalidArgument("Unknown member group(s): "
                            + String.join(", ", missingGroups)
                            + ". Use list_member_groups to see available groups.");
                    }

                    var query = new MemberQueryService.MemberQuery(
                        1, MemberQueryService.MAX_SIZE, null, status, null,
                        "createdTime-asc");
                    return memberQueryService.listMembers(query).flatMap(result -> {
                        var assignments = new ArrayList<Map<String, Object>>();
                        Map<String, List<String>> byGroup = new LinkedHashMap<>();
                        int matched = 0;
                        for (MemberVo member : result.getItems()) {
                            var spec = member.getSpec();
                            if (onlyUngrouped && spec.getGroupName() != null
                                && !spec.getGroupName().isBlank()) {
                                continue;
                            }
                            String targetGroup = matchGroup(spec.getSchool(), rules);
                            if (targetGroup == null
                                || targetGroup.equals(spec.getGroupName())) {
                                continue;
                            }
                            matched++;
                            if (assignments.size() >= limit) {
                                continue;
                            }
                            Map<String, Object> assignment = new LinkedHashMap<>();
                            assignment.put("name", member.getMetadata().getName());
                            assignment.put("displayName", spec.getDisplayName());
                            assignment.put("school", spec.getSchool());
                            assignment.put("fromGroup", spec.getGroupName());
                            assignment.put("toGroup", targetGroup);
                            assignments.add(assignment);
                            byGroup.computeIfAbsent(targetGroup, key -> new ArrayList<>())
                                .add(member.getMetadata().getName());
                        }

                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("dryRun", dryRun);
                        content.put("status", status);
                        content.put("onlyUngrouped", onlyUngrouped);
                        content.put("limit", limit);
                        content.put("scanned", result.getTotal());
                        content.put("matched", matched);
                        content.put("planned", assignments.size());
                        content.put("assignments", assignments);

                        if (dryRun || byGroup.isEmpty()) {
                            String message = dryRun
                                ? "Dry run: %d members would be assigned".formatted(
                                    assignments.size())
                                : "No members matched the group rules";
                            return Mono.just(McpToolResult.success(content, message));
                        }

                        return Flux.fromIterable(byGroup.entrySet())
                            .concatMap(entry -> memberBatchService
                                .batchChangeGroup(entry.getValue(), entry.getKey())
                                .map(batch -> {
                                    Map<String, Object> item = batchContent(batch);
                                    item.put("groupName", entry.getKey());
                                    return item;
                                }))
                            .collectList()
                            .map(results -> {
                                content.put("result", results);
                                int moved = results.stream()
                                    .mapToInt(item -> (Integer) item.get("success"))
                                    .sum();
                                return McpToolResult.success(content,
                                    "%d of %d members moved".formatted(moved,
                                        assignments.size()));
                            });
                    });
                });
            }))
            .build();
    }

    private static Map<String, String> requireRules(Map<String, Object> args) {
        if (!(args.get("rules") instanceof Map<?, ?> raw) || raw.isEmpty()) {
            throw invalidArgument("Argument 'rules' must be a non-empty object mapping a "
                + "school keyword to a group name.");
        }
        Map<String, String> rules = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String keyword = key == null ? null : String.valueOf(key).trim();
            String groupName = value == null ? null : String.valueOf(value).trim();
            if (keyword == null || keyword.isEmpty() || groupName == null
                || groupName.isEmpty()) {
                throw invalidArgument("Each rule needs a school keyword and a group name.");
            }
            rules.put(keyword, groupName);
        });
        return rules;
    }

    private static String matchGroup(String school, Map<String, String> rules) {
        if (school == null || school.isBlank()) {
            return null;
        }
        String haystack = school.toLowerCase();
        for (var entry : rules.entrySet()) {
            if (haystack.contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, Object> rulesProperty(String description) {
        Map<String, Object> schema = property("object", description);
        schema.put("additionalProperties", property("string", null));
        return schema;
    }
}
