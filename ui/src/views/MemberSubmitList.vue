<script lang="ts" setup>
import { membersBatchApiClient, membersCoreApiClient } from "@/api";
import MemberCheckModal from "@/components/MemberCheckModal.vue";
import { useMemberGroupFetch } from "@/composables/use-group-fetch";
import { QK_MEMBERS } from "@/composables/use-member-fetch";
import type { Member } from "@/types";
import { formatDatetime } from "@/utils/date";
import {
  Dialog,
  IconExternalLinkLine,
  IconRefreshLine,
  Toast,
  VAvatar,
  VButton,
  VCard,
  VEntity,
  VEntityField,
  VLoading,
  VPagination,
  VPageHeader,
  VSpace,
  VStatusDot,
  VTag,
} from "@halo-dev/components";
import { useQuery, useQueryClient } from "@tanstack/vue-query";
import { computed, ref, watch } from "vue";

const queryClient = useQueryClient();
const selectedMembers = ref<string[]>([]);
const checkedAll = ref(false);
const checkingMember = ref<Member>();
const checkModal = ref(false);
const initialCheckStatus = ref<"APPROVED" | "REJECTED">("APPROVED");
const keyword = ref("");
const selectedStatus = ref<string | undefined>();
const selectedGroup = ref<string | undefined>();
const selectedSort = ref("createdTime-desc");
const page = ref(1);
const size = ref(20);

const sortOptions = [
  { label: "提交时间 ↓", value: "createdTime-desc" },
  { label: "提交时间 ↑", value: "createdTime-asc" },
  { label: "账号名称 A-Z", value: "name-asc" },
  { label: "账号名称 Z-A", value: "name-desc" },
];

const statusOptions = [
  { label: "全部", value: undefined },
  { label: "待审核", value: "PENDING" },
  { label: "已通过", value: "APPROVED" },
  { label: "已拒绝", value: "REJECTED" },
];

const { groups } = useMemberGroupFetch();
const groupOptions = computed(() => [
  { label: "全部分组", value: undefined },
  ...(groups.value?.map((g) => ({ label: g.spec.displayName, value: g.metadata.name })) || []),
]);

watch(
  () => [selectedStatus.value, selectedGroup.value, keyword.value, selectedSort.value],
  () => {
    page.value = 1;
    selectedMembers.value.length = 0;
    checkedAll.value = false;
  }
);

const hasFilters = computed(
  () => selectedStatus.value || selectedGroup.value || selectedSort.value !== "createdTime-desc" || keyword.value.trim() !== ""
);

function handleClearFilters() {
  selectedStatus.value = undefined;
  selectedGroup.value = undefined;
  selectedSort.value = "createdTime-desc";
  keyword.value = "";
}

function optionalQueryValue(v?: string) {
  const t = v?.trim();
  return t ? t : undefined;
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
      sort: selectedSort.value || "createdTime-desc",
    });
    return data;
  },
  refetchInterval: (data) => {
    const d = data?.items?.filter((m: Member) => !!m.metadata.deletionTimestamp);
    return d?.length ? 1000 : false;
  },
});

const members = computed(() => membersData.value?.items || []);
const total = computed(() => membersData.value?.total || 0);

watch(selectedMembers, (v) => {
  checkedAll.value = Boolean(members.value?.length) && v.length === members.value?.length;
});
watch(members, (m) => {
  const s = new Set(m.map((x: Member) => x.metadata.name));
  selectedMembers.value = selectedMembers.value.filter((n) => s.has(n));
});

function getGroupName(n: string) {
  return groups.value?.find((g) => g.metadata.name === n)?.spec?.displayName || "未分组";
}

function handleOpenCheckModal(member: Member, status: "APPROVED" | "REJECTED") {
  checkingMember.value = member;
  initialCheckStatus.value = status;
  checkModal.value = true;
}
function handleCheckModalClose() {
  checkingMember.value = undefined;
  checkModal.value = false;
  queryClient.invalidateQueries({ queryKey: ["member-submits"] });
  queryClient.invalidateQueries({ queryKey: [QK_MEMBERS] });
}

const handleCheckAllChange = (e: Event) => {
  const { checked } = e.target as HTMLInputElement;
  checkedAll.value = checked;
  if (checked) selectedMembers.value = members.value?.map((m: Member) => m.metadata.name) || [];
  else selectedMembers.value.length = 0;
};

