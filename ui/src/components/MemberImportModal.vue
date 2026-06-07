<script lang="ts" setup>
import { VButton, VModal, VSpace } from "@halo-dev/components";
import { useTemplateRef } from "vue";
const emit = defineEmits<{ (event: "close"): void }>();
const modal = useTemplateRef<InstanceType<typeof VModal> | null>("modal");
</script>
<template>
  <VModal :centered="false" title="批量导入" ref="modal" :mount-to-body="true" :width="500" @close="emit('close')">
    <p>请使用页面工具栏的"批量导入"按钮上传 CSV 文件。</p>
    <template #footer>
      <VSpace><VButton @click="modal?.close()">关闭</VButton></VSpace>
    </template>
  </VModal>
</template>
