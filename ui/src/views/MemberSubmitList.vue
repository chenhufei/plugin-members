<script lang="ts" setup>
import { membersBatchApiClient, membersCoreApiClient } from "@/api";
import ListFilterSelect from "@/components/ListFilterSelect.vue";
import MemberEditingModal from "@/components/MemberEditingModal.vue";
import MemberReviewReasonModal from "@/components/MemberReviewReasonModal.vue";
import { useMemberGroupFetch } from "@/composables/use-group-fetch";
import { QK_MEMBERS } from "@/composables/use-member-fetch";
import type { Member } from "@/types";
import { formatDatetime } from "@/utils/date";
import {
  Dialog,
  IconRefreshLine,
  Toast,
  VAvatar,
  VButton,
  VDropdownDivider,
  VDropdownItem,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VPagination,
  VSpace,
  VStatusDot,
} from "@halo-dev/components";
import { useQuery, useQueryClient } from "@tanstack/vue-query";
import { computed, ref, watch } from "vue";

const queryClient = useQueryClient();
const selectedMembers = ref<string[]>([]);
const checkedAll = ref(false);
const editingMember = ref<Member>();
const reasonMember = ref<Member>();
const reasonAction = ref<"REJECT" | "OFFLINE">("REJECT");
const keyword = ref("");
const selectedStatus = ref<string | undefined>();
const selectedGroup = ref<string | undefined>();
const selectedSort = ref("createdTime-desc");
const page = ref(1);
const size = ref(20);

const sortOptions = [
  { label: "最新提交", value: "createdTime-desc" },
  { label: "最早提交", value: "createdTime-asc" },
  { label: "名称 A-Z", value: "name-asc" },
  { label: "名称 Z-A", value: "name-desc" },
];

const statusOptions = [
  { label: "全部状态", value: undefined },
  { label: "待审核", value: "PENDING" },
  { label: "已通过", value: "APPROVED" },
  { label: "已拒绝/下架", value: "REJECTED" },
];

const { groups } = useMemberGroupFetch();
const groupOptions = computed(() => [
  { label: "全部分组", value: undefined },
  ...(groups.value?.map((group) => ({
    label: group.spec.displayName,
    value: group.metadata.name,
  })) || []),
]);

watch(
  () => [selectedStatus.value, selectedGroup.value, keyword.value, selectedSort.value],
  () => {
    page.value = 1;
    selectedMembers.value = [];
    checkedAll.value = false;
  },
);

const hasFilters = computed(() => Boolean(
  selectedStatus.value
  || selectedGroup.value
  || selectedSort.value !== "createdTime-desc"
  || keyword.value.trim(),
));

function clearFilters() {
  selectedStatus.value = undefined;
  selectedGroup.value = undefined;
  selectedSort.value = "createdTime-desc";
  keyword.value = "";
}

function optionalQueryValue(value?: string) {
  const normalized = value?.trim();
  return normalized || undefined;
}

const {
  data: membersData,
  isLoading,
  isFetching,
  refetch,
} = useQuery({
  queryKey: ["member-submits", page, size, selectedStatus, selectedGroup, keyword, selectedSort],
  queryFn: async () => {
    const { data } = await membersBatchApiClient.member.list({
      page: page.value,
      size: size.value,
      status: optionalQueryValue(selectedStatus.value),
      groupName: optionalQueryValue(selectedGroup.value),
      keyword: optionalQueryValue(keyword.value),
      sort: selectedSort.value,
    });
    return data;
  },
  refetchInterval: (data) => data?.items?.some((member: Member) => member.metadata.deletionTimestamp)
    ? 1000
    : false,
});

const members = computed<Member[]>(() => membersData.value?.items || []);
const total = computed(() => membersData.value?.total || 0);

watch(selectedMembers, (value) => {
  checkedAll.value = Boolean(members.value.length) && value.length === members.value.length;
});

watch(members, (items) => {
  const visibleNames = new Set(items.map((member) => member.metadata.name));
  selectedMembers.value = selectedMembers.value.filter((name) => visibleNames.has(name));
});

function getGroupName(name: string) {
  return groups.value?.find((group) => group.metadata.name === name)?.spec.displayName || "未分组";
}

