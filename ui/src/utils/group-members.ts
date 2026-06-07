import type { Member, MemberGroup } from "@/types";

export interface GroupWithMembers {
  group?: MemberGroup;
  members: Member[];
}

export function groupMembers(groups: MemberGroup[], members: Member[]) {
  const groupNames = groups.map((g) => g.metadata.name);

  const grouped: GroupWithMembers[] = groups.map((group) => ({
    group,
    members: members.filter((m) => m.spec?.groupName === group.metadata.name),
  }));

  const ungrouped: GroupWithMembers = {
    group: undefined,
    members: members.filter(
      (m) => !m.spec?.groupName || !groupNames.includes(m.spec.groupName)
    ),
  };

  // Ungrouped first, then grouped by priority
  return [ungrouped, ...grouped].filter((g) => g.members.length > 0);
}
