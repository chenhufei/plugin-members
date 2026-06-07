<script lang="ts" setup>
import MembersCard from "@/components/MembersCard.vue";
import { groupMembers, type GroupWithMembers } from "@/utils/group-members";
import {
  IconExternalLinkLine,
  IconRefreshLine,
  IconTeam,
  VButton,
  VLoading,
  VPageHeader,
  VSpace,
} from "@halo-dev/components";
import { useQuery } from "@tanstack/vue-query";
import { computed, defineAsyncComponent, ref, shallowRef } from "vue";
import { membersBatchApiClient, membersCoreApiClient } from "@/api";

import type { Member } from "@/types";

const GroupCreationModal = defineAsyncComponent(
  () => import("@/components/GroupCreationModal.vue"),
);
const MemberImportModal = defineAsyncComponent(
  () => import("@/components/MemberImportModal.vue"),
);

const handleRouteToFront = () => {
  window.open("/members", "_blank");
};

const groupCreationModalVisible = ref(false);
const memberImportModalVisible = ref(false);
const selectedStatusFilter = shallowRef<string>("all");
const selectedSortFilter = shallowRef<string>("all");

const statusFilterOptions = [
  { label: "全部", value: "all" },
  { label: "已通过", value: "APPROVED" },
  { label: "待审核", value: "PENDING" },
  { label: "已拒绝", value: "REJECTED" },
];

const sortFilterOptions = [
  { label: "默认排序", value: "all" },
  { label: "优先级 ↑", value: "priority-asc" },
  { label: "优先级 ↓", value: "priority-desc" },
  { label: "创建时间 ↓", value: "createdTime-desc" },
  { label: "创建时间 ↑", value: "createdTime-asc" },
];

const { data, isLoading, isFetching, refetch } = useQuery<GroupWithMembers[]>({
  queryKey: ["plugin:members:grouped-members", selectedStatusFilter, selectedSortFilter],
  queryFn: async () => {
    const { data: groupsData } = await membersCoreApiClient.memberGroup.list();
    const params: Record<string, unknown> = { page: 1, size: 10000 };
    if (selectedStatusFilter.value !== "all") params.status = selectedStatusFilter.value;
    if (selectedSortFilter.value !== "all") params.sort = selectedSortFilter.value;
    const { data: membersData } = await membersBatchApiClient.member.list(params);
    return groupMembers(groupsData.items || [], membersData.items || []);
  },
  refetchInterval: (data) => {
    const hasDeleting = data?.some((g) =>
      g.members.some((m: Member) => !!m.metadata.deletionTimestamp)
    ) || data?.some((g) => !!g.group?.metadata.deletionTimestamp);
    return hasDeleting ? 1000 : false;
  },
});

const filteredGroups = computed(() => data.value || []);
</script>
<template>
  <VPageHeader title="成员">
    <template #icon>
      <IconTeam />
    </template>
    <template #actions>
      <VButton @click="handleRouteToFront" size="sm" ghost>
        <template #icon>
          <IconExternalLinkLine />
        </template>
        跳转到前台
      </VButton>
    </template>
  </VPageHeader>
  <div class=":uno: p-4">
    <div
      class=":uno: mb-4 flex flex-col gap-3 border border-gray-200 rounded-lg bg-white/90 p-3 shadow-sm md:flex-row md:items-center md:justify-between"
    >
      <VSpace class=":uno: flex-wrap">
        <VButton size="sm" @click="groupCreationModalVisible = true">新建分组</VButton>
        <VButton size="sm" @click="memberImportModalVisible = true">批量导入</VButton>
      </VSpace>

      <div class=":uno: flex items-center gap-2">
        <FilterDropdown v-model="selectedStatusFilter" label="状态" :items="statusFilterOptions" />
        <FilterDropdown v-model="selectedSortFilter" label="排序" :items="sortFilterOptions" />

        <button
          v-tooltip="'刷新'"
          type="button"
          class=":uno: group cursor-pointer rounded p-1 hover:bg-gray-200"
          @click="refetch()"
        >
          <IconRefreshLine
            :class="{ ':uno: animate-spin text-gray-900': isFetching }"
            class=":uno: h-4 w-4 text-gray-600 group-hover:text-gray-900"
          />
        </button>
      </div>
    </div>

    <VLoading v-if="isLoading" />

    <div class=":uno: space-y-4" v-else-if="filteredGroups.length">
      <MembersCard
        v-for="groupWithMembers in filteredGroups"
        :group-with-members="groupWithMembers"
        :key="groupWithMembers.group?.metadata.name || 'ungrouped'"
      />
    </div>
    <div
      v-else
      class=":uno: border border-gray-200 rounded-lg border-dashed bg-white py-12 text-center text-sm text-gray-500"
    >
      暂无符合条件的成员
    </div>
  </div>

  <GroupCreationModal v-if="groupCreationModalVisible" @close="groupCreationModalVisible = false" />
  <MemberImportModal v-if="memberImportModalVisible" @close="memberImportModalVisible = false" />
</template>