function statusText(member: Member) {
  const action = member.metadata.annotations?.["member.plugin.halo.run/review-action"];
  if (member.spec.status === "REJECTED" && action === "OFFLINE") return "已下架";
  if (member.spec.status === "REJECTED" && action === "WITHDRAW") return "已撤回";
  return {
    PENDING: "待审核",
    APPROVED: "已通过",
    REJECTED: "已拒绝",
  }[member.spec.status] || "未知";
}

function statusState(status: string) {
  return ({
    PENDING: "warning",
    APPROVED: "success",
    REJECTED: "error",
  } as Record<string, "warning" | "success" | "error" | "default">)[status] || "default";
}

async function invalidateMemberQueries() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ["member-submits"] }),
    queryClient.invalidateQueries({ queryKey: [QK_MEMBERS] }),
    queryClient.invalidateQueries({ queryKey: ["plugin:members:withdraw-requests"] }),
  ]);
}

async function approve(member: Member) {
  try {
    await membersCoreApiClient.member.patch(member.metadata.name, [
      { op: "add", path: "/spec/status", value: "APPROVED" },
      {
        op: "add",
        path: "/metadata/annotations",
        value: {
          ...(member.metadata.annotations || {}),
          "member.plugin.halo.run/review-description": "",
          "member.plugin.halo.run/review-action": "APPROVE",
          "member.plugin.halo.run/review-notification": "pending",
        },
      },
    ]);
    Toast.success("已通过申请");
    await invalidateMemberQueries();
  } catch (error) {
    console.error("Failed to approve member", error);
    Toast.error("操作失败");
  }
}

function requestReason(member: Member, action: "REJECT" | "OFFLINE") {
  reasonMember.value = member;
  reasonAction.value = action;
}

function handleCheckAllChange(event: Event) {
  const checked = (event.target as HTMLInputElement).checked;
  checkedAll.value = checked;
  selectedMembers.value = checked ? members.value.map((member) => member.metadata.name) : [];
}

function approveInBatch() {
  Dialog.warning({
    title: "批量通过申请？",
    description: `将通过 ${selectedMembers.value.length} 个成员申请。`,
    confirmType: "secondary",
    onConfirm: async () => {
      const { data } = await membersBatchApiClient.member.batchApprove([...selectedMembers.value], true);
      data.failed ? Toast.warning(data.message) : Toast.success("批量通过完成");
      selectedMembers.value = [];
      checkedAll.value = false;
      await invalidateMemberQueries();
    },
  });
}

function deleteInBatch() {
  Dialog.warning({
    title: "删除所选记录？",
    description: "删除后无法恢复。拒绝或下架请使用卡片上的对应按钮，以便填写并发送原因。",
    confirmType: "danger",
    onConfirm: async () => {
      const { data } = await membersBatchApiClient.member.batchDelete([...selectedMembers.value]);
      data.failed ? Toast.warning(data.message) : Toast.success("删除完成");
      selectedMembers.value = [];
      checkedAll.value = false;
      await invalidateMemberQueries();
    },
  });
}

function deleteMember(member: Member) {
  Dialog.warning({
    title: "删除成员记录？",
    description: "此操作不可恢复。",
    confirmType: "danger",
    onConfirm: async () => {
      await membersCoreApiClient.member.delete(member.metadata.name);
      Toast.success("删除成功");
      await invalidateMemberQueries();
    },
  });
}
</script>

