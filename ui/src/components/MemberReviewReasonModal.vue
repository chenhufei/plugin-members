<script lang="ts" setup>
import { membersCoreApiClient } from "@/api";
import { QK_MEMBER_GROUPS } from "@/composables/use-group-fetch";
import { QK_MEMBERS } from "@/composables/use-member-fetch";
import type { Member } from "@/types";
import { Toast, VButton, VModal, VSpace } from "@halo-dev/components";
import { useQueryClient } from "@tanstack/vue-query";
import { computed, ref, useTemplateRef } from "vue";

const props = defineProps<{
  member: Member;
  action: "REJECT" | "OFFLINE";
}>();

const emit = defineEmits<{
  (event: "close"): void;
  (event: "success"): void;
}>();

const queryClient = useQueryClient();
const modal = useTemplateRef<InstanceType<typeof VModal> | null>("modal");
const saving = ref(false);
const reason = ref("");
const title = computed(() => props.action === "OFFLINE" ? "下架成员" : "拒绝成员申请");
const reasonLabel = computed(() => props.action === "OFFLINE" ? "下架原因" : "拒绝原因");

function submitReasonForm() {
  const form = document.getElementById("member-review-reason-form") as HTMLFormElement | null;
  form?.requestSubmit();
}

async function submit() {
  const value = reason.value.trim();
  if (!value) {
    Toast.error(`请填写${reasonLabel.value}`);
    return;
  }
  try {
    saving.value = true;
    const reviewedAt = new Date().toISOString();
    const nextAnnotations = {
      ...(props.member.metadata.annotations || {}),
      "member.plugin.halo.run/review-description": value,
      "member.plugin.halo.run/review-action": props.action,
      "member.plugin.halo.run/review-notification": "pending",
      ...(props.action === "OFFLINE"
        ? {
            "member.plugin.halo.run/offline-at": reviewedAt,
            "member.plugin.halo.run/offline-reason": value,
            "member.plugin.halo.run/offline-source": "ADMIN",
          }
        : {}),
    };
    await membersCoreApiClient.member.patch(props.member.metadata.name, [
      { op: "add", path: "/spec/status", value: "REJECTED" },
      {
        op: "add",
        path: "/metadata/annotations",
        value: nextAnnotations,
      },
    ]);
    Toast.success(props.action === "OFFLINE" ? "成员已下架" : "申请已拒绝");
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: [QK_MEMBERS] }),
      queryClient.invalidateQueries({ queryKey: [QK_MEMBER_GROUPS] }),
      queryClient.invalidateQueries({ queryKey: ["member-submits"] }),
      queryClient.invalidateQueries({ queryKey: ["plugin:members:withdraw-requests"] }),
    ]);
    emit("success");
    modal.value?.close();
  } catch (error) {
    console.error("Failed to update member review status", error);
    Toast.error("操作失败");
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <VModal
    ref="modal"
    :centered="false"
    :mount-to-body="true"
    :title="`${title} - ${member.spec.displayName}`"
    :width="520"
    @close="emit('close')"
  >
    <FormKit
      id="member-review-reason-form"
      type="form"
      :actions="false"
      :config="{ validationVisibility: 'submit' }"
      @submit="submit"
    >
      <FormKit
        v-model="reason"
        type="textarea"
        name="reason"
        :label="reasonLabel"
        :placeholder="`请填写${reasonLabel}，该内容会通过邮件通知申请人`"
        validation="required"
        :rows="5"
      />
    </FormKit>

    <template #footer>
      <VSpace>
        <VButton
          type="danger"
          :loading="saving"
          @click="submitReasonForm"
        >确认{{ action === "OFFLINE" ? "下架" : "拒绝" }}</VButton>
        <VButton @click="modal?.close()">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
