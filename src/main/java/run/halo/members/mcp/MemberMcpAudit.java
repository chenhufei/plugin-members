package run.halo.members.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import run.halo.members.Member;
import run.halo.members.vo.MemberGroupVo;
import run.halo.members.vo.MemberVo;
import run.halo.members.utils.AvatarUtils;

/**
 * 成员数据巡检引擎：与 HTTP/MCP 无关的纯计算部分。
 *
 * <p>成员档案域（{@code audit_member_data}）与统计域（概览快照的 health 分区）
 * 都要跑同一套判定，因此从 provider 里抽出来做成静态工具，
 * 保证两处对「重复 / 联系方式 / 学校 / 分组 / 头像 / 状态」的口径永远一致。
 *
 * @since 1.5.0
 */
final class MemberMcpAudit {

    /** 数据巡检项：重复、联系方式、学校、分组、头像、状态。 */
    static final List<String> CHECKS = List.of(
        "duplicate", "contact", "school", "group", "avatar", "status");

    private MemberMcpAudit() {
    }

    /**
     * 分组的可识别标识集合。
     *
     * <p>成员的 {@code groupName} 存的是分组**资源名**（如 {@code member-group-xxxx}），
     * 但老数据里也可能残留显示名（如「高校联盟」），导入时两者都会做双向解析。
     * 因此这里同时收集资源名与显示名，避免把「分组存在但存法不同」误报成分组缺失。
     */
    static Set<String> groupIdentifiers(List<MemberGroupVo> groups) {
        return groups.stream()
            .filter(group -> group != null && group.getMetadata() != null
                && group.getMetadata().getName() != null)
            .flatMap(group -> {
                var identifiers = new ArrayList<String>();
                identifiers.add(group.getMetadata().getName());
                if (group.getSpec() != null && group.getSpec().getDisplayName() != null) {
                    identifiers.add(group.getSpec().getDisplayName());
                }
                return identifiers.stream();
            })
            .map(String::trim)
            .filter(identifier -> !identifier.isEmpty())
            .collect(Collectors.toSet());
    }

    /**
     * 分组的可识别标识集合。
     *
     * <p>成员的 {@code groupName} 存的是分组**资源名**（如 {@code member-group-xxxx}），
     * 但老数据里也可能残留显示名（如「高校联盟」），导入时两者都会做双向解析。
     * 因此这里同时收集资源名与显示名，避免把「分组存在但存法不同」误报成分组缺失。
     */

    static Map<String, Object> run(String check, List<Member> members,
        Set<String> groupNames, int limit) {
        return switch (check) {
            case "duplicate" -> duplicateAudit(members, limit);
            case "contact" -> contactAudit(members, limit);
            case "school" -> fieldAudit(members, limit, "school");
            case "group" -> danglingGroupAudit(members, groupNames, limit);
            case "avatar" -> avatarAudit(members, limit);
            default -> statusAudit(members, limit);
        };
    }

    /**
     * 只保留各巡检项的数量，供概览快照复用同一套判定，又不携带样例明细。
     */
    static List<Map<String, Object>> counts(List<Member> members,
        List<MemberGroupVo> groups) {
        var groupNames = groupIdentifiers(groups);
        var summaries = new ArrayList<Map<String, Object>>();
        for (var check : CHECKS) {
            var report = run(check, members, groupNames, 0);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("check", check);
            summary.put("count", report.get("count"));
            summaries.add(summary);
        }
        return summaries;
    }

    /**
     * 只保留各巡检项的数量，供概览快照复用同一套判定，又不携带样例明细。
     */

