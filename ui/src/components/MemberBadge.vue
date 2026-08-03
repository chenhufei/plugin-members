<script lang="ts" setup>
import type { Member } from "@/types";
import { computed } from "vue";

const props = defineProps<{
  selectMode?: boolean;
  member: Member;
}>();

const emit = defineEmits<{
  (event: "open-detail"): void;
}>();

function handleClick(event: MouseEvent) {
  const target = event.target as HTMLElement;
  const currentTarget = event.currentTarget as HTMLElement;
  const interactiveTarget = target.closest("button, a, input, [role='button'], [role='menuitem']");
  if (props.selectMode || (interactiveTarget && interactiveTarget !== currentTarget)) return;
  emit("open-detail");
}

function handleKeydown() {
  if (!props.selectMode) emit("open-detail");
}

const displayName = computed(() => props.member.spec?.displayName || props.member.metadata.name);
const school = computed(() => props.member.spec?.school || "未填写学校");
const qq = computed(() => props.member.spec?.qq || "");
const avatar = computed(() => props.member.spec?.avatar || "");
const background = computed(() => props.member.spec?.background || "");
const status = computed(() => props.member.spec?.status || "PENDING");
const reviewAction = computed(
  () => props.member.metadata.annotations?.["member.plugin.halo.run/review-action"],
);

const statusLabel = computed(() => {
  if (status.value === "REJECTED" && reviewAction.value === "OFFLINE") return "已下架";
  if (status.value === "REJECTED" && reviewAction.value === "WITHDRAW") return "已撤回";
  return {
    APPROVED: "已通过",
    PENDING: "待审核",
    REJECTED: "已拒绝",
    WITHDRAW_REQUESTED: "撤回中",
  }[status.value] || "未知";
});

const statusTone = computed(() => {
  return ({
    APPROVED: "success",
    PENDING: "warning",
    REJECTED: "danger",
    WITHDRAW_REQUESTED: "warning",
  } as Record<string, string>)[status.value] || "muted";
});
</script>

<template>
  <article
    class=":uno: member-badge min-w-0 w-full cursor-pointer"
    :class="{
      ':uno: animate-flash opacity-50': member.metadata.deletionTimestamp,
      ':uno: member-badge--plain': selectMode,
    }"
    role="button"
    tabindex="0"
    @click="handleClick"
    @keydown.enter.prevent="handleKeydown"
    @keydown.space.prevent="handleKeydown"
  >
    <img
      v-if="background"
      :src="background"
      alt=""
      aria-hidden="true"
      class=":uno: member-badge__background"
    />
    <div class=":uno: member-badge__main">
      <span v-if="selectMode" class=":uno: member-badge__media">
        <slot name="checkbox"></slot>
      </span>
      <img v-else-if="avatar" :src="avatar" :alt="displayName" class=":uno: member-badge__avatar" />
      <span v-else class=":uno: member-badge__avatar member-badge__avatar--placeholder">
        {{ displayName.slice(0, 1) }}
      </span>

      <div class=":uno: member-badge__content">
        <span
          class=":uno: member-badge__title"
          :class="{ ':uno: line-through': member.metadata.deletionTimestamp }"
          v-tooltip="{ content: displayName, disabled: selectMode }"
        >
          {{ displayName }}
        </span>
        <span class=":uno: member-badge__school" v-tooltip="{ content: school, disabled: selectMode }">
          {{ school }}
        </span>
      </div>
    </div>

    <div v-if="!selectMode" class=":uno: member-badge__status-dock" aria-label="成员状态">
      <span
        class=":uno: member-badge__status-pill"
        :class="`:uno: member-badge__status-pill--${statusTone}`"
      >
        <span class=":uno: member-badge__status-dot"></span>
        <span class=":uno: member-badge__status-label">{{ statusLabel }}</span>
      </span>
      <span v-if="qq" class=":uno: member-badge__meta" v-tooltip="`QQ ${qq}`">
        QQ {{ qq }}
      </span>
    </div>
  </article>
</template>

<style scoped>
.member-badge {
  position: relative;
  display: flex;
  min-height: 4.625rem;
  flex-direction: column;
  gap: 0.4375rem;
  overflow: hidden;
  border: 1px solid rgb(229 231 235);
  border-radius: 0.5rem;
  background: rgb(249 250 251);
  padding: 0.5625rem;
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease;
}

.member-badge__background {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.12;
  pointer-events: none;
}

.member-badge > :not(.member-badge__background) {
  position: relative;
  z-index: 1;
}

.member-badge:hover,
.member-badge:focus-within {
  border-color: rgb(209 213 219);
  background: rgb(243 244 246);
}

.member-badge--plain {
  min-height: 3.5rem;
  justify-content: center;
}

.member-badge__main {
  display: grid;
  min-width: 0;
  grid-template-columns: 1.875rem minmax(0, 1fr);
  align-items: center;
  gap: 0.625rem;
}

.member-badge__media,
.member-badge__avatar {
  display: inline-flex;
  width: 1.875rem;
  height: 1.875rem;
  flex: none;
  align-items: center;
  justify-content: center;
}

.member-badge__avatar {
  border-radius: 9999px;
  background: rgb(255 255 255);
  object-fit: cover;
  color: rgb(107 114 128);
  font-size: 0.75rem;
  font-weight: 600;
  box-shadow:
    0 0 0 1px rgb(15 23 42 / 0.08),
    0 1px 2px rgb(15 23 42 / 0.06);
}

.member-badge__avatar--placeholder {
  background: rgb(229 231 235);
}

.member-badge__content {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 0.0625rem;
}

.member-badge__title,
.member-badge__school {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.member-badge__title {
  color: rgb(17 24 39);
  font-size: 0.8125rem;
  font-weight: 500;
  line-height: 1.125rem;
}

.member-badge__school {
  color: rgb(107 114 128);
  font-size: 0.75rem;
  line-height: 1rem;
}

.member-badge__status-dock {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  justify-content: flex-start;
  gap: 0.4375rem;
}

.member-badge__status-pill,
.member-badge__meta {
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

.member-badge__status-dot {
  width: 0.375rem;
  height: 0.375rem;
  flex: none;
  border-radius: 9999px;
  background: rgb(107 114 128);
}

.member-badge__status-label,
.member-badge__meta {
  overflow: hidden;
  text-overflow: ellipsis;
}

.member-badge__status-pill--success .member-badge__status-dot { background: rgb(22 163 74); }
.member-badge__status-pill--warning .member-badge__status-dot { background: rgb(202 138 4); }
.member-badge__status-pill--danger .member-badge__status-dot { background: rgb(220 38 38); }
.member-badge__status-pill--muted .member-badge__status-dot { background: rgb(148 163 184); }
</style>
