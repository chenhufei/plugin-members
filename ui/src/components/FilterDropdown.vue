<script lang="ts" setup generic="T extends string | number | undefined">
import { computed } from "vue";

export interface FilterDropdownProps<T extends string | number | undefined = string> {
  modelValue?: T;
  label?: string;
  items?: Array<{ label: string; value: T }>;
}

const props = withDefaults(defineProps<FilterDropdownProps<T>>(), {
  modelValue: undefined,
  label: "",
  items: () => [],
});

const emit = defineEmits<{
  "update:modelValue": [value: T | undefined];
}>();

const selected = computed({
  get: () => props.modelValue,
  set: (val) => {
    if (val !== undefined) emit("update:modelValue", val);
  },
});
</script>

<template>
  <div class="inline-flex items-center gap-1">
    <span class="text-sm text-gray-500">{{ label }}:</span>
    <select
      :value="selected"
      class="rounded border border-gray-300 px-2 py-1 text-sm focus:border-blue-500 focus:outline-none"
      @change="emit('update:modelValue', (($event.target as HTMLSelectElement).value || undefined) as T | undefined)"
    >
      <option value="" default>全部</option>
      <option
        v-for="item in items"
        :key="item.value"
        :value="item.value"
      >
        {{ item.label }}
      </option>
    </select>
  </div>
</template>
