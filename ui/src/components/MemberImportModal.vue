<script lang="ts" setup>
import { VButton, VModal, VSpace, Toast } from "@halo-dev/components";
import { useTemplateRef, ref } from "vue";
import { useMutation } from "@tanstack/vue-query";
import { axiosInstance } from "@halo-dev/api-client";

const emit = defineEmits<{ (event: "close"): void }>();
const modal = useTemplateRef<InstanceType<typeof VModal> | null>("modal");
const fileInput = useTemplateRef<HTMLInputElement | null>("fileInput");
const selectedFile = ref<File | null>(null);
const defaultGroup = ref("");

const importMutation = useMutation({
  mutationFn: async ({ csvData, defaultGroup }: { csvData: string; defaultGroup: string }) => {
    const { data } = await axiosInstance.post(
      "/apis/api.member.plugin.halo.run/v1alpha1/members/-/import-csv",
      { csvData, defaultGroup: defaultGroup || null }
    );
    return data;
  },
  onSuccess: (data) => {
    const success = data.succeeded || 0;
    const failed = data.failed || 0;
    if (failed > 0) {
      Toast.warning(`导入完成：成功 ${success} 个，失败 ${failed} 个`);
    } else {
      Toast.success(`成功导入 ${success} 个成员`);
    }
    modal.value?.close();
    emit("close");
  },
  onError: (error: any) => {
    Toast.error(error.response?.data?.message || error.message || "导入失败");
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
  const template = `displayName,email,school,qq,groupName,qqFriendLink
张三,zhangsan@example.com,清华大学,123456789,校友,
李四,lisi@example.com,北京大学,987654321,校友,`;
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
          CSV 文件需包含表头，支持字段：displayName(账号名称)、email(邮箱)、school(学校)、qq(QQ号)、groupName(分组)、qqFriendLink(QQ链接)
        </p>
      </div>

      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">默认分组（可选）</label>
        <input
          v-model="defaultGroup"
          type="text"
          class="block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 text-sm"
          placeholder="如果 CSV 中未指定分组，将使用此默认分组"
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