    static Map<String, Object> duplicateAudit(List<Member> members, int limit) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var field : List.of("qq", "email")) {
            var byValue = members.stream()
                .filter(member -> {
                    var value = auditValue(member, field);
                    return value != null && !value.isBlank();
                })
                .collect(Collectors.groupingBy(
                    member -> auditValue(member, field).toLowerCase(),
                    LinkedHashMap::new,
                    Collectors.toList()));
            for (var entry : byValue.entrySet()) {
                if (entry.getValue().size() < 2) {
                    continue;
                }
                count += entry.getValue().size() - 1;
                if (items.size() < limit) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("field", field);
                    item.put("value", entry.getKey());
                    item.put("members", entry.getValue().stream()
                        .map(member -> member.getMetadata().getName())
                        .toList());
                    items.add(item);
                }
            }
        }
        return auditReport("duplicate", count, items, limit);
    }

    static Map<String, Object> contactAudit(List<Member> members, int limit) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var member : members) {
            var spec = member.getSpec();
            String detail = null;
            if (AbstractMemberMcpToolProvider.isBlank(spec.getQq())) {
                detail = "missing-qq";
            } else if (AbstractMemberMcpToolProvider.isBlank(spec.getQqFriendLink())) {
                detail = "missing-friend-link";
            }
            if (detail == null) {
                continue;
            }
            count++;
            if (items.size() < limit) {
                items.add(auditItem(member, detail));
            }
        }
        return auditReport("contact", count, items, limit);
    }

    static Map<String, Object> fieldAudit(List<Member> members, int limit, String field) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var member : members) {
            if (!AbstractMemberMcpToolProvider.isBlank(auditValue(member, field))) {
                continue;
            }
            count++;
            if (items.size() < limit) {
                items.add(auditItem(member, "missing-" + field));
            }
        }
        return auditReport(field, count, items, limit);
    }

    static Map<String, Object> danglingGroupAudit(List<Member> members, Set<String> groupNames,
        int limit) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var member : members) {
            var groupName = member.getSpec().getGroupName();
            if (AbstractMemberMcpToolProvider.isBlank(groupName) || groupNames.contains(groupName.trim())) {
                continue;
            }
            count++;
            if (items.size() < limit) {
                items.add(auditItem(member, "group-missing:" + groupName.trim()));
            }
        }
        return auditReport("group", count, items, limit);
    }

    static Map<String, Object> avatarAudit(List<Member> members, int limit) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var member : members) {
            var spec = member.getSpec();
            var avatar = spec.getAvatar();
            var expected = AvatarUtils.defaultQqAvatar(spec.getQq());
            String detail = null;
            if (AbstractMemberMcpToolProvider.isBlank(avatar)) {
                detail = AbstractMemberMcpToolProvider.isBlank(spec.getQq()) ? "missing-avatar" : "avatar-not-derived";
            } else if (expected != null && !expected.equals(avatar.trim())) {
                detail = "avatar-not-derived";
            }
            if (detail == null) {
                continue;
            }
            count++;
            if (items.size() < limit) {
                items.add(auditItem(member, detail));
            }
        }
        return auditReport("avatar", count, items, limit);
    }

    static Map<String, Object> statusAudit(List<Member> members, int limit) {
        var items = new ArrayList<Map<String, Object>>();
        long count = 0;
        for (var member : members) {
            var status = member.getSpec().getStatus();
            if (status != null && AbstractMemberMcpToolProvider.MEMBER_STATUSES.contains(status.trim())) {
                continue;
            }
            count++;
            if (items.size() < limit) {
                items.add(auditItem(member, "invalid-status:" + status));
            }
        }
        return auditReport("status", count, items, limit);
    }

    static Map<String, Object> auditReport(String check, long count,
        List<Map<String, Object>> items, int limit) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("check", check);
        report.put("count", count);
        report.put("truncated", count > items.size());
        report.put("items", items);
        if (count > limit) {
            report.put("hint", "Only the first " + limit
                + " item(s) are listed; raise 'limit' to see more.");
        }
        return report;
    }

    static Map<String, Object> auditItem(Member member, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", member.getMetadata().getName());
        item.put("displayName", member.getSpec().getDisplayName());
        item.put("status", member.getSpec().getStatus());
        item.put("detail", detail);
        return item;
    }

    static String auditValue(Member member, String field) {
        var spec = member.getSpec();
        if (spec == null) {
            return null;
        }
        return switch (field) {
            case "qq" -> spec.getQq();
            case "school" -> spec.getSchool();
            default -> spec.getEmail();
        };
    }

    static String duplicateValue(MemberVo member, String field) {
        var spec = member.getSpec();
        return switch (field) {
            case "qq" -> spec.getQq();
            case "school" -> spec.getSchool();
            default -> spec.getEmail();
        };
    }
}
