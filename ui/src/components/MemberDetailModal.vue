<script lang="ts" setup>
import type { Member } from "@/types";
import { formatDatetime } from "@/utils/date";
import { VAvatar, VButton, VModal, VSpace, VTag } from "@halo-dev/components";
import { computed, useTemplateRef } from "vue";

const props = defineProps<{
  member: Member;
  groupName: string;
}>();

const emit = defineEmits<{
  (event: "close"): void;
  (event: "edit"): void;
  (event: "approve"): void;
  (event: "reject"): void;
  (event: "revert"): void;
}>();

const modal = useTemplateRef<InstanceType<typeof VModal> | null>("modal");
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
</script>

<template>
  <VModal
    ref="modal"
    :centered="false"
    :mount-to-body="true"
    :title="`成员详情 - ${member.spec.displayName}`"
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
        <div class=":uno: flex gap-3 px-3.5 py-2.5 text-sm">
          <dt class=":uno: w-22 shrink-0 text-gray-500">邮箱</dt>
          <dd class=":uno: min-w-0 flex-1 break-all text-gray-900">
            <a v-if="member.spec.email" :href="`mailto:${member.spec.email}`" class=":uno: text-blue-600 hover:underline">{{ member.spec.email }}</a>
            <span v-else>未填写</span>
          </dd>
        </div>
        <div class=":uno: flex gap-3 px-3.5 py-2.5 text-sm">
          <dt class=":uno: w-22 shrink-0 text-gray-500">QQ</dt>
          <dd class=":uno: min-w-0 flex-1 break-all text-gray-900">{{ member.spec.qq || "未填写" }}</dd>
        </div>
        <div class=":uno: flex gap-3 px-3.5 py-2.5 text-sm">
          <dt class=":uno: w-22 shrink-0 text-gray-500">分组</dt>
          <dd class=":uno: min-w-0 flex-1 text-gray-900">{{ groupName }}</dd>
        </div>
        <div class=":uno: flex gap-3 px-3.5 py-2.5 text-sm">
          <dt class=":uno: w-22 shrink-0 text-gray-500">优先级</dt>
          <dd class=":uno: min-w-0 flex-1 text-gray-900">{{ member.spec.priority || 0 }}</dd>
        </div>
        <div v-if="member.spec.qqFriendLink" class=":uno: flex gap-3 px-3.5 py-2.5 text-sm">
          <dt class=":uno: w-22 shrink-0 text-gray-500">QQ 加好友</dt>
          <dd class=":uno: min-w-0 flex-1 break-all">
            <a :href="member.spec.qqFriendLink" target="_blank" rel="noopener noreferrer" class=":uno: text-blue-600 hover:underline">{{ member.spec.qqFriendLink }}</a>
          </dd>
        </div>
        <div v-if="member.spec.website" class=":uno: flex gap-3 px-3.5 py-2.5 text-sm">
          <dt class=":uno: w-22 shrink-0 text-gray-500">网站</dt>
          <dd class=":uno: min-w-0 flex-1 break-all">
            <a :href="member.spec.website" target="_blank" rel="noopener noreferrer" class=":uno: text-blue-600 hover:underline">{{ member.spec.website }}</a>
          </dd>
        </div>
        <div v-if="member.spec.description" class=":uno: flex gap-3 px-3.5 py-2.5 text-sm">
          <dt class=":uno: w-22 shrink-0 text-gray-500">简介</dt>
          <dd class=":uno: min-w-0 flex-1 whitespace-pre-wrap text-gray-900">{{ member.spec.description }}</dd>
        </div>
        <div v-if="reviewDescription" class=":uno: flex gap-3 px-3.5 py-2.5 text-sm">
          <dt class=":uno: w-22 shrink-0 text-gray-500">审核说明</dt>
          <dd class=":uno: min-w-0 flex-1 whitespace-pre-wrap text-gray-900">{{ reviewDescription }}</dd>
        </div>
        <div v-if="member.metadata.creationTimestamp" class=":uno: flex gap-3 px-3.5 py-2.5 text-sm">
          <dt class=":uno: w-22 shrink-0 text-gray-500">创建时间</dt>
          <dd class=":uno: min-w-0 flex-1 text-gray-900">{{ formatDatetime(member.metadata.creationTimestamp) }}</dd>
        </div>
      </dl>
    </div>

    <template #footer>
      <VSpace>
        <template v-if="member.spec.status === 'PENDING'">
          <VButton type="secondary" @click="emit('approve')">通过</VButton>
          <VButton type="danger" @click="emit('reject')">拒绝</VButton>
        </template>
        <VButton v-else @click="emit('revert')">撤回审核</VButton>
        <VButton type="secondary" @click="emit('edit')">编辑</VButton>
        <VButton @click="modal?.close()">关闭</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
