import { membersBatchApiClient } from "@/api";
import type { Member } from "@/types";
import { useQuery } from "@tanstack/vue-query";
import { computed, type Ref } from "vue";

export const QK_MEMBERS = "plugin:members:members";

export interface MemberQueryState {
  page: Ref<number>;
  size: Ref<number>;
  keyword: Ref<string>;
  sort: Ref<string>;
  groupName: Ref<string>;
  status: Ref<string>;
}

export function useMemberFetch(queryState: MemberQueryState) {
  const { data, isLoading, isFetching, refetch } = useQuery({
    queryKey: [
      QK_MEMBERS,
      queryState.page,
      queryState.size,
      queryState.keyword,
      queryState.sort,
      queryState.groupName,
      queryState.status,
    ],
    queryFn: async () => {
      const { data } = await membersBatchApiClient.member.list({
        page: queryState.page.value,
        size: queryState.size.value,
        keyword: optionalQueryValue(queryState.keyword.value),
        groupName: optionalQueryValue(queryState.groupName.value),
        status: optionalQueryValue(queryState.status.value),
        sort: queryState.sort.value || "priority-desc",
      });
      return data;
    },
    refetchInterval: (data) => {
      const hasDeletingMember = data?.items?.some(
        (member: Member) => !!member.metadata.deletionTimestamp
      );
      return hasDeletingMember ? 1000 : false;
    },
  });

  const members = computed(() => data.value?.items || []);
  const total = computed(() => data.value?.total || 0);

  return {
    data,
    members,
    total,
    isLoading,
    isFetching,
    refetch,
  };
}

function optionalQueryValue(value?: string) {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}
