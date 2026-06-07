<script lang="ts" setup>
import { membersCoreApiClient } from "@/api";
import { QK_MEMBERS } from "@/composables/use-member-fetch";
import type { MemberGroup } from "@/types";
import type { MemberFormState } from "@/types";
import { Toast, VButton, VModal, VSpace } from "@halo-dev/components";
import { useMutation, useQueryClient } from "@tanstack/vue-query";
import { computed, useTemplateRef } from "vue";
import MemberForm from "./MemberForm.vue";

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
    // Query max priority
    const { data: memberList } = await membersCoreApiClient.member.list({ page: 1, size: 1 });
    const maxPriority = (memberList.items?.[0] as any)?.spec?.priority || 0;

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
        avatar: data.avatar,
        qqFriendLink: data.qqFriendLink,
        groupName: props.group?.metadata.name || data.groupName,
        status: data.status,
        priority: maxPriority + 1,
      },
    });
  },
  onSuccess: () => {
    Toast.success("创建成员成功");
    modal.value?.close();
    queryClient.invalidateQueries({ queryKey: [QK_MEMBERS] });
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