<template>
  <MemberEditingModal
    v-if="editingMember"
    :member="editingMember"
    :show-status="false"
    @close="editingMember = undefined"
  />
  <MemberReviewReasonModal
    v-if="reasonMember"
    :member="reasonMember"
    :action="reasonAction"
    @close="reasonMember = undefined"
    @success="reasonMember = undefined"
  />

  <div>
    <div class=":uno: flex flex-col gap-3 border-b border-gray-100 bg-gray-50 px-4 py-3 md:flex-row md:items-center md:justify-between">
      <VSpace class=":uno: min-w-0 flex-1 flex-wrap">
        <input
          v-model="checkedAll"
          v-permission="['plugin:members:manage']"
          type="checkbox"
          aria-label="选择当前页全部成员"
          @change="handleCheckAllChange"
        />
        <template v-if="selectedMembers.length" v-permission="['plugin:members:manage']">
          <VButton size="sm" type="secondary" @click="approveInBatch">批量通过</VButton>
          <VButton size="sm" type="danger" @click="deleteInBatch">删除</VButton>
        </template>
        <SearchInput
          v-else
          v-model="keyword"
          placeholder="搜索名称、学校、邮箱或 QQ"
          class=":uno: w-full md:w-72"
        />
      </VSpace>

      <div class=":uno: flex flex-wrap items-center gap-2">
        <FilterCleanButton v-if="hasFilters" @click="clearFilters" />
        <ListFilterSelect v-model="selectedStatus" label="状态" :items="statusOptions" />
        <ListFilterSelect v-model="selectedGroup" label="分组" :items="groupOptions" />
        <ListFilterSelect v-model="selectedSort" label="排序" :items="sortOptions" />
        <VButton v-tooltip="'刷新'" size="sm" ghost @click="refetch()">
          <template #icon>
            <IconRefreshLine :class="{ ':uno: animate-spin': isFetching }" class=":uno: h-4 w-4" />
          </template>
        </VButton>
      </div>
    </div>

    <div v-if="isLoading" class=":uno: py-16"><VLoading /></div>
    <VEmpty
      v-else-if="!members.length"
      title="当前没有成员申请"
      message="请调整筛选条件或稍后刷新"
    />

    <VEntityContainer v-else>
      <VEntity
        v-for="member in members"
        :key="member.metadata.name"
        :is-selected="selectedMembers.includes(member.metadata.name)"
      >
        <template #checkbox>
          <input
            v-model="selectedMembers"
            :value="member.metadata.name"
            :disabled="Boolean(member.metadata.deletionTimestamp)"
            type="checkbox"
          />
        </template>
        <template #start>
          <VEntityField>
            <template #description>
              <VAvatar :alt="member.spec.displayName" :src="member.spec.avatar" size="md" />
            </template>
          </VEntityField>
          <VEntityField
            :title="member.spec.displayName"
            :description="member.spec.school || member.spec.email || '未填写学校'"
            class=":uno: min-w-0 max-w-[32rem] cursor-pointer"
            @click="editingMember = member"
          />
        </template>

        <template #end>
          <VEntityField>
            <template #description>
              <VStatusDot :state="statusState(member.spec.status)" :text="statusText(member)" />
            </template>
          </VEntityField>
          <VEntityField>
            <template #description>
              <span class=":uno: block max-w-[9rem] truncate text-sm text-gray-500">
                {{ getGroupName(member.spec.groupName || "") }}
              </span>
            </template>
          </VEntityField>
          <VEntityField v-if="member.metadata.creationTimestamp">
            <template #description>
              <span class=":uno: whitespace-nowrap text-sm text-gray-500">
                {{ formatDatetime(member.metadata.creationTimestamp) }}
              </span>
            </template>
          </VEntityField>
          <VEntityField class=":uno: min-w-[138px]">
            <template #description>
              <VSpace spacing="xs" class=":uno: justify-end">
                <template v-if="member.spec.status === 'PENDING'">
                  <VButton size="sm" type="secondary" @click="approve(member)">通过</VButton>
                  <VButton size="sm" type="danger" ghost @click="requestReason(member, 'REJECT')">拒绝</VButton>
                </template>
                <VButton
                  v-else-if="member.spec.status === 'APPROVED'"
                  size="sm"
                  type="danger"
                  ghost
                  @click="requestReason(member, 'OFFLINE')"
                >下架</VButton>
                <VButton v-else size="sm" type="secondary" @click="approve(member)">重新通过</VButton>
              </VSpace>
            </template>
          </VEntityField>
        </template>

        <template #dropdownItems>
          <VDropdownItem @click="editingMember = member">编辑成员</VDropdownItem>
          <VDropdownDivider />
          <VDropdownItem type="danger" @click="deleteMember(member)">删除</VDropdownItem>
        </template>
      </VEntity>
    </VEntityContainer>

    <div v-if="total > 0" class=":uno: border-t border-gray-100 px-4 py-3">
      <VPagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        :size-options="[20, 30, 50, 100]"
      />
    </div>
  </div>
</template>
