<script lang="ts" setup>
import type { Member } from "@/types";
import { computed } from "vue";
import { IconMore, VButton, VDropdown, VDropdownItem } from "@halo-dev/components";

const props = defineProps<{
  selectMode?: boolean;
  member: Member;
}>();

const emit = defineEmits<{
  (event: "open-edit"): void;
  (event: "approve"): void;
  (event: "reject"): void;
  (event: "revert"): void;
}>();

function handleClick() {
  if (!props.selectMode) {
    emit("open-edit");
  }
}

const displayName = computed(() => props.member.spec?.displayName || props.member.metadata.name);
const school = computed(() => props.member.spec?.school || "");
const qq = computed(() => props.member.spec?.qq || "");
const avatar = computed(() => props.member.spec?.avatar || "");
const status = computed(() => props.member.spec?.status || "PENDING");

const statusLabel = computed(() => {
  return { APPROVED: "已通过", PENDING: "待审核", REJECTED: "已拒绝" }[status.value] || "未知";
});
const statusTone = computed(() => {
  return ({ APPROVED: "success", PENDING: "warning", REJECTED: "danger" } as Record<string, string>)[status.value] || "muted";
});

const isPending = computed(() => status.value === "PENDING");
const isReviewed = computed(() => status.value === "APPROVED" || status.value === "REJECTED");
</script>

<template>
  <label
    class=":uno: member-badge min-w-0 w-full cursor-pointer"
    :class="{
      ':uno: animate-flash opacity-50': member.metadata.deletionTimestamp,
      ':uno: member-badge--plain': !qq && !selectMode && !isPending && !isReviewed,
    }"
    @click="handleClick"
  >
    <!-- 第一行：头像 + 名称 -->
    <div class=":uno: member-badge__row1">
      <span v-if="selectMode" class=":uno: member-badge__media">
        <slot name="checkbox"></slot>
      </span>
      <img v-else-if="avatar" :src="avatar" class=":uno: member-badge__avatar" />
      <div v-else class=":uno: member-badge__avatar member-badge__avatar--placeholder"></div>

      <span
        class=":uno: member-badge__title"
        :class="{ ':uno: line-through': member.metadata.deletionTimestamp }"
        v-tooltip="{ content: displayName, disabled: selectMode }"
      >
        {{ displayName }}
      </span>
      <div v-if="!selectMode" class=":uno: member-badge__actions">
        <template v-if="isPending">
          <VButton
            v-tooltip="'通过审核'"
            class=":uno: !h-6 !px-2 !text-[0.6875rem]"
            size="sm"
            type="secondary"
            @click.stop="emit('approve')"
          >通过</VButton>
          <VButton
            v-tooltip="'拒绝申请'"
            class=":uno: !h-6 !px-2 !text-[0.6875rem]"
            size="sm"
            type="danger"
            @click.stop="emit('reject')"
          >拒绝</VButton>
        </template>
        <VDropdown v-else-if="isReviewed">
          <VButton v-tooltip="'更多审核操作'" size="sm" ghost @click.stop>
            <template #icon><IconMore class=":uno: h-3.5 w-3.5" /></template>
          </VButton>
          <template #popper>
            <VDropdownItem @click="emit('revert')">撤回审核</VDropdownItem>
          </template>
        </VDropdown>
      </div>
    </div>

    <!-- 第二行：学校 -->
    <div class=":uno: member-badge__row2">
      <span
        class=":uno: member-badge__school"
        v-tooltip="{ content: school, disabled: selectMode }"
      >
        {{ school }}
      </span>
    </div>

    <!-- 第三行：QQ + 状态 -->
    <div v-if="!selectMode" class=":uno: member-badge__row3">
      <span v-if="qq" class=":uno: member-badge__status-pill" v-tooltip="'QQ号'">
        <span class=":uno: member-badge__status-label">QQ:{{ qq }}</span>
      </span>
      <span
        class=":uno: member-badge__status-pill"
        :class="`:uno: member-badge__status-pill--${statusTone}`"
      >
        <span class=":uno: member-badge__status-label">{{ statusLabel }}</span>
      </span>
    </div>
  </label>
</template>

<style scoped>
.member-badge {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  overflow: hidden;
  border: 1px solid rgb(229 231 235);
  border-radius: 0.5rem;
  padding: 0.5625rem;
  transition: background-color 0.18s ease, border-color 0.18s ease;
  /* 默认不设置背景色，跟随全局液态玻璃效果 */
}
.member-badge:hover,
.member-badge:focus-within {
  border-color: rgb(209 213 219);
  background: rgb(243 244 246);
}
.member-badge--plain {
  justify-content: center;
}

/* 第一行：头像 + 名称 */
.member-badge__row1 {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 0.625rem;
}
.member-badge__media {
  display: inline-flex;
  width: 1.875rem;
  height: 1.875rem;
  align-items: center;
  justify-content: center;
}
.member-badge__avatar {
  width: 1.875rem;
  height: 1.875rem;
  flex: none;
  border-radius: 9999px;
  background: rgb(255 255 255);
  object-fit: cover;
  box-shadow: 0 0 0 1px rgb(15 23 42 / 0.08), 0 1px 2px rgb(15 23 42 / 0.06);
}
.member-badge__avatar--placeholder {
  background: rgb(209 213 219);
}
.member-badge__title {
  display: block;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: rgb(17 24 39);
  font-size: 0.8125rem;
  font-weight: 500;
  line-height: 1.125rem;
}

/* 第二行：学校 + 操作按钮 */
.member-badge__row2 {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding-left: calc(1.875rem + 0.625rem);
}
.member-badge__school {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: rgb(107 114 128);
  font-size: 0.75rem;
  line-height: 1rem;
}
.member-badge__actions {
  display: flex;
  margin-left: auto;
  align-items: center;
  gap: 0.25rem;
  flex: none;
}

/* 第三行：QQ + 状态 */
.member-badge__row3 {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-start;
  gap: 0.375rem;
  padding-left: calc(1.875rem + 0.625rem);
}
.member-badge__status-pill {
  --status-color: rgb(107 114 128);
  display: inline-flex;
  min-width: 0;
  height: 1.125rem;
  flex: none;
  align-items: center;
  justify-content: center;
  gap: 0.1875rem;
  overflow: hidden;
  color: rgb(107 114 128);
  font-size: 0.625rem;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
}
.member-badge__status-label {
  overflow: hidden;
  text-overflow: ellipsis;
}
.member-badge__status-pill--success { --status-color: rgb(22 163 74); }
.member-badge__status-pill--warning { --status-color: rgb(202 138 4); }
.member-badge__status-pill--danger { --status-color: rgb(220 38 38); }
.member-badge__status-pill--muted { --status-color: rgb(148 163 184); }
</style>
