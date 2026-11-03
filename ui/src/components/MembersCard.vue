<script lang="ts" setup>
import { membersBatchApiClient, membersCoreApiClient } from "@/api";
import { invalidateMemberViews } from "@/api/query-keys";
import { useMemberGroupFetch } from "@/composables/use-group-fetch";
import type { GroupWithMembers } from "@/utils/group-members";
import type { Member, MemberGroup } from "@/types";
import {
  Dialog,
  IconArrowLeft,
  IconArrowRight,
  IconMore,
  Toast,
  VButton,
  VCard,
  VDropdown,
  VDropdownItem,
  VSpace,
} from "@halo-dev/components";
import { useQueryClient } from "@tanstack/vue-query";
import { computed, defineAsyncComponent, ref } from "vue";
import MemberBadge from "./MemberBadge.vue";

const GroupEditingModal = defineAsyncComponent(() => import("./GroupEditingModal.vue"));
const MemberCreationModal = defineAsyncComponent(() => import("./MemberCreationModal.vue"));
const MemberEditingModal = defineAsyncComponent(() => import("./MemberEditingModal.vue"));

const props = defineProps<{
  groupWithMembers: GroupWithMembers;
}>();

const group = computed(() => props.groupWithMembers.group);
const members = computed(() => props.groupWithMembers.members);

const queryClient = useQueryClient();
const { groups } = useMemberGroupFetch();
const otherGroups = computed(() => groups.value?.filter((i: MemberGroup) => i.metadata.name !== group.value?.metadata.name));

const creationModalVisible = ref(false);
const groupEditingModalVisible = ref(false);
const enabledSelect = ref(false);
const selectedMemberNames = ref<string[]>([]);
const selectedMember = ref<Member | undefined>();
const memberEditingModalVisible = ref(false);

function handleOpenEdit(member: Member) {
  selectedMember.value = member;
  memberEditingModalVisible.value = true;
}

function handleSelectPrevious() {
  if (!members.value.length) return;
  const idx = members.value.findIndex((m) => m.metadata.name === selectedMember.value?.metadata.name);
  selectedMember.value = idx > 0 ? members.value[idx - 1] : undefined;
}

function handleSelectNext() {
  if (!members.value.length) return;
  if (!selectedMember.value) { selectedMember.value = members.value[0]; return; }
  const idx = members.value.findIndex((m) => m.metadata.name === selectedMember.value?.metadata.name);
  if (idx < members.value.length - 1) selectedMember.value = members.value[idx + 1];
}

function handleEditingModalClose() {
  memberEditingModalVisible.value = false;
  selectedMember.value = undefined;
}

function handleSelectAll() {
  selectedMemberNames.value = members.value.map((m) => m.metadata.name);
}

function handleDeleteInBatch() {
  Dialog.warning({
    title: "是否确认删除选中的成员？",
    description: "删除之后将无法恢复。",
    confirmType: "danger",
    onConfirm: async () => {
      try {
        // 逐条 await 删除：慢、不原子，中途失败时前面的已经删掉，后面的没动，
        // 而异常又被 Dialog 吞掉，用户只看到按钮转一圈就恢复原样。
        const { data } = await membersBatchApiClient.member.batchDelete(
          selectedMemberNames.value
        );
        if (data.failed > 0) {
          Toast.warning(`已删除 ${data.success} 位，失败 ${data.failed} 位`);
        } else {
          Toast.success(`已删除 ${data.success} 位成员`);
        }
        void invalidateMemberViews(queryClient);
        enabledSelect.value = false;
        selectedMemberNames.value.length = 0;
      } catch (error: any) {
        Toast.error(error.response?.data?.message || error.message || "删除失败");
      }
    },
  });
}

function handleMoveToGroup(targetGroup: MemberGroup) {
  Dialog.warning({
    title: "移动到分组",
    description: `确认将选中的成员移动到${targetGroup.spec?.displayName}分组吗？`,
    confirmType: "danger",
    onConfirm: async () => {
      try {
        const { data } = await membersBatchApiClient.member.batchChangeGroup(
          selectedMemberNames.value,
          targetGroup.metadata.name
        );
        if (data.failed > 0) {
          Toast.warning(`已移动 ${data.success} 位，失败 ${data.failed} 位`);
        } else {
          Toast.success(`已移动 ${data.success} 位成员`);
        }
        void invalidateMemberViews(queryClient);
        enabledSelect.value = false;
        selectedMemberNames.value.length = 0;
      } catch (error: any) {
        Toast.error(error.response?.data?.message || error.message || "移动失败");
      }
    },
  });
}

