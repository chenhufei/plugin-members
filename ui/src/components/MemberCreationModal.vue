<script lang="ts" setup>
import { membersCoreApiClient } from "@/api";
import { invalidateMemberViews } from "@/api/query-keys";
import type { MemberGroup } from "@/types";
import type { MemberFormState } from "@/types";
import { Toast, VButton, VModal, VSpace } from "@halo-dev/components";
import { useMutation, useQueryClient } from "@tanstack/vue-query";
import { computed, useTemplateRef } from "vue";
import MemberForm from "./MemberForm.vue";
import { qqAvatar } from "@/utils/qq-link";
import { errorMessage } from "@/utils/error";

const props = defineProps<{
  group?: MemberGroup;
}>();

const emit = defineEmits<{
  (event: "close"): void;
}>();

const queryClient = useQueryClient();
const modal = useTemplateRef<InstanceType<typeof VModal> | null>("modal");

const { mutate, isPending } = useMutation({
  mutationFn: async (data: MemberFormState) => {
    return membersCoreApiClient.member.create({
      apiVersion: "member.plugin.halo.run/v1alpha1",
      kind: "Member",
      metadata: {
        name: "",
        generateName: "member-",
        annotations: data.annotations,
      },
      spec: {
        displayName: data.displayName,
        email: data.email,
        school: data.school,
        qq: data.qq,
        avatar: qqAvatar(data.qq),
        background: data.background,
        qqFriendLink: data.qqFriendLink,
        groupName: props.group?.metadata.name || data.groupName,
        status: data.status,
        origin: "CONSOLE",
        joinedAt: data.joinedAt || undefined,
      },
    });
  },
  onSuccess: () => {
    Toast.success("创建成员成功");
    modal.value?.close();
    void invalidateMemberViews(queryClient);
  },
  // 缺 onError 时后端拒绝（QQ 重复、名称为空、权限不足）在界面上毫无反应：
  // 只有按钮从 loading 回到正常，站长会以为没点上。
  onError: (error) => {
    console.error("Failed to create member", error);
    Toast.error(errorMessage(error, "创建成员失败，请检查填写内容"));
  },
});

function onSubmit(data: MemberFormState) {
  mutate(data);
}

const title = computed(() => {
  return ["创建成员", props.group?.spec?.displayName].filter(Boolean).join(" - ");
});
</script>
<template>
  <VModal :centered="false" :title="title" ref="modal" :mount-to-body="true" :width="650" @close="emit('close')">
    <MemberForm @submit="onSubmit" />
    <template #footer>
      <VSpace>
        <!-- @vue-ignore -->
        <VButton :loading="isPending" type="secondary" @click="$formkit.submit('member-form')"> 保存 </VButton>
        <VButton @click="modal?.close()">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
