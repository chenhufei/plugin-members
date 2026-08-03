<script lang="ts" setup>
import { membersCoreApiClient } from "@/api";
import { QK_MEMBER_GROUPS } from "@/composables/use-group-fetch";
import type { MemberGroup } from "@/types";
import { IconArrowUpDownLine, Toast, VButton, VLoading, VModal, VSpace } from "@halo-dev/components";
import { useQueryClient } from "@tanstack/vue-query";
import { onMounted, ref } from "vue";
import { VueDraggable } from "vue-draggable-plus";

const emit = defineEmits<{
  (event: "close"): void;
}>();

const queryClient = useQueryClient();
const modal = ref<InstanceType<typeof VModal> | null>(null);
const groups = ref<MemberGroup[]>([]);
const isLoading = ref(false);
const isSubmitting = ref(false);

async function fetchGroups() {
  isLoading.value = true;
  try {
    const { data } = await membersCoreApiClient.memberGroup.list();
    groups.value = [...(data.items || [])].sort(
      (a, b) => (a.spec.priority || 0) - (b.spec.priority || 0),
    );
  } finally {
    isLoading.value = false;
  }
}

onMounted(fetchGroups);

async function handleSave() {
  isSubmitting.value = true;
  try {
    await Promise.all(
      groups.value.map((group, index) =>
        membersCoreApiClient.memberGroup.patch(group.metadata.name, [
          { op: "add", path: "/spec/priority", value: index + 1 },
        ]),
      ),
    );
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: [QK_MEMBER_GROUPS] }),
      queryClient.invalidateQueries({ queryKey: ["plugin:members:grouped-members"] }),
    ]);
    Toast.success("分组排序已保存");
    modal.value?.close();
  } finally {
    isSubmitting.value = false;
  }
}
</script>

<template>
  <VModal
    ref="modal"
    title="调整分组排序"
    :centered="false"
    :mount-to-body="true"
    :width="600"
    @close="emit('close')"
  >
    <VLoading v-if="isLoading" />
    <VueDraggable
      v-else
      v-model="groups"
      class=":uno: rounded-base overflow-hidden border divide-y divide-gray-100"
    >
      <div
        v-for="group in groups"
        :key="group.metadata.name"
        class=":uno: flex cursor-move items-center gap-2 px-3 py-2 text-sm text-gray-600 font-semibold transition-colors hover:text-gray-900"
      >
        <IconArrowUpDownLine class=":uno: size-4" />
        <span class=":uno: min-w-0 truncate">{{ group.spec.displayName }}</span>
      </div>
    </VueDraggable>

    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="isSubmitting" @click="handleSave">保存</VButton>
        <VButton @click="modal?.close()">关闭</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
