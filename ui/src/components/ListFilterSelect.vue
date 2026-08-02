<script lang="ts" setup>
import { computed } from "vue";

interface ListFilterOption {
  label: string;
  value?: string;
}

const props = withDefaults(
  defineProps<{
    modelValue?: string;
    label: string;
    items?: ListFilterOption[];
  }>(),
  {
    modelValue: undefined,
    items: () => [],
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: string | undefined];
}>();

const selected = computed({
  get: () => props.modelValue ?? "",
  set: (value: string) => emit("update:modelValue", value || undefined),
});

const options = computed(() =>
  props.items.map((item) => ({
    label: item.label,
    value: item.value ?? "",
  })),
);
</script>

<template>
  <div class=":uno: min-w-30 list-filter-select">
    <FormKit
      v-model="selected"
      type="select"
      :name="`list-filter-${label}`"
      :label="label"
      :options="options"
      :clearable="false"
    />
  </div>
</template>

<style scoped>
.list-filter-select :deep(.formkit-outer) {
  margin-bottom: 0;
}

.list-filter-select :deep(.formkit-label) {
  margin-bottom: 0.25rem;
  color: rgb(107 114 128);
  font-size: 0.75rem;
  line-height: 1rem;
}
</style>
