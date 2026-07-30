<script lang="ts" setup>
import {
  Dialog,
  IconRefreshLine,
  Toast,
  VButton,
  VCard,
  VEntityField,
  VLoading,
  VSpace,
} from "@halo-dev/components";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import { ref } from "vue";
import {
  getWithdrawRequests,
  approveWithdraw,
  rejectWithdraw,
} from "@/api";

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

const withdrawRequests = ref<WithdrawMember[]>([]);

// 监听数据变化
const observeData = () => {
  const interval = setInterval(() => {
    const newData = queryClient.getQueryData<{ items: WithdrawMember[] }>(["plugin:members:withdraw-requests"]);
    if (newData && newData.items.length !== withdrawRequests.value.length) {
      withdrawRequests.value = newData.items || [];
    }
  }, 1000);
  return () => clearInterval(interval);
};

// 手动刷新
const handleRefresh = async () => {
  await refetch();
  withdrawRequests.value = data.value?.items || [];
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

observeData();
</script>

<template>
  <VCard>
    <div class="flex items-center justify-between px-6 pt-6 pb-4">
      <div>
        <h3 class="text-lg font-medium">撤回申请审核</h3>
        <p class="text-sm text-gray-500 mt-1">
          用户提交的撤回申请将在此显示，管理员可进行审批操作
        </p>
      </div>
      <VButton @click="handleRefresh" :loading="isLoading" ghost>
        <template #icon>
          <IconRefreshLine />
        </template>
        刷新
      </VButton>
    </div>

    <VLoading v-if="isLoading" />

    <div v-else class="px-6 pb-6">
      <div v-if="!data || !data.items || data.items.length === 0" class="text-center py-12 text-gray-400">
        暂无撤回申请
      </div>

      <div v-else>
        <div
          v-for="item in data.items"
          :key="item.metadata.name"
          class="border rounded-lg p-4 mb-3 hover:bg-gray-50 transition-colors"
        >
          <div class="flex items-start justify-between">
            <div class="flex-1">
              <div class="flex items-center gap-3 mb-2">
                <span class="font-medium text-base">{{ item.spec.displayName }}</span>
                <span class="text-sm text-gray-500">@{{ item.spec.qq }}</span>
              </div>
              <div class="flex flex-wrap gap-x-6 gap-y-1 text-sm text-gray-600">
                <VEntityField :title="'邮箱'" :detail="item.spec.email" />
                <VEntityField :title="''" :detail="item.spec.groupName || '未分组'" />
                <VEntityField
                  :title="''"
                  :detail="item.metadata.creationTimestamp"
                />
              </div>
            </div>

            <VSpace>
              <VButton
                size="sm"
                type="primary"
                @click="handleApprove(item)"
                :loading="approvingName === item.metadata.name"
              >
                批准
              </VButton>
              <VButton
                size="sm"
                @click="handleReject(item)"
                :loading="rejectingName === item.metadata.name"
              >
                拒绝
              </VButton>
            </VSpace>
          </div>
        </div>
      </div>
    </div>
  </VCard>
</template>
