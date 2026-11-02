<script lang="ts" setup>
import MemberCron from "@/views/MemberCron.vue";
import MemberSubmitList from "@/views/MemberSubmitList.vue";
import MemberWithdrawReview from "@/views/MemberWithdrawReview.vue";
import {
  IconArrowLeft,
  IconExternalLinkLine,
  VButton,
  VCard,
  VPageHeader,
  VTabbar,
} from "@halo-dev/components";
import { useRouteQuery } from "@vueuse/router";
import { shallowRef } from "vue";
import { useRouter } from "vue-router";

const router = useRouter();

const tabs = shallowRef([
  { id: "submits", label: "提交记录" },
  { id: "withdrawals", label: "撤回记录" },
  { id: "cron", label: "定时任务" },
]);
const activeTab = useRouteQuery<string>("tab", tabs.value[0].id);

function openFrontend() {
  window.open("/members", "_blank", "noopener,noreferrer");
}
</script>

<template>
  <VPageHeader title="成员自助提交管理">
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
    <MemberWithdrawReview v-else-if="activeTab === 'withdrawals'" />
    <VCard v-else-if="activeTab === 'cron'" :body-class="[':uno: !p-0']">
      <MemberCron />
    </VCard>
  </div>
</template>
