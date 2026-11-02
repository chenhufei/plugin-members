<script lang="ts" setup>
import { membersCoreApiClient } from "@/api";
import { QK_MEMBER_GROUPS } from "@/composables/use-group-fetch";
import { QK_MEMBERS } from "@/composables/use-member-fetch";
import type { Member } from "@/types";
import { formatDatetime } from "@/utils/date";
import { Toast, VAvatar, VButton, VModal, VSpace, VTag } from "@halo-dev/components";
import { useQueryClient } from "@tanstack/vue-query";
import { computed, ref, useTemplateRef } from "vue";

const props = withDefaults(
  defineProps<{
    member: Member;
    groupName?: string;
    initialStatus?: "APPROVED" | "REJECTED";
  }>(),
  {
    groupName: "未分组",
    initialStatus: "APPROVED",
  }
);

const emit = defineEmits<{ (event: "close"): void }>();
const saving = ref(false);
const formState = ref({ checkStatus: props.initialStatus, reason: "" });
const queryClient = useQueryClient();
const modal = useTemplateRef<InstanceType<typeof VModal> | null>("modal");
const isPending = computed(() => props.member.spec.status === "PENDING");
const statusMeta = computed(() => {
  return {
    APPROVED: { label: "已通过", type: "success" },
    PENDING: { label: "待审核", type: "warning" },
    REJECTED: { label: "已拒绝", type: "danger" },
  }[props.member.spec.status] || { label: "未知", type: "default" };
});
const reviewDescription = computed(
  () => props.member.metadata.annotations?.["member.plugin.halo.run/review-description"] || ""
);

function submitCheckForm() {
  const form = document.getElementById("member-check-form") as HTMLFormElement | null;
  form?.requestSubmit();
}

async function handleCheck() {
  try {
    saving.value = true;
    const reviewDescriptionValue = formState.value.checkStatus === "REJECTED" ? formState.value.reason : "";
    await membersCoreApiClient.member.patch(props.member.metadata.name, [
      { op: "add", path: "/spec/status", value: formState.value.checkStatus },
      {
        op: "add",
        path: "/metadata/annotations",
        value: {
          ...(props.member.metadata.annotations || {}),
          "member.plugin.halo.run/review-description": reviewDescriptionValue,
          "member.plugin.halo.run/review-action":
            formState.value.checkStatus === "APPROVED" ? "APPROVE" : "REJECT",
          "member.plugin.halo.run/review-notification": "pending",
        },
      },
    ]);
    Toast.success(formState.value.checkStatus === "APPROVED" ? "已通过申请" : "已拒绝申请");
    modal.value?.close();
  } catch (error) {
    console.error("Failed to check Member", error);
    Toast.error("审核失败");
  } finally {
    queryClient.invalidateQueries({ queryKey: ["member-submits"] });
    queryClient.invalidateQueries({ queryKey: [QK_MEMBERS] });
    queryClient.invalidateQueries({ queryKey: [QK_MEMBER_GROUPS] });
    saving.value = false;
  }
}

async function handleRevert() {
  try {
    saving.value = true;
    await membersCoreApiClient.member.patch(props.member.metadata.name, [
      { op: "add", path: "/spec/status", value: "PENDING" },
      {
        op: "add",
        path: "/metadata/annotations",
        value: {
          ...(props.member.metadata.annotations || {}),
          "member.plugin.halo.run/review-description": "已撤回审核，恢复待审核状态",
        },
      },
    ]);
    Toast.success("已撤回审核");
    modal.value?.close();
  } catch (error) {
    console.error("Failed to revert Member review", error);
    Toast.error("撤回审核失败");
  } finally {
    queryClient.invalidateQueries({ queryKey: ["member-submits"] });
    queryClient.invalidateQueries({ queryKey: [QK_MEMBERS] });
    queryClient.invalidateQueries({ queryKey: [QK_MEMBER_GROUPS] });
    saving.value = false;
  }
}
</script>

