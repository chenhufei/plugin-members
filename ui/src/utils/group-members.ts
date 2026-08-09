import type { Member, MemberGroup } from "@/types";

export interface GroupWithMembers {
  group?: MemberGroup;
  members: Member[];
}

export function groupMembers(groups: MemberGroup[], members: Member[]) {
  const orderedGroups = [...groups].sort((a, b) => {
    const nameCompare = (a.spec?.displayName || "").localeCompare(
      b.spec?.displayName || "",
      "zh-Hans",
    );
    return nameCompare || a.metadata.name.localeCompare(b.metadata.name);
  });
  const groupNames = orderedGroups.map((g) => g.metadata.name);

  const grouped: GroupWithMembers[] = orderedGroups.map((group) => ({
    group,
    members: members.filter((m) => m.spec?.groupName === group.metadata.name),
  }));

  const ungrouped: GroupWithMembers = {
    group: undefined,
    members: members.filter(
      (m) => !m.spec?.groupName || !groupNames.includes(m.spec.groupName)
    ),
  };

  // Keep ungrouped members first, followed by stable display-name order.
  return [ungrouped, ...grouped].filter((g) => g.members.length > 0);
}
