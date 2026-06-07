<script lang="ts" setup>
import { membersCoreApiClient } from "@/api";
import { QK_MEMBER_GROUPS } from "@/composables/use-group-fetch";
import { QK_MEMBERS } from "@/composables/use-member-fetch";
import type { Member, MemberFormState } from "@/types";
import { Dialog, Toast, VButton, VModal, VSpace } from "@halo-dev/components";
import { useMutation, useQueryClient } from "@tanstack/vue-query";
import { useTemplateRef } from "vue";
import MemberForm from "./MemberForm.vue";

const props = withDefaults(
  defineProps<{
    member: Member;
  }>(),
  {}
);

const emit = defineEmits<{
  (event: "close"): void;
}>();

const queryClient = useQueryClient();
const modal = useTemplateRef<InstanceType<typeof VModal> | null>("modal");

const { mutate, isPending } = useMutation({
  mutationFn: (data: MemberFormState) => {
    const jsonPatchInner = [
      { op: "add", path: "/spec/displayName", value: data.displayName },
      { op: "add", path: "/spec/email", value: data.email || "" },
      { op: "add", path: "/spec/school", value: data.school || "" },
      { op: "add", path: "/spec/qq", value: data.qq },
      { op: "add", path: "/spec/avatar", value: data.avatar || "" },
      { op: "add", path: "/spec/qqFriendLink", value: data.qqFriendLink || "" },
      { op: "add", path: "/spec/groupName", value: data.groupName || "" },
      { op: "add", path: "/spec/status", value: data.status },
      { op: "add", path: "/spec/priority", value: data.priority },
      { op: "add", path: "/metadata/annotations", value: data.annotations || {} },
    ];
    return membersCoreApiClient.member.patch(props.member.metadata.name, jsonPatchInner);
  },
  onSuccess: () => {
    Toast.success("编辑成员成功");
    modal.value?.close();
    queryClient.invalidateQueries({ queryKey: [QK_MEMBER_GROUPS] });
    queryClient.invalidateQueries({ queryKey: [QK_MEMBERS] });
  },
});

function onSubmit(data: MemberFormState) {
  mutate(data);
}

function handleDelete() {
  Dialog.warning({
    title: "是否确认删除当前的成员？",
    description: "删除之后将无法恢复。",
    confirmType: "danger",
    onConfirm: async () => {
      await membersCoreApiClient.member.delete(props.member.metadata.name);
      Toast.success("删除成功");
      modal.value?.close();
      queryClient.invalidateQueries({ queryKey: [QK_MEMBERS] });
      queryClient.invalidateQueries({ queryKey: [QK_MEMBER_GROUPS] });
    },
  });
}
</script>
<template>
  <VModal :centered="false" title="编辑成员" ref="modal" :mount-to-body="true" :width="650" @close="emit('close')">
    <template #actions>
      <slot name="append-actions" />
    </template>

    <MemberForm
      :key="member.metadata.name"
      :name="member.metadata.name"
      :formState="{
        displayName: member.spec.displayName,
        email: member.spec.email,
        school: member.spec.school || '',
        qq: member.spec.qq || '',
        avatar: member.spec.avatar || '',
        qqFriendLink: member.spec.qqFriendLink || '',
        groupName: member.spec.groupName || '',
        status: member.spec.status,
        priority: member.spec.priority || 0,
        annotations: member.metadata.annotations,
      }"
      @submit="onSubmit"
    />

    <template #footer>
      <div class=":uno: flex items-center justify-between">
        <VSpace>
          <!-- @vue-ignore -->
          <VButton :loading="isPending" type="secondary" @click="$formkit.submit('member-form')"> 保存 </VButton>
          <VButton @click="modal?.close()">取消</VButton>
        </VSpace>
        <VButton type="danger" ghost @click="handleDelete">删除</VButton>
      </div>
    </template>
  </VModal>
</template>