<template>
  <VModal
    ref="modal"
    :centered="false"
    :mount-to-body="true"
    :title="isPending ? `审核申请 - ${member.spec.displayName}` : `申请详情 - ${member.spec.displayName}`"
    :width="640"
    @close="emit('close')"
  >
    <div class=":uno: space-y-4">
      <div class=":uno: flex min-w-0 items-center gap-3 border border-gray-200 rounded-lg bg-gray-50 px-4 py-3">
        <VAvatar :alt="member.spec.displayName" :src="member.spec.avatar" size="lg" />
        <div class=":uno: min-w-0 flex-1">
          <div class=":uno: truncate text-base text-gray-900 font-semibold">{{ member.spec.displayName }}</div>
          <div class=":uno: mt-1 truncate text-sm text-gray-500">{{ member.spec.school || "未填写学校" }}</div>
        </div>
        <VTag :type="statusMeta.type">{{ statusMeta.label }}</VTag>
      </div>

      <dl class=":uno: overflow-hidden border border-gray-200 rounded-lg divide-y divide-gray-100">
        <div class=":uno: flex gap-3 px-3.5 py-2.5 text-sm"><dt class=":uno: w-22 shrink-0 text-gray-500">邮箱</dt><dd class=":uno: min-w-0 flex-1 break-all text-gray-900">{{ member.spec.email || "未填写" }}</dd></div>
        <div class=":uno: flex gap-3 px-3.5 py-2.5 text-sm"><dt class=":uno: w-22 shrink-0 text-gray-500">QQ</dt><dd class=":uno: min-w-0 flex-1 break-all text-gray-900">{{ member.spec.qq || "未填写" }}</dd></div>
        <div class=":uno: flex gap-3 px-3.5 py-2.5 text-sm"><dt class=":uno: w-22 shrink-0 text-gray-500">分组</dt><dd class=":uno: min-w-0 flex-1 text-gray-900">{{ groupName }}</dd></div>
        <div v-if="member.spec.qqFriendLink" class=":uno: flex gap-3 px-3.5 py-2.5 text-sm"><dt class=":uno: w-22 shrink-0 text-gray-500">QQ 加好友</dt><dd class=":uno: min-w-0 flex-1 break-all"><a :href="member.spec.qqFriendLink" target="_blank" rel="noopener noreferrer" class=":uno: text-blue-600 hover:underline">{{ member.spec.qqFriendLink }}</a></dd></div>
        <div v-if="reviewDescription" class=":uno: flex gap-3 px-3.5 py-2.5 text-sm"><dt class=":uno: w-22 shrink-0 text-gray-500">审核说明</dt><dd class=":uno: min-w-0 flex-1 whitespace-pre-wrap text-gray-900">{{ reviewDescription }}</dd></div>
        <div v-if="member.metadata.creationTimestamp" class=":uno: flex gap-3 px-3.5 py-2.5 text-sm"><dt class=":uno: w-22 shrink-0 text-gray-500">提交时间</dt><dd class=":uno: min-w-0 flex-1 text-gray-900">{{ formatDatetime(member.metadata.creationTimestamp) }}</dd></div>
      </dl>

      <FormKit
        v-if="isPending"
        id="member-check-form"
        name="member-check-form"
        type="form"
        :actions="false"
        :config="{ validationVisibility: 'submit' }"
        @submit="handleCheck"
      >
        <FormKit
          v-model="formState.checkStatus"
          :options="[
            { label: '通过', value: 'APPROVED' },
            { label: '拒绝', value: 'REJECTED' },
          ]"
          label="审核结果"
          name="checkStatus"
          type="select"
        />
        <FormKit
          v-if="formState.checkStatus === 'REJECTED'"
          v-model="formState.reason"
          type="textarea"
          name="reason"
          label="拒绝原因"
          placeholder="请说明拒绝原因"
          validation="required"
          rows="3"
        />
      </FormKit>
    </div>

    <template #footer>
      <VSpace>
        <VButton v-if="isPending" :loading="saving" type="secondary" @click="submitCheckForm">提交审核</VButton>
        <VButton v-else :loading="saving" @click="handleRevert">撤回审核</VButton>
        <VButton @click="modal?.close()">{{ isPending ? "取消" : "关闭" }}</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
