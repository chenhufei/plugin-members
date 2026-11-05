<script lang="ts" setup>
import {
  Dialog,
  IconLink,
  IconRefreshLine,
  VButton,
  VLoading,
  VPageHeader,
  VSpace,
  Toast,
} from "@halo-dev/components";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import { computed, ref } from "vue";
import { axiosInstance } from "@halo-dev/api-client";
import { errorMessage } from "@/utils/error";
import { formatDatetime } from "@/utils/date";

// 认领管理是站内接口（api.member 分组需要登录 + 成员管理权限）；
// 匿名认领流程仍在 api.plugin 分组的 /plugins/PluginMembers/claim*。
const CLAIM_API_BASE = "/apis/api.member.plugin.halo.run/v1alpha1";

interface ClaimToken {
  memberName: string;
  displayName: string;
  qq: string;
  token: string;
  claimUrl: string;
  expiresAt: string | null;
  expired: boolean;
  groupName?: string;
  groupDisplayName?: string;
}

interface IncompleteMember {
  memberName: string;
  displayName: string;
  qq: string;
  school: string;
  hasEmail: boolean;
  targetEmail?: string;
  groupName?: string;
  groupDisplayName?: string;
}

const queryClient = useQueryClient();
const selectedIncomplete = ref<string[]>([]);
const selectedClaims = ref<string[]>([]);

const { data: incompleteData, isLoading: incompleteLoading, refetch: refetchIncomplete } = useQuery<IncompleteMember[]>({
  queryKey: ["plugin:members:incomplete"],
  queryFn: async () => {
    const { data } = await axiosInstance.get(`${CLAIM_API_BASE}/members/-/incomplete`);
    return data.items || [];
  },
});

const incompleteMembers = computed(() => incompleteData.value || []);

const { data: claimsData, isLoading: claimsLoading, refetch: refetchClaims } = useQuery<ClaimToken[]>({
  queryKey: ["plugin:members:claims"],
  queryFn: async () => {
    const { data } = await axiosInstance.get(`${CLAIM_API_BASE}/members/-/claims`);
    return data.items || [];
  },
  refetchInterval: 5000,
});

const claims = computed(() => claimsData.value || []);

const generateMutation = useMutation({
  mutationFn: async (memberNames: string[]) => {
    const { data } = await axiosInstance.post(`${CLAIM_API_BASE}/members/-/claims-generate`, { memberNames });
    return data;
  },
  onSuccess: (data) => {
    const generated = data.generated || 0;
    const failed = (data.items?.length || 0) - generated;
    if (failed > 0) {
      Toast.warning(`生成了 ${generated} 个链接，${failed} 个失败`);
    } else {
      Toast.success(`成功生成 ${generated} 个认领链接`);
    }
    queryClient.invalidateQueries({ queryKey: ["plugin:members:claims"] });
    queryClient.invalidateQueries({ queryKey: ["plugin:members:incomplete"] });
    selectedIncomplete.value = [];
  },
  onError: (error: any) => {
    Toast.error(errorMessage(error, "生成失败"));
  },
});

const invalidateMutation = useMutation({
  mutationFn: async (memberName: string) => {
    await axiosInstance.delete(`${CLAIM_API_BASE}/members/${memberName}/claim`);
  },
  onSuccess: () => {
    Toast.success("认领链接已作废");
    queryClient.invalidateQueries({ queryKey: ["plugin:members:claims"] });
    queryClient.invalidateQueries({ queryKey: ["plugin:members:incomplete"] });
  },
  onError: (error: any) => {
    Toast.error(errorMessage(error, "作废失败"));
  },
});

/**
 * 批量作废。后端只有单条 DELETE，这里并发下发再用 allSettled 汇总：
 * 一条失败不该把已经作废成功的链接重新变回有效，也不该让其余请求中止。
 */
const batchInvalidateMutation = useMutation({
  mutationFn: async (memberNames: string[]) => {
    const results = await Promise.allSettled(
      memberNames.map((memberName) => axiosInstance.delete(`${CLAIM_API_BASE}/members/${memberName}/claim`))
    );
    return {
      total: results.length,
      failed: results.filter((result) => result.status === "rejected").length,
    };
  },
  onSuccess: ({ total, failed }) => {
    if (failed > 0) {
      Toast.warning(`已作废 ${total - failed} 个认领链接，${failed} 个失败，请刷新后重试`);
    } else {
      Toast.success(`已作废 ${total} 个认领链接`);
    }
    selectedClaims.value = [];
    queryClient.invalidateQueries({ queryKey: ["plugin:members:claims"] });
    queryClient.invalidateQueries({ queryKey: ["plugin:members:incomplete"] });
  },
  onError: (error: any) => {
    Toast.error(errorMessage(error, "批量作废失败"));
  },
});

