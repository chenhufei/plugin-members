<script lang="ts" setup>
import {
  Dialog,
  IconRefreshLine,
  Toast,
  VAvatar,
  VButton,
  VCard,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VSpace,
  VStatusDot,
} from "@halo-dev/components";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import { ref } from "vue";
import { approveWithdraw, getWithdrawRequests, rejectWithdraw } from "@/api";
import { formatDatetime } from "@/utils/date";

interface WithdrawRecord {
  metadata: {
    name: string;
    creationTimestamp?: string;
  };
  spec: {
    avatar?: string;
    displayName: string;
    email?: string;
    qq?: string;
    groupName?: string;
    status: string;
  };
  recordType: "SELF_WITHDRAW" | "ADMIN_OFFLINE";
  recordStatus: "PENDING" | "APPROVED" | "REJECTED" | "OFFLINE" | "RESTORED";
  reason?: string;
  email?: string;
  statusBefore?: string;
  recordedAt?: string;
}

interface WithdrawRecordList {
  items: WithdrawRecord[];
  total: number;
}

const queryClient = useQueryClient();
const approvingName = ref<string>();
const rejectingName = ref<string>();

const { data, isLoading, isFetching, refetch } = useQuery<WithdrawRecordList>({
  queryKey: ["plugin:members:withdraw-requests"],
  queryFn: async () => {
    const { data } = await getWithdrawRequests();
    return data;
  },
  refetchInterval: 30000,
});

async function invalidateMemberQueries() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ["plugin:members:withdraw-requests"] }),
    queryClient.invalidateQueries({ queryKey: ["plugin:members:members"] }),
    queryClient.invalidateQueries({ queryKey: ["member-submits"] }),
  ]);
}

const approveMutation = useMutation({
  mutationFn: (name: string) => approveWithdraw(name),
  onSuccess: async () => {
    Toast.success("撤回申请已通过，成员已下架");
    await invalidateMemberQueries();
  },
  onError: () => Toast.error("操作失败，请刷新后重试"),
});

const rejectMutation = useMutation({
  mutationFn: (name: string) => rejectWithdraw(name),
  onSuccess: async () => {
    Toast.success("撤回申请已拒绝，成员状态已恢复");
    await invalidateMemberQueries();
  },
  onError: () => Toast.error("操作失败，请刷新后重试"),
});

function handleApprove(record: WithdrawRecord) {
  Dialog.warning({
    title: "批准撤回申请？",
    description: "批准后该成员将从公示页面下架，撤回原因和处理结果会保留在此记录中。",
    confirmType: "danger",
    onConfirm: async () => {
      approvingName.value = record.metadata.name;
      try {
        await approveMutation.mutateAsync(record.metadata.name);
      } finally {
        approvingName.value = undefined;
      }
    },
  });
}

function handleReject(record: WithdrawRecord) {
  Dialog.warning({
    title: "拒绝撤回申请？",
    description: "拒绝后成员会恢复为撤回前状态，申请人将收到处理结果邮件。",
    onConfirm: async () => {
      rejectingName.value = record.metadata.name;
      try {
        await rejectMutation.mutateAsync(record.metadata.name);
      } finally {
        rejectingName.value = undefined;
      }
    },
  });
}

function recordSource(record: WithdrawRecord) {
  return record.recordType === "SELF_WITHDRAW" ? "成员自助撤回" : "管理员下架";
}

function recordStatus(record: WithdrawRecord) {
  return {
    PENDING: { state: "warning" as const, text: "待处理" },
    APPROVED: { state: "success" as const, text: "已通过" },
    REJECTED: { state: "error" as const, text: "已拒绝" },
    OFFLINE: { state: "error" as const, text: "已下架" },
    RESTORED: { state: "success" as const, text: "已重新上架" },
  }[record.recordStatus] || { state: "default" as const, text: "未知" };
}
</script>

<template>
  <VCard :body-class="[':uno: !p-0']">
    <template #header>
      <div class=":uno: flex w-full items-center justify-between bg-gray-50 px-4 py-3">
        <div class=":uno: min-w-0">
          <div class=":uno: text-sm text-gray-900 font-medium">撤回与下架记录</div>
          <div class=":uno: mt-0.5 text-xs text-gray-500">
            统一查看待处理撤回、处理结果和管理员下架记录
          </div>
        </div>
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
    </template>

    <div v-if="isLoading" class=":uno: py-16"><VLoading /></div>
    <VEmpty
      v-else-if="!data?.items?.length"
      title="暂无撤回或下架记录"
      message="成员提交撤回申请或管理员下架成员后会显示在这里"
    />

    <VEntityContainer v-else>
      <VEntity v-for="record in data.items" :key="`${record.metadata.name}-${record.recordedAt}`">
        <template #start>
          <VEntityField>
            <template #description>
              <VAvatar :alt="record.spec.displayName" :src="record.spec.avatar" size="md" />
            </template>
          </VEntityField>
          <VEntityField
            :title="record.spec.displayName"
            :description="record.email || record.spec.email || (record.spec.qq ? `QQ ${record.spec.qq}` : '未填写联系方式')"
            class=":uno: min-w-0 max-w-[28rem]"
          />
        </template>

        <template #end>
          <VEntityField>
            <template #description>
              <span class=":uno: whitespace-nowrap text-sm text-gray-500">{{ recordSource(record) }}</span>
            </template>
          </VEntityField>
          <VEntityField>
            <template #description>
              <VStatusDot :state="recordStatus(record).state" :text="recordStatus(record).text" />
            </template>
          </VEntityField>
          <VEntityField v-if="record.reason" class=":uno: min-w-0 max-w-[18rem]">
            <template #description>
              <span class=":uno: block truncate text-sm text-gray-500" v-tooltip="record.reason">
                {{ record.reason }}
              </span>
            </template>
          </VEntityField>
          <VEntityField v-if="record.recordedAt">
            <template #description>
              <span class=":uno: whitespace-nowrap text-sm text-gray-500">
                {{ formatDatetime(record.recordedAt) }}
              </span>
            </template>
          </VEntityField>
          <VEntityField v-if="record.recordStatus === 'PENDING'" class=":uno: min-w-[132px]">
            <template #description>
              <VSpace spacing="xs" class=":uno: justify-end">
                <VButton
                  size="sm"
                  type="secondary"
                  :loading="approvingName === record.metadata.name"
                  @click="handleApprove(record)"
                >批准</VButton>
                <VButton
                  size="sm"
                  type="danger"
                  ghost
                  :loading="rejectingName === record.metadata.name"
                  @click="handleReject(record)"
                >拒绝</VButton>
              </VSpace>
            </template>
          </VEntityField>
        </template>
      </VEntity>
    </VEntityContainer>
  </VCard>
</template>
