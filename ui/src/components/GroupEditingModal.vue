<script lang="ts" setup>
import { membersCoreApiClient } from "@/api";
import { QK_MEMBER_GROUPS } from "@/composables/use-group-fetch";
import type { GroupFormState, MemberGroup } from "@/types";
import { Toast, VButton, VModal, VSpace } from "@halo-dev/components";
import { useMutation, useQueryClient } from "@tanstack/vue-query";
import { useTemplateRef } from "vue";
import GroupForm from "./GroupForm.vue";

const props = defineProps<{
  group: MemberGroup;
}>();

const emit = defineEmits<{
  (event: "close"): void;
}>();

const queryClient = useQueryClient();
const modal = useTemplateRef<InstanceType<typeof VModal> | null>("modal");

const { mutate, isPending } = useMutation({
  mutationFn: (data: GroupFormState) => {
    return membersCoreApiClient.memberGroup.patch(props.group.metadata.name, [
      { op: "add", path: "/spec/displayName", value: data.displayName },
      { op: "add", path: "/spec/description", value: data.description || "" },
      { op: "add", path: "/spec/priority", value: data.priority },
      { op: "add", path: "/metadata/annotations", value: data.annotations || {} },
    ]);
  },
  onSuccess: () => {
    Toast.success("编辑分组成功");
    modal.value?.close();
    queryClient.invalidateQueries({ queryKey: [QK_MEMBER_GROUPS] });
  },
});

function onSubmit(data: GroupFormState) {
  mutate(data);
}
</script>
<template>
  <VModal :centered="false" title="编辑分组" ref="modal" :mount-to-body="true" :width="600" @close="emit('close')">
    <GroupForm
      :name="group.metadata.name"
      :formState="{
        displayName: group.spec.displayName,
        description: group.spec.description || '',
        priority: group.spec.priority || 0,
        annotations: group.metadata.annotations,
      }"
      @submit="onSubmit"
    />
    <template #footer>
      <VSpace>
        <!-- @vue-ignore -->
        <VButton :loading="isPending" type="secondary" @click="$formkit.submit('group-form')"> 保存 </VButton>
        <VButton @click="modal?.close()">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