const distributeMutation = useMutation({
  mutationFn: async (memberNames: string[]) => {
    const { data } = await axiosInstance.post(`${CLAIM_API_BASE}/members/-/claims-distribute`, { memberNames });
    return data;
  },
  onSuccess: (data) => {
    const items = data.items || [];
    const emailed = items.filter((item: any) => item.emailed).length;
    const failures = items.filter((item: any) => !item.emailed);
    if (failures.length > 0) {
      const reasons = [...new Set(failures.map((item: any) => item.error || "未知错误"))];
      Toast.warning(
        `${emailed} 位成员的认领链接邮件已提交，${failures.length} 位未提交：${reasons.join("；")}`
      );
    } else {
      Toast.success(`已提交 ${emailed} 位成员的认领链接邮件，实际送达取决于站点邮件通知配置`);
    }
    queryClient.invalidateQueries({ queryKey: ["plugin:members:claims"] });
    queryClient.invalidateQueries({ queryKey: ["plugin:members:incomplete"] });
    selectedIncomplete.value = [];
  },
  onError: (error: any) => {
    Toast.error(errorMessage(error, "分发失败"));
  },
});

const handleGenerateSelected = () => {
  if (selectedIncomplete.value.length === 0) {
    Toast.warning("请先选择要生成认领链接的成员");
    return;
  }
  generateMutation.mutate(selectedIncomplete.value);
};

const handleDistributeSelected = () => {
  if (selectedIncomplete.value.length === 0) {
    Toast.warning("请先选择要分发认领链接的成员");
    return;
  }
  if (!confirm(`确定要向 ${selectedIncomplete.value.length} 位成员发送认领链接邮件吗？邮件发送到成员可用的邮箱（登记邮箱或 QQ 号派生的 QQ 邮箱）；既无邮箱也无 QQ 号的成员会被跳过并在结果中列出。`)) {
    return;
  }
  distributeMutation.mutate(selectedIncomplete.value);
};

const handleInvalidate = (memberName: string) => {
  if (confirm("确定要作废该认领链接吗？")) {
    invalidateMutation.mutate(memberName);
  }
};

const handleBatchInvalidate = () => {
  if (selectedClaims.value.length === 0) {
    Toast.warning("请先选择要作废的认领链接");
    return;
  }
  const count = selectedClaims.value.length;
  Dialog.warning({
    title: `作废选中的 ${count} 个认领链接？`,
    description: "作废后成员手里的旧链接立即失效，需要重新生成才能认领。",
    confirmType: "danger",
    // 返回 Promise，对话框会一直显示加载态直到请求结束；失败已由 onError 提示
    onConfirm: async () => {
      try {
        await batchInvalidateMutation.mutateAsync([...selectedClaims.value]);
      } catch {
        // onError 已提示，别把拒绝再抛回对话框
      }
    },
  });
};

const copyLink = (claim: ClaimToken) => {
  // 链接由服务端按站点外部地址生成，控制台域名可能与前台站点不同
  const link = claim.claimUrl || `${window.location.origin}/claim?token=${claim.token}`;
  navigator.clipboard.writeText(link).then(() => {
    Toast.success("链接已复制到剪贴板");
  });
};

const handleSelectAllIncomplete = () => {
  if (selectedIncomplete.value.length === incompleteMembers.value.length) {
    selectedIncomplete.value = [];
  } else {
    selectedIncomplete.value = incompleteMembers.value.map((m) => m.memberName);
  }
};

const handleSelectAllClaims = () => {
  if (selectedClaims.value.length === claims.value.length) {
    selectedClaims.value = [];
  } else {
    selectedClaims.value = claims.value.map((c) => c.memberName);
  }
};

const refreshAll = () => {
  refetchIncomplete();
  refetchClaims();
};

// 分组展示名缺失时回退到分组标识，两者都没有才算未分组
const groupLabel = (item: { groupName?: string; groupDisplayName?: string }) =>
  item.groupDisplayName || item.groupName || "-";
</script>