const handleDeleteInBatch = () => {
  Dialog.warning({
    title: "是否确认删除所选的成员？",
    description: "删除之后将无法恢复。",
    confirmType: "danger",
    onConfirm: async () => {
      const { data } = await membersBatchApiClient.member.batchDelete([...selectedMembers.value]);
      selectedMembers.value.length = 0;
      checkedAll.value = false;
      data.failed ? Toast.warning(data.message) : Toast.success("删除成功");
      queryClient.invalidateQueries({ queryKey: ["member-submits"] });
    },
  });
};
const handleApproveInBatch = () => {
  Dialog.warning({
    title: "是否确认批量通过审核？",
    description: `将通过 ${selectedMembers.value.length} 个成员的申请。`,
    confirmType: "secondary",
    onConfirm: async () => {
      const { data } = await membersBatchApiClient.member.batchApprove([...selectedMembers.value], true);
      selectedMembers.value.length = 0;
      checkedAll.value = false;
      data.failed ? Toast.warning(data.message) : Toast.success("批量审核通过");
      queryClient.invalidateQueries({ queryKey: ["member-submits"] });
    },
  });
};
const handleRejectInBatch = () => {
  Dialog.warning({
    title: "是否确认批量拒绝？",
    description: `将拒绝 ${selectedMembers.value.length} 个成员的申请。`,
    confirmType: "danger",
    onConfirm: async () => {
      const { data } = await membersBatchApiClient.member.batchApprove([...selectedMembers.value], false);
      selectedMembers.value.length = 0;
      checkedAll.value = false;
      data.failed ? Toast.warning(data.message) : Toast.success("批量拒绝成功");
      queryClient.invalidateQueries({ queryKey: ["member-submits"] });
    },
  });
};
const handleDelete = (member: Member) => {
  Dialog.warning({
    title: "确定删除吗？",
    description: "此操作不可逆。",
    confirmType: "danger",
    async onConfirm() {
      await membersCoreApiClient.member.delete(member.metadata.name);
      Toast.success("删除成功");
      queryClient.invalidateQueries({ queryKey: ["member-submits"] });
    },
  });
};

function statusText(s: string) {
  return { PENDING: "待审核", APPROVED: "已通过", REJECTED: "已拒绝" }[s] || "未知";
}
function statusType(s: string) {
  return ({ PENDING: "warning", APPROVED: "success", REJECTED: "error" } as Record<string, string>)[s] || "default";
}

async function handleRevert(member: Member) {
  await membersCoreApiClient.member.patch(member.metadata.name, [{ op: "add", path: "/spec/status", value: "PENDING" }]);
  Toast.success("已恢复为待审核");
  refetch();
}
</script>

