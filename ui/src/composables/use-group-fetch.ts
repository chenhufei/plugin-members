import { membersCoreApiClient } from "@/api";
import type { MemberGroup } from "@/types";
import { useQuery } from "@tanstack/vue-query";
import { computed } from "vue";

export const QK_MEMBER_GROUPS = "plugin:members:member-groups";

export function useMemberGroupFetch() {
  const { data, isLoading, refetch } = useQuery({
    queryKey: [QK_MEMBER_GROUPS],
    queryFn: async () => {
      const { data } = await membersCoreApiClient.memberGroup.list();
      return data;
    },
    refetchInterval(data) {
      const hasDeletingData = data?.items?.some(
        (group: MemberGroup) => !!group.metadata.deletionTimestamp
      );
      return hasDeletingData ? 1000 : false;
    },
  });

  const groups = computed(() => {
    const items = data.value?.items || [];
    return [...items].sort((a: MemberGroup, b: MemberGroup) => {
      return (a.spec?.priority || 0) - (b.spec?.priority || 0);
    });
  });

  return {
    groups,
    isLoading,
    refetch,
  };
}
