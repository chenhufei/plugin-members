<script lang="ts" setup>
import MemberCron from "@/views/MemberCron.vue";
import MemberSubmitList from "@/views/MemberSubmitList.vue";
import MemberWithdrawReview from "@/views/MemberWithdrawReview.vue";
import { useUiPermissions } from "@/composables/use-ui-permissions";
import {
  IconArrowLeft,
  IconExternalLinkLine,
  VButton,
  VCard,
  VPageHeader,
  VTabbar,
} from "@halo-dev/components";
import { useRouteQuery } from "@vueuse/router";
import { computed, watchEffect } from "vue";
import { useRouter } from "vue-router";

const router = useRouter();
const { can } = useUiPermissions();

/**
 * 撤回记录走的是只有 manage 才能读的接口（console .../members/withdraw-requests）。
 * 本页路由只要求 view，所以 tab 必须按权限过滤：否则只有查看权限的账号点进去，
 * 看到的是满屏 403 报错。
 */
const allTabs = [
  { id: "submits", label: "提交记录", permission: "" },
  { id: "withdrawals", label: "撤回记录", permission: "plugin:members:manage" },
  { id: "cron", label: "定时任务", permission: "" },
];

const tabs = computed(() =>
  allTabs
    .filter((tab) => !tab.permission || can(tab.permission))
    .map(({ id, label }) => ({ id, label })),
);

const activeTab = useRouteQuery<string>("tab", allTabs[0].id);

// 直接带 ?tab=withdrawals 进来但没有 manage 时，回落到第一个可见 tab。
watchEffect(() => {
  const visible = tabs.value;
  if (!visible.length) {
    return;
  }
  if (!visible.some((tab) => tab.id === activeTab.value)) {
    activeTab.value = visible[0].id;
  }
});

function openFrontend() {
  window.open("/members", "_blank", "noopener,noreferrer");
}
</script>

<template>
  <VPageHeader title="申请记录">
    <template #actions>
      <VButton size="sm" @click="router.push('/members')">
        <template #icon><IconArrowLeft /></template>
        返回成员管理
      </VButton>
      <VButton size="sm" ghost @click="openFrontend">
        <template #icon><IconExternalLinkLine /></template>
        前台成员页
      </VButton>
    </template>
  </VPageHeader>

  <div class=":uno: m-0 space-y-4 md:m-4">
    <div class=":uno: border-b border-gray-100 bg-white px-4 py-2">
      <VTabbar v-model:active-id="activeTab" :items="tabs" class=":uno: w-full" type="outline" />
    </div>
    <VCard v-if="activeTab === 'submits'" :body-class="[':uno: !p-0']">
      <MemberSubmitList />
    </VCard>
    <MemberWithdrawReview v-else-if="activeTab === 'withdrawals' && can('plugin:members:manage')" />
    <VCard v-else-if="activeTab === 'cron'" :body-class="[':uno: !p-0']">
      <MemberCron />
    </VCard>
  </div>
</template>