<template>
  <VPageHeader title="认领链接管理">
    <template #icon>
      <IconLink />
    </template>
    <template #actions>
      <VSpace>
        <VButton size="sm" type="secondary" @click="refreshAll">
          <template #icon>
            <IconRefreshLine />
          </template>
          刷新
        </VButton>
      </VSpace>
    </template>
  </VPageHeader>

  <div class="m-4 space-y-4">
    <!-- 信息不完善的成员 -->
    <div class="bg-white rounded-lg shadow-sm">
      <div class="p-4 border-b">
        <div class="flex items-center justify-between">
          <h3 class="text-lg font-medium">
            信息不完善的成员
            <span class="text-sm text-gray-500 ml-2">({{ incompleteMembers.length }})</span>
          </h3>
          <div class="flex items-center gap-3">
            <VButton
              v-if="selectedIncomplete.length > 0"
              size="xs"
              type="secondary"
              :loading="distributeMutation.isPending.value"
              @click="handleDistributeSelected"
            >
              邮件分发选中 ({{ selectedIncomplete.length }})
            </VButton>
            <VButton
              v-if="selectedIncomplete.length > 0"
              size="xs"
              type="primary"
              :loading="generateMutation.isPending.value"
              @click="handleGenerateSelected"
            >
              生成链接选中 ({{ selectedIncomplete.length }})
            </VButton>
            <label class="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                :checked="selectedIncomplete.length === incompleteMembers.length && incompleteMembers.length > 0"
                @change="handleSelectAllIncomplete"
              />
              全选
            </label>
          </div>
        </div>
        <p class="text-sm text-gray-500 mt-2">
          以下成员尚未填写 QQ 加好友链接。选择成员后可批量生成认领链接或发送邮件通知。
        </p>
      </div>

      <VLoading v-if="incompleteLoading" />

      <div v-else-if="incompleteMembers.length === 0" class="p-8 text-center text-gray-500">
        所有成员的 QQ 链接信息已完善
      </div>

      <table v-else class="w-full">
        <thead class="bg-gray-50 text-xs text-gray-500 uppercase">
          <tr>
            <th class="px-4 py-3 text-left w-8"></th>
            <th class="px-4 py-3 text-left">成员</th>
            <th class="px-4 py-3 text-left">QQ</th>
            <th class="px-4 py-3 text-left">邮箱</th>
            <th class="px-4 py-3 text-left">学校</th>
            <th class="px-4 py-3 text-left">分组</th>
          </tr>
        </thead>
        <tbody class="divide-y">
          <tr v-for="member in incompleteMembers" :key="member.memberName" class="hover:bg-gray-50">
            <td class="px-4 py-3">
              <input
                type="checkbox"
                :value="member.memberName"
                v-model="selectedIncomplete"
              />
            </td>
            <td class="px-4 py-3">
              <div class="font-medium">{{ member.displayName }}</div>
              <div class="text-xs text-gray-500">{{ member.memberName }}</div>
            </td>
            <td class="px-4 py-3 text-sm">{{ member.qq || "-" }}</td>
            <td class="px-4 py-3 text-sm font-mono">
              <span v-if="member.targetEmail">{{ member.targetEmail }}</span>
              <span v-else class="text-red-600">无可用邮箱</span>
            </td>
            <td class="px-4 py-3 text-sm">{{ member.school || "-" }}</td>
            <td class="px-4 py-3 text-sm">{{ groupLabel(member) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 已生成的认领链接 -->
    <div class="bg-white rounded-lg shadow-sm">
      <div class="p-4 border-b">
        <div class="flex items-center justify-between">
          <h3 class="text-lg font-medium">
            已生成的认领链接
            <span class="text-sm text-gray-500 ml-2">({{ claims.length }})</span>
          </h3>
          <div class="flex items-center gap-3">
            <VButton
              v-if="selectedClaims.length > 0"
              size="xs"
              type="danger"
              :loading="batchInvalidateMutation.isPending.value"
              @click="handleBatchInvalidate"
            >
              作废选中 ({{ selectedClaims.length }})
            </VButton>
            <label class="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                :checked="selectedClaims.length === claims.length && claims.length > 0"
                @change="handleSelectAllClaims"
              />
              全选
            </label>
          </div>
        </div>
      </div>

      <VLoading v-if="claimsLoading" />

      <div v-else-if="claims.length === 0" class="p-8 text-center text-gray-500">
        暂无已生成的认领链接
      </div>

      <table v-else class="w-full">
        <thead class="bg-gray-50 text-xs text-gray-500 uppercase">
          <tr>
            <th class="px-4 py-3 text-left w-8"></th>
            <th class="px-4 py-3 text-left">成员</th>
            <th class="px-4 py-3 text-left">分组</th>
            <th class="px-4 py-3 text-left">QQ</th>
            <th class="px-4 py-3 text-left">过期时间</th>
            <th class="px-4 py-3 text-left">状态</th>
            <th class="px-4 py-3 text-right">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y">
          <tr v-for="claim in claims" :key="claim.memberName" class="hover:bg-gray-50">
            <td class="px-4 py-3">
              <input
                type="checkbox"
                :value="claim.memberName"
                v-model="selectedClaims"
              />
            </td>
            <td class="px-4 py-3">
              <div class="font-medium">{{ claim.displayName }}</div>
              <div class="text-xs text-gray-500">{{ claim.memberName }}</div>
            </td>
            <td class="px-4 py-3 text-sm">{{ groupLabel(claim) }}</td>
            <td class="px-4 py-3 text-sm">{{ claim.qq || "-" }}</td>
            <td class="px-4 py-3 text-sm">
              {{ claim.expiresAt ? formatDatetime(claim.expiresAt) : "-" }}
            </td>
            <td class="px-4 py-3">
              <span
                v-if="claim.expired"
                class="px-2 py-1 text-xs rounded-full bg-red-100 text-red-700"
              >
                已过期
              </span>
              <span v-else class="px-2 py-1 text-xs rounded-full bg-green-100 text-green-700">
                有效
              </span>
            </td>
            <td class="px-4 py-3 text-right">
              <VSpace>
                <VButton
                  size="xs"
                  type="secondary"
                  @click="copyLink(claim)"
                >
                  复制链接
                </VButton>
                <VButton
                  size="xs"
                  type="danger"
                  @click="handleInvalidate(claim.memberName)"
                >
                  作废
                </VButton>
              </VSpace>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
