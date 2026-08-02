<script lang="ts" setup>
import {
  Dialog,
  IconRefreshLine,
  Toast,
  VButton,
  VCard,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VLoading,
  VPageHeader,
  VSpace,
} from "@halo-dev/components";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import { ref } from "vue";
import {
  getWithdrawRequests,
  approveWithdraw,
  rejectWithdraw,
} from "@/api";
import { formatDatetime } from "@/utils/date";

interface WithdrawMember {
  metadata: {
    name: string;
    creationTimestamp: string;
    annotations?: Record<string, string>;
  };
  spec: {
    displayName: string;
    email: string;
    qq: string;
    groupName: string;
    status: string;
  };
}

const queryClient = useQueryClient();
const approvingName = ref<string | null>(null);
const rejectingName = ref<string | null>(null);

const { data, isLoading, refetch } = useQuery<{ items: WithdrawMember[] }>({
  queryKey: ["plugin:members:withdraw-requests"],
  queryFn: async () => {
    const { data } = await getWithdrawRequests();
    return data;
  },
  refetchInterval: 30000, // 每30秒刷新
});

// 手动刷新
const handleRefresh = async () => {
  await refetch();
};

// 批准撤回
const approveMutation = useMutation({
  mutationFn: (name: string) => approveWithdraw(name),
  onSuccess: () => {
    Toast.success("撤回申请已通过");
    queryClient.invalidateQueries({ queryKey: ["plugin:members:withdraw-requests"] });
    queryClient.invalidateQueries({ queryKey: ["plugin:members:members"] });
  },
  onError: () => {
    Toast.error("操作失败");
  },
});

// 拒绝撤回
const rejectMutation = useMutation({
  mutationFn: (name: string) => rejectWithdraw(name),
  onSuccess: () => {
    Toast.success("撤回申请已拒绝");
    queryClient.invalidateQueries({ queryKey: ["plugin:members:withdraw-requests"] });
    queryClient.invalidateQueries({ queryKey: ["plugin:members:members"] });
  },
  onError: () => {
    Toast.error("操作失败");
  },
});

const handleApprove = (member: WithdrawMember) => {
  Dialog.warning({
    title: "确认批准",
    description: "批准后该成员的撤回申请将通过，申请将恢复为待审核状态。是否继续？",
    onConfirm: async () => {
      approvingName.value = member.metadata.name;
      try {
        await approveMutation.mutateAsync(member.metadata.name);
      } finally {
        approvingName.value = null;
      }
    },
  });
};

const handleReject = (member: WithdrawMember) => {
  Dialog.warning({
    title: "确认拒绝",
    description: "拒绝后该成员的撤回申请将被取消，成员状态将恢复为撤回前的状态。是否继续？",
    onConfirm: async () => {
      rejectingName.value = member.metadata.name;
      try {
        await rejectMutation.mutateAsync(member.metadata.name);
      } finally {
        rejectingName.value = null;
      }
    },
  });
};

</script>

<template>
  <VPageHeader title="撤回审核">
    <template #actions>
      <VButton v-tooltip="'刷新'" size="sm" :loading="isLoading" ghost @click="handleRefresh">
        <template #icon>
          <IconRefreshLine />
        </template>
      </VButton>
    </template>
  </VPageHeader>

  <div class=":uno: p-4">
    <VLoading v-if="isLoading" />

    <VEmpty
      v-else-if="!data?.items?.length"
      title="暂无撤回申请"
      message="新的撤回申请会显示在这里"
    />

    <VCard v-else :body-class="[':uno: !p-0']">
      <VEntityContainer>
        <VEntity v-for="item in data.items" :key="item.metadata.name">
          <template #start>
            <VEntityField
              :title="item.spec.displayName"
              :description="item.spec.qq ? `QQ: ${item.spec.qq}` : '未填写 QQ'"
              class=":uno: min-w-[180px]"
            />
          </template>
          <template #end>
            <VEntityField class=":uno: min-w-[180px]">
              <template #description>{{ item.spec.email || "未填写邮箱" }}</template>
            </VEntityField>
            <VEntityField class=":uno: min-w-[100px]">
              <template #description>{{ item.spec.groupName || "未分组" }}</template>
            </VEntityField>
            <VEntityField class=":uno: min-w-[140px]">
              <template #description>{{ formatDatetime(item.metadata.creationTimestamp) }}</template>
            </VEntityField>
            <VEntityField class=":uno: min-w-[132px]">
              <template #description>
                <VSpace spacing="xs" class=":uno: justify-end">
                  <VButton
                    size="sm"
                    type="secondary"
                    :loading="approvingName === item.metadata.name"
                    @click="handleApprove(item)"
                  >批准</VButton>
                  <VButton
                    size="sm"
                    type="danger"
                    :loading="rejectingName === item.metadata.name"
                    @click="handleReject(item)"
                  >拒绝</VButton>
                </VSpace>
              </template>
            </VEntityField>
          </template>
        </VEntity>
      </VEntityContainer>
    </VCard>
  </div>
</template>
