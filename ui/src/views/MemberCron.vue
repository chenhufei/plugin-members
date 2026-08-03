<script lang="ts" setup>
import { membersCoreApiClient } from "@/api";
import type { CronMemberSubmit } from "@/types";
import { formatDatetime } from "@/utils/date";
import {
  Toast,
  VAlert,
  VDescription,
  VDescriptionItem,
  VLoading,
} from "@halo-dev/components";
import { useMutation, useQuery } from "@tanstack/vue-query";
import { computed, ref } from "vue";

const TASK_NAME = "cron-member-submit-default";

function createDefaultTask(): CronMemberSubmit {
  return {
    apiVersion: "member.plugin.halo.run/v1alpha1",
    kind: "CronMemberSubmit",
    metadata: { name: TASK_NAME },
    spec: {
      cron: "@daily",
      enabled: false,
      cleanupRejected: true,
      rejectedRetentionDays: 30,
      cleanupPending: false,
      pendingRetentionDays: 90,
    },
  };
}

const formState = ref<CronMemberSubmit>(createDefaultTask());
const taskExists = computed(() => Boolean(formState.value.metadata.creationTimestamp));

const cronOptions = [
  { label: "每月（每月 1 日 0 点）", value: "@monthly" },
  { label: "每周（每周一 0 点）", value: "@weekly" },
  { label: "每天（每天 0 点）", value: "@daily" },
  { label: "每小时", value: "@hourly" },
];

const { isLoading } = useQuery({
  queryKey: ["plugin:members:cron-submit"],
  queryFn: async () => {
    const { data } = await membersCoreApiClient.cronMemberSubmit.list({ page: 1, size: 100 });
    return data.items.find((item) => item.metadata.name === TASK_NAME);
  },
  onSuccess(task) {
    formState.value = task || createDefaultTask();
  },
});

const { mutate: save, isLoading: isSaving } = useMutation({
  mutationKey: ["plugin:members:cron-submit:save"],
  mutationFn: async () => {
    if (taskExists.value) {
      return membersCoreApiClient.cronMemberSubmit.update(TASK_NAME, formState.value);
    }
    return membersCoreApiClient.cronMemberSubmit.create(formState.value);
  },
  onSuccess(response) {
    formState.value = response.data;
    Toast.success("定时任务已保存");
  },
  onError() {
    Toast.error("定时任务保存失败，请稍后重试");
  },
});

function formatStatusTime(value?: string) {
  return value ? formatDatetime(value) : "尚未执行";
}
</script>

<template>
  <div class=":uno: space-y-4 p-4">
    <VAlert
      type="info"
      title="仅清理过期申请记录"
      description="定时任务默认关闭，且永远不会删除已通过成员。清理长期待审核申请需要单独开启。"
      :closable="false"
    />

    <div v-if="isLoading" class=":uno: py-12">
      <VLoading />
    </div>

    <FormKit
      v-else
      id="member-cron-setting"
      type="form"
      :actions="false"
      @submit="save"
    >
      <FormKit
        v-model="formState.spec.enabled"
        type="switch"
        name="enabled"
        label="启用定时任务"
      />
      <FormKit
        v-model="formState.spec.cron"
        type="select"
        name="cron"
        label="执行周期"
        searchable
        allow-create
        validation="required"
        :options="cronOptions"
        help="可选择常用周期，也可填写 Spring Cron 表达式"
      />
      <FormKit
        v-model="formState.spec.cleanupRejected"
        type="switch"
        name="cleanupRejected"
        label="清理已拒绝申请"
      />
      <FormKit
        v-if="formState.spec.cleanupRejected"
        v-model="formState.spec.rejectedRetentionDays"
        type="number"
        name="rejectedRetentionDays"
        label="已拒绝申请保留天数"
        :min="1"
        :max="3650"
        validation="required|min:1|max:3650"
      />
      <FormKit
        v-model="formState.spec.cleanupPending"
        type="switch"
        name="cleanupPending"
        label="清理长期待审核申请"
        help="开启后，超过保留期仍未处理的申请将被删除"
      />
      <FormKit
        v-if="formState.spec.cleanupPending"
        v-model="formState.spec.pendingRetentionDays"
        type="number"
        name="pendingRetentionDays"
        label="待审核申请保留天数"
        :min="1"
        :max="3650"
        validation="required|min:1|max:3650"
      />
      <div v-permission="['plugin:members:manage']" class=":uno: border-t border-gray-100 pt-4">
        <FormKit
          type="submit"
          :label="isSaving ? '保存中...' : '保存'"
          :disabled="isLoading || isSaving"
        />
      </div>
    </FormKit>

    <div v-if="formState.status" class=":uno: rounded border border-gray-100 p-4">
      <h3 class=":uno: mb-3 text-sm text-gray-900 font-medium">运行状态</h3>
      <VDescription>
        <VDescriptionItem label="上次执行">
          {{ formatStatusTime(formState.status.lastScheduledTimestamp) }}
        </VDescriptionItem>
        <VDescriptionItem label="下次执行">
          {{ formState.status.nextSchedulingTimestamp ? formatDatetime(formState.status.nextSchedulingTimestamp) : "未计划" }}
        </VDescriptionItem>
        <VDescriptionItem label="上次结果">
          {{ formState.status.lastMessage || "暂无记录" }}
        </VDescriptionItem>
      </VDescription>
    </div>
  </div>
</template>
