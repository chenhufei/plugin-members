<script lang="ts" setup>
import { membersCoreApiClient } from "@/api";
import { QK_MEMBER_GROUPS } from "@/composables/use-group-fetch";
import type { GroupFormState } from "@/types";
import { Toast, VButton, VModal, VSpace } from "@halo-dev/components";
import { useMutation, useQueryClient } from "@tanstack/vue-query";
import { useTemplateRef } from "vue";
import GroupForm from "./GroupForm.vue";

const emit = defineEmits<{
  (event: "close"): void;
}>();

const queryClient = useQueryClient();
const modal = useTemplateRef<InstanceType<typeof VModal> | null>("modal");

const { mutate, isPending } = useMutation({
  mutationFn: async (data: GroupFormState) => {
    // Fetch all groups to calculate max priority
    const { data: groupList } = await membersCoreApiClient.memberGroup.list();
    const maxPriority = groupList.items?.[0]?.spec?.priority || 0;

    return membersCoreApiClient.memberGroup.create({
      apiVersion: "member.plugin.halo.run/v1alpha1",
      kind: "MemberGroup",
      metadata: {
        name: "",
        generateName: "member-group-",
        annotations: data.annotations,
      },
      spec: {
        displayName: data.displayName,
        description: data.description || "",
        priority: maxPriority + 1,
      },
    });
  },
  onSuccess: () => {
    Toast.success("创建分组成功");
    modal.value?.close();
    queryClient.invalidateQueries({ queryKey: [QK_MEMBER_GROUPS] });
  },
});

function onSubmit(data: GroupFormState) {
  mutate(data);
}
</script>
<template>
  <VModal :centered="false" title="新建分组" ref="modal" :mount-to-body="true" :width="600" @close="emit('close')">
    <GroupForm @submit="onSubmit" />
    <template #footer>
      <VSpace>
        <!-- @vue-ignore -->
        <VButton :loading="isPending" type="secondary" @click="$formkit.submit('group-form')"> 保存 </VButton>
        <VButton @click="modal?.close()">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