function handleDeleteGroup({ deleteMembers }: { deleteMembers: boolean }) {
  const title = deleteMembers ? "删除分组及成员" : "仅删除分组";
  const description = deleteMembers
    ? "将同时删除该分组下所有成员，此操作不可恢复。"
    : "该分组下的成员将变为未分组，此操作不可恢复。";

  Dialog.warning({
    title,
    description,
    confirmType: "danger",
    onConfirm: async () => {
      if (!group.value) return;
      try {
        if (deleteMembers) {
          const names = members.value.map((member) => member.metadata.name);
          if (names.length > 0) {
            await membersBatchApiClient.member.batchDelete(names);
          }
        }
        await membersCoreApiClient.memberGroup.delete(group.value.metadata.name);
        Toast.success("删除成功");
        void invalidateMemberViews(queryClient);
      } catch (error: any) {
        Toast.error(error.response?.data?.message || error.message || "删除失败");
      }
    },
  });
}
</script>

<template>
  <VCard>
    <template #header>
      <div class=":uno: w-full flex flex-wrap items-center justify-between gap-2 px-4 py-2">
        <div class=":uno: flex flex-wrap items-center gap-3">
          <div class=":uno: text-sm text-gray-900 font-semibold">
            {{ group?.spec?.displayName || "未分组" }}（{{ members.length }}）
          </div>
          <!-- 批量选择模式 -->
          <VSpace v-if="enabledSelect" v-permission="['plugin:members:manage']" class=":uno: flex-wrap">
            <VButton size="sm" @click="handleSelectAll">全选</VButton>
            <VButton size="sm" @click="selectedMemberNames.length = 0">清空</VButton>
            <VDropdown>
              <VButton size="sm" :disabled="selectedMemberNames.length === 0 || !otherGroups?.length">移动</VButton>
              <template #popper>
                <VDropdownItem
                  @click="handleMoveToGroup(item)"
                  v-for="item in otherGroups"
                  :key="item.metadata.name"
                  :value="item.metadata.name"
                >
                  {{ item.spec?.displayName }}
                </VDropdownItem>
              </template>
            </VDropdown>
            <VButton size="sm" type="danger" :disabled="selectedMemberNames.length === 0" @click="handleDeleteInBatch">删除</VButton>
            <VButton size="sm" @click="enabledSelect = false">取消</VButton>
          </VSpace>
          <!-- 普通模式 -->
          <VSpace v-else v-permission="['plugin:members:manage']" class=":uno: flex-wrap">
            <VDropdown v-if="members.length || group">
              <VButton size="sm" ghost aria-label="打开分组操作"><IconMore /></VButton>
              <template #popper>
                <VDropdownItem v-if="members.length" @click="enabledSelect = true">批量选择</VDropdownItem>
                <VDropdownItem v-if="group" @click="groupEditingModalVisible = true">编辑分组</VDropdownItem>
                <VDropdown>
                  <VDropdownItem v-if="group" type="danger">删除分组</VDropdownItem>
                  <template #popper>
                    <VDropdownItem v-if="group" type="danger" @click="handleDeleteGroup({ deleteMembers: false })">仅删除分组</VDropdownItem>
                    <VDropdownItem v-if="group" type="danger" @click="handleDeleteGroup({ deleteMembers: true })">删除分组及成员</VDropdownItem>
                  </template>
                </VDropdown>
              </template>
            </VDropdown>
          </VSpace>
        </div>
        <div>
          <VButton v-permission="['plugin:members:manage']" type="secondary" size="sm" @click="creationModalVisible = true">新建</VButton>
        </div>
      </div>
    </template>
    <div v-if="!members.length" class=":uno: px-4 py-3 text-center text-sm text-gray-500">此分组下暂无成员</div>
    <div
      class=":uno: grid grid-cols-1 gap-2.5 2xl:grid-cols-5 3xl:grid-cols-6 4xl:grid-cols-7 5xl:grid-cols-8 lg:grid-cols-3 sm:grid-cols-2 xl:grid-cols-4"
      v-else
    >
      <MemberBadge
        v-for="member in members"
        :key="member.metadata.name"
        :member="member"
        :select-mode="enabledSelect"
        @open-detail="handleOpenEdit(member)"
      >
        <template #checkbox>
          <input type="checkbox" v-model="selectedMemberNames" :value="member.metadata.name" />
        </template>
      </MemberBadge>
    </div>
  </VCard>

  <MemberCreationModal v-if="creationModalVisible" :group="group" @close="creationModalVisible = false" />
  <GroupEditingModal v-if="groupEditingModalVisible && group" :group="group" @close="groupEditingModalVisible = false" />
  <MemberEditingModal v-if="memberEditingModalVisible && selectedMember" :member="selectedMember" @close="handleEditingModalClose">
    <template #append-actions>
      <span @click="handleSelectPrevious"><IconArrowLeft /></span>
      <span @click="handleSelectNext"><IconArrowRight /></span>
    </template>
  </MemberEditingModal>
</template>
