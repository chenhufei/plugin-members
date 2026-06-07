<script lang="ts" setup>
import type { GroupFormState } from "@/types";
import { nextTick, ref } from "vue";

const props = defineProps<{
  name?: string;
  formState?: GroupFormState;
}>();

const emit = defineEmits<{
  (event: "submit", data: GroupFormState): void;
}>();

const annotationsForm = ref();

async function onSubmit(data: GroupFormState) {
  annotationsForm.value?.handleSubmit();
  await nextTick();

  const { customAnnotations, annotations, customFormInvalid, specFormInvalid } = annotationsForm.value || {};
  if (customFormInvalid || specFormInvalid) {
    return;
  }

  emit("submit", {
    ...data,
    annotations: {
      ...annotations,
      ...customAnnotations,
    },
  });
}
</script>
<template>
  <FormKit
    id="group-form"
    name="group-form"
    type="form"
    :config="{ validationVisibility: 'submit' }"
    @submit="onSubmit"
  >
    <div class=":uno: md:grid md:grid-cols-4 md:gap-6">
      <div class=":uno: md:col-span-1">
        <div class=":uno: sticky top-0">
          <span class=":uno: text-base text-gray-900 font-medium"> 常规 </span>
        </div>
      </div>
      <div class=":uno: mt-5 md:col-span-3 md:mt-0 divide-y divide-gray-100">
        <FormKit
          type="text"
          name="displayName"
          :value="formState?.displayName"
          validation="required"
          label="分组名称"
        />
        <FormKit
          type="textarea"
          name="description"
          :value="formState?.description"
          label="描述"
          rows="3"
        />
        <FormKit
          type="number"
          name="priority"
          :value="formState?.priority"
          label="优先级"
        />
      </div>
    </div>
  </FormKit>

  <div class=":uno: py-5">
    <div class=":uno: border-t border-gray-200"></div>
  </div>

  <div class=":uno: md:grid md:grid-cols-4 md:gap-6">
    <div class=":uno: md:col-span-1">
      <div class=":uno: sticky top-0">
        <span class=":uno: text-base text-gray-900 font-medium"> 元数据 </span>
      </div>
    </div>
    <div class=":uno: mt-5 md:col-span-3 md:mt-0 divide-y divide-gray-100">
      <AnnotationsForm
        :key="name"
        ref="annotationsForm"
        :value="props.formState?.annotations || {}"
        kind="MemberGroup"
        group="member.plugin.halo.run"
      />
    </div>
  </div>
</template>