<template>
  <MemberCheckModal
    v-if="checkModal && checkingMember"
    :member="checkingMember"
    :initial-status="initialCheckStatus"
    @close="handleCheckModalClose"
  />

  <VPageHeader title="申请记录">
    <template #actions>
      <VButton size="sm" ghost>
        <template #icon><IconExternalLinkLine /></template>
        跳转到前台
      </VButton>
    </template>
  </VPageHeader>

  <div class=":uno: p-4">
    <!-- 工具栏 — 与链接管理完全一致的样式 -->
    <div
      class=":uno: mb-4 flex flex-col gap-3 border border-gray-200 rounded-lg bg-white/90 p-3 shadow-sm md:flex-row md:items-center md:justify-between"
    >
      <VSpace class=":uno: flex-wrap">
        <div v-permission="['plugin:members:manage']" class=":uno: hidden items-center md:flex">
          <input v-model="checkedAll" type="checkbox" @change="handleCheckAllChange" class=":uno: mr-2" />
        </div>
        <template v-if="selectedMembers.length" v-permission="['plugin:members:manage']">
          <VButton size="sm" type="secondary" @click="handleApproveInBatch">批量同意</VButton>
          <VButton size="sm" @click="handleRejectInBatch">批量拒绝</VButton>
          <VButton size="sm" type="danger" @click="handleDeleteInBatch">删除</VButton>
        </template>
        <input
          v-else
          v-model="keyword"
          type="text"
          placeholder="搜索成员申请..."
          class=":uno: w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none md:w-64"
        />
      </VSpace>

      <div class=":uno: flex items-center gap-2">
        <FilterCleanButton v-if="hasFilters" @click="handleClearFilters" />
        <FilterDropdown v-model="selectedStatus" label="状态" :items="statusOptions" />
        <FilterDropdown v-model="selectedGroup" label="分组" :items="groupOptions" />
        <FilterDropdown v-model="selectedSort" label="排序" :items="sortOptions" />
        <button v-tooltip="'刷新'" type="button" class=":uno: group cursor-pointer rounded p-1 hover:bg-gray-200" @click="refetch()">
          <IconRefreshLine
            :class="{ ':uno: animate-spin text-gray-900': isFetching }"
            class=":uno: h-4 w-4 text-gray-600 group-hover:text-gray-900"
          />
        </button>
      </div>
    </div>

    <!-- 内容区 -->
    <VLoading v-if="isLoading" />

    <div
      v-else-if="!members?.length"
      class=":uno: border border-gray-200 rounded-lg border-dashed bg-white py-12 text-center text-sm text-gray-500"
    >
      当前没有成员申请
    </div>

    <VCard v-else :body-class="[':uno: !p-0']">
      <div class=":uno: w-full overflow-x-auto">
        <div class=":uno: min-w-[900px]">
          <table class=":uno: w-full border-spacing-0">
            <tbody class=":uno: divide-y divide-gray-100">
              <VEntity
                v-for="member in members"
                :key="member.metadata.name"
                :is-selected="selectedMembers.includes(member.metadata.name)"
              >
                <template #checkbox>
                  <input
                    v-model="selectedMembers"
                    :value="member.metadata.name"
                    :disabled="!!member.metadata.deletionTimestamp"
                    type="checkbox"
                  />
                </template>
                <template #start>
                  <VEntityField class=":uno: min-w-[60px]">
                    <template #description>
                      <VAvatar :alt="member.spec.displayName" :src="member.spec.avatar" size="md" />
                    </template>
                  </VEntityField>
                  <VEntityField :title="member.spec.displayName" :description="member.spec.school" class=":uno: min-w-[200px]">
                    <template #extra>
                      <VSpace>
                        <VTag size="sm" v-if="member.spec.qq">QQ: {{ member.spec.qq }}</VTag>
                        <VTag :type="statusType(member.spec.status)">{{ statusText(member.spec.status) }}</VTag>
                      </VSpace>
                    </template>
                  </VEntityField>
                </template>
                <template #end>
                  <VEntityField v-if="member.spec.email" class=":uno: min-w-[180px]">
                    <template #description>
                      <a
                        :href="`mailto:${member.spec.email}`"
                        class=":uno: text-xs text-gray-500 hover:text-gray-900 truncate block"
                        :title="member.spec.email"
                      >
                        {{ member.spec.email }}
                      </a>
                    </template>
                  </VEntityField>
                  <VEntityField class=":uno: min-w-[100px]">
                    <template #description>
                      <span class=":uno: text-xs text-gray-500 truncate block">{{ getGroupName(member.spec.groupName || "") }}</span>
                    </template>
                  </VEntityField>
                  <VEntityField v-if="member.metadata.creationTimestamp" class=":uno: min-w-[120px]">
                    <template #description>
                      <span class=":uno: truncate text-xs tabular-nums text-gray-500 block">
                        {{ formatDatetime(member.metadata.creationTimestamp) }}
                      </span>
                    </template>
                  </VEntityField>
                  <VEntityField v-if="member.metadata.deletionTimestamp" class=":uno: min-w-[80px]">
                    <template #description>
                      <VStatusDot v-tooltip="'删除中'" state="warning" animate />
                    </template>
                  </VEntityField>
                  <VEntityField
                    v-if="!member.metadata.deletionTimestamp"
                    v-permission="['plugin:members:manage']"
                    class=":uno: min-w-[200px]"
                  >
                    <template #description>
                      <VSpace spacing="xs" class=":uno: flex-wrap">
                        <VButton
                          v-if="member.spec.status === 'PENDING'"
                          size="sm"
                          type="secondary"
                          @click="handleOpenCheckModal(member, 'APPROVED')"
                        >同意</VButton>
                        <VButton
                          v-if="member.spec.status === 'PENDING'"
                          size="sm"
                          type="danger"
                          @click="handleOpenCheckModal(member, 'REJECTED')"
                        >拒绝</VButton>
                        <VButton
                          v-if="member.spec.status === 'APPROVED'"
                          size="sm"
                          type="default"
                          @click="handleRevert(member)"
                        >撤回</VButton>
                        <VButton
                          v-if="member.spec.status === 'REJECTED'"
                          size="sm"
                          type="default"
                          @click="handleRevert(member)"
                        >重新审核</VButton>
                        <VButton size="sm" type="danger" @click="handleDelete(member)">删除</VButton>
                      </VSpace>
                    </template>
                  </VEntityField>
                </template>
              </VEntity>
            </tbody>
          </table>
        </div>
      </div>

      <template #footer>
        <VPagination v-model:page="page" v-model:size="size" :total="total" :size-options="[20, 30, 50, 100]" />
      </template>
    </VCard>
  </div>
</template>
