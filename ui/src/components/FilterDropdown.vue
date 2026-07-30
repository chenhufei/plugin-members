<script lang="ts" setup generic="T">
import { computed } from "vue";

interface Props {
  modelValue?: T;
  label?: string;
  items?: Array<{ label: string; value: T }>;
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: undefined,
  label: "",
  items: () => [],
});

const emit = defineEmits<{
  "update:modelValue": [value: T];
}>();

const selected = computed({
  get: () => props.modelValue,
  set: (val) => emit("update:modelValue", val),
});
</script>

<template>
  <div class="inline-flex items-center gap-1">
    <span class="text-sm text-gray-500">{{ label }}:</span>
    <select
      :value="selected"
      class="rounded border border-gray-300 px-2 py-1 text-sm focus:border-blue-500 focus:outline-none"
      @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value as T)"
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
