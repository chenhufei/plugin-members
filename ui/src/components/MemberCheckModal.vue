<script lang="ts" setup>
import { QK_MEMBER_GROUPS } from "@/composables/use-group-fetch";
import { QK_MEMBERS } from "@/composables/use-member-fetch";
import { Toast, VButton, VModal, VSpace } from "@halo-dev/components";
import { ref, useTemplateRef } from "vue";
import type { Member } from "@/types";
import { membersCoreApiClient } from "@/api";
import { useQueryClient } from "@tanstack/vue-query";

const props = withDefaults(
  defineProps<{
    member: Member;
    initialStatus?: "APPROVED" | "REJECTED";
  }>(),
  {
    initialStatus: "APPROVED",
  }
);

const emit = defineEmits<{
  (event: "close"): void;
}>();

const saving = ref<boolean>(false);
const formState = ref({
  checkStatus: props.initialStatus,
  reason: "",
});

const queryClient = useQueryClient();
const modal = useTemplateRef<InstanceType<typeof VModal> | null>("modal");

const submitCheckForm = () => {
  const form = document.getElementById("check-form") as HTMLFormElement | null;
  form?.requestSubmit();
};

const handleCheck = async () => {
  try {
    saving.value = true;

    const patches: unknown[] = [
      {
        op: "add",
        path: "/spec/status",
        value: formState.value.checkStatus,
      },
    ];

    const reviewDescription =
      formState.value.checkStatus === "REJECTED" ? formState.value.reason : "";
    patches.push({
      op: "add",
      path: "/metadata/annotations",
      value: {
        ...(props.member.metadata.annotations || {}),
        "member.plugin.halo.run/review-description": reviewDescription,
      },
    });

    await membersCoreApiClient.member.patch(props.member.metadata.name, patches);

    modal.value?.close();
    Toast.success("审核成功");
  } catch (error) {
    console.error("Failed to check Member", error);
    Toast.error("审核失败");
  } finally {
    queryClient.invalidateQueries({ queryKey: ["member-submits"] });
    queryClient.invalidateQueries({ queryKey: [QK_MEMBERS] });
    queryClient.invalidateQueries({ queryKey: [QK_MEMBER_GROUPS] });
    saving.value = false;
  }
};
</script>

<template>
  <VModal ref="modal" :centered="false" :mount-to-body="true" title="成员申请审核" :width="650" @close="emit('close')">
    <FormKit
      id="check-form"
      name="check-form"
      type="form"
      :config="{ validationVisibility: 'submit' }"
      @submit="handleCheck"
    >
      <FormKit :disabled="true" :value="member.spec.displayName" name="displayName" type="text" label="账号名称" />
      <FormKit :disabled="true" :value="member.spec.email" name="email" type="text" label="邮箱" />
      <FormKit :disabled="true" :value="member.spec.school" name="school" type="text" label="所属学校" />
      <FormKit :disabled="true" :value="member.spec.qq" name="qq" type="text" label="QQ号" />
      <FormKit v-if="member.spec.qqFriendLink" :disabled="true" :value="member.spec.qqFriendLink" name="qqFriendLink" type="text" label="QQ加好友链接" />
      <FormKit
        v-model="formState.checkStatus"
        :options="[
          { label: '通过', value: 'APPROVED' },
          { label: '拒绝', value: 'REJECTED' },
        ]"
        label="审核状态"
        name="checkStatus"
        type="select"
      />
      <FormKit
        v-if="formState.checkStatus === 'REJECTED'"
        v-model="formState.reason"
        type="textarea"
        name="reason"
        label="拒绝原因"
        placeholder="请输入拒绝原因"
        validation="required"
        rows="3"
      />
    </FormKit>

    <template #footer>
      <VSpace>
        <VButton :loading="saving" type="secondary" @click="submitCheckForm">提交</VButton>
        <VButton @click="modal?.close()">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
