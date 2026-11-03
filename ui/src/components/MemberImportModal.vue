<script lang="ts" setup>
import { membersBatchApiClient, type BatchOperationResult } from "@/api";
import { invalidateMemberViews } from "@/api/query-keys";
import { useMemberGroupFetch } from "@/composables/use-group-fetch";
import { errorMessage } from "@/utils/error";
import { VButton, VModal, VSpace, Toast } from "@halo-dev/components";
import { useTemplateRef, ref, computed } from "vue";
import { useMutation, useQueryClient } from "@tanstack/vue-query";

const emit = defineEmits<{ (event: "close"): void }>();
const queryClient = useQueryClient();
const modal = useTemplateRef<InstanceType<typeof VModal> | null>("modal");
const fileInput = useTemplateRef<HTMLInputElement | null>("fileInput");
const selectedFile = ref<File | null>(null);
const defaultGroup = ref("");

// 只列真实存在的分组：这个值是直接写进 spec.groupName 的，
// 写成不存在的名字不会报错，只会在前台悄悄归入「未分组」。
const { groups } = useMemberGroupFetch();
const groupOptions = computed(() => [
  { label: "不指定（保留 CSV 里的分组）", value: "" },
  ...(groups.value || []).map((group) => ({
    label: `${group.spec.displayName}（${group.metadata.name}）`,
    value: group.metadata.name,
  })),
]);

const importMutation = useMutation({
  mutationFn: async ({ csvData, defaultGroup }: { csvData: string; defaultGroup: string }) => {
    const { data } = await membersBatchApiClient.member.importCsv(csvData, defaultGroup);
    return data as BatchOperationResult;
  },
  onSuccess: (data) => {
    // 后端字段是 success / failed / total，早先读的 `succeeded` 并不存在，
    // 于是「成功」永远显示 0。这里按类型读，写错字段名会直接编译报错。
    const success = data.success ?? 0;
    const failed = data.failed ?? 0;
    const total = data.total ?? success + failed;
    if (failed > 0) {
      const preview = data.failedMembers?.slice(0, 3).join("、");
      const more = (data.failedMembers?.length ?? 0) > 3 ? " 等" : "";
      Toast.warning(
        `导入完成：成功 ${success} 个，失败 ${failed} 个（共 ${total} 条）`
          + (preview ? `；失败行：${preview}${more}` : "")
      );
    } else {
      Toast.success(`成功导入 ${success} 个成员`);
    }
    void invalidateMemberViews(queryClient);
    modal.value?.close();
    emit("close");
  },
  onError: (error) => {
    Toast.error(errorMessage(error, "导入失败"));
  },
});

const handleFileSelect = (event: Event) => {
  const input = event.target as HTMLInputElement;
  if (input.files && input.files.length > 0) {
    selectedFile.value = input.files[0];
  }
};

const handleImport = () => {
  if (!selectedFile.value) {
    Toast.warning("请先选择 CSV 文件");
    return;
  }

  const reader = new FileReader();
  reader.onload = (e) => {
    const csvData = e.target?.result as string;
    if (!csvData) {
      Toast.error("文件内容为空");
      return;
    }
    importMutation.mutate({ csvData, defaultGroup: defaultGroup.value });
  };
  reader.onerror = () => {
    Toast.error("读取文件失败");
  };
  reader.readAsText(selectedFile.value, "UTF-8");
};

const downloadTemplate = () => {
  // 示例里的分组必须写资源名（不是「校友」这种显示名），否则照模板导入的人
  // 全员落进「未分组」——旧模板就是栽在这里。优先用当前选中的默认分组。
  const sampleGroup = defaultGroup.value || groups.value?.[0]?.metadata.name || "";
  const template = `displayName,email,school,qq,groupName,qqFriendLink
张三,zhangsan@example.com,清华大学,123456789,${sampleGroup},
李四,lisi@example.com,北京大学,987654321,${sampleGroup},`;
  const blob = new Blob([template], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "members_import_template.csv";
  a.click();
  URL.revokeObjectURL(url);
};
</script>
<template>
  <VModal :centered="false" title="批量导入" ref="modal" :mount-to-body="true" :width="500" @close="emit('close')">
    <div class="space-y-4">
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">选择 CSV 文件</label>
        <input
          ref="fileInput"
          type="file"
          accept=".csv,text/csv"
          class="block w-full text-sm text-gray-500 file:mr-4 file:py-2 file:px-4 file:rounded-md file:border-0 file:text-sm file:font-semibold file:bg-blue-50 file:text-blue-700 hover:file:bg-blue-100"
          @change="handleFileSelect"
        />
        <p class="mt-1 text-xs text-gray-500">
          CSV 文件需包含表头，支持字段：displayName(账号名称)、email(邮箱)、school(学校)、qq(QQ号)、groupName(分组标识)、qqFriendLink(QQ链接)
        </p>
      </div>

      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">默认分组（可选）</label>
        <FormKit
          v-model="defaultGroup"
          type="select"
          name="import-default-group"
          :options="groupOptions"
          :help="`CSV 的 groupName 列只写分组的标识（括号里的英文名），不是显示名；留空的行使用这里的默认分组。当前可选 ${groupOptions.length - 1} 个分组。`"
        />
      </div>

      <div class="flex justify-between items-center">
        <button
          type="button"
          class="text-sm text-blue-600 hover:text-blue-800 underline"
          @click="downloadTemplate"
        >
          下载模板文件
        </button>
      </div>

      <div v-if="selectedFile" class="text-sm text-gray-600">
        已选择：<span class="font-medium">{{ selectedFile.name }}</span>
        <span class="text-gray-400 ml-2">({{ (selectedFile.size / 1024).toFixed(1) }} KB)</span>
      </div>
    </div>

    <template #footer>
      <VSpace>
        <VButton @click="modal?.close()">取消</VButton>
        <VButton
          type="primary"
          :loading="importMutation.isPending.value"
          :disabled="!selectedFile"
          @click="handleImport"
        >
          开始导入
        </VButton>
      </VSpace>
    </template>
  </VModal>
</template>
