<script lang="ts" setup>
import { useMemberGroupFetch } from "@/composables/use-group-fetch";
import type { MemberFormState } from "@/types";
import { Toast, VButton } from "@halo-dev/components";
import jsQR from "jsqr";
import { nextTick, onMounted, ref, shallowRef, toRaw, useTemplateRef } from "vue";

const props = withDefaults(defineProps<{
  name?: string;
  formState?: MemberFormState;
  showStatus?: boolean;
}>(), {
  showStatus: true,
});

const emit = defineEmits<{
  (event: "submit", data: MemberFormState): void;
}>();

const { groups } = useMemberGroupFetch();

const data = ref<MemberFormState>({
  displayName: "",
  email: "",
  school: "",
  qq: "",
  avatar: "",
  background: "",
  qqFriendLink: "",
  groupName: "",
  status: "PENDING",
  joinedAt: "",
});

const qrCodeProcessing = shallowRef(false);
const qrInput = useTemplateRef<HTMLInputElement>("qrInput");

function pad(value: number) {
  return String(value).padStart(2, "0");
}

function isoToLocalInput(value?: string) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function localInputToIso(value?: string) {
  if (!value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : date.toISOString();
}

onMounted(() => {
  if (props.formState) {
    data.value = {
      ...toRaw(props.formState),
      joinedAt: isoToLocalInput(props.formState.joinedAt),
    };
  }
});

// FormKit types for props.formState value binding: https://formkit.com/essentials/inputs#value-vs-initial
// When editing, form-state is passed as initial value; FormKit reads the initial value
// from the prop and the :value binding is the mechanism

const annotationsForm = ref();

// QQ validation
function validateQQ(qq: string) {
  return /^\d{5,12}$/.test(qq);
}

function handleQRCodeUpload(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  qrCodeProcessing.value = true;
  const url = URL.createObjectURL(file);
  const img = new Image();
  img.onload = () => {
    const canvas = document.createElement("canvas");
    const maxDimension = 1600;
    const scale = Math.min(1, maxDimension / Math.max(img.width, img.height));
    canvas.width = Math.max(1, Math.round(img.width * scale));
    canvas.height = Math.max(1, Math.round(img.height * scale));
    const ctx = canvas.getContext("2d");
    if (!ctx) { qrCodeProcessing.value = false; return; }
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const code = jsQR(imageData.data, imageData.width, imageData.height);
    if (code?.data) {
      const match = code.data.trim().match(/^https:\/\/qm\.qq\.com\/q\/[^\s]+$/i);
      if (match) {
        data.value.qqFriendLink = match[0];
        Toast.success("二维码解析成功！");
      } else {
        Toast.warning("不是 QQ 加好友链接（需为 https://qm.qq.com/q/ 开头）");
      }
    } else {
      Toast.error("无法识别二维码");
    }
    qrCodeProcessing.value = false;
    URL.revokeObjectURL(url);
  };
  img.onerror = () => {
    Toast.error("图片加载失败");
    qrCodeProcessing.value = false;
    URL.revokeObjectURL(url);
  };
  img.src = url;
  (e.target as HTMLInputElement).value = "";
}

async function onSubmit() {
  annotationsForm.value?.handleSubmit();
  await nextTick();
  const { customAnnotations, annotations, customFormInvalid, specFormInvalid } = annotationsForm.value || {};
  if (customFormInvalid || specFormInvalid) return;

  if (!data.value.displayName.trim()) { Toast.error("请填写账号名称"); return; }
  if (!data.value.school.trim()) { Toast.error("请填写所属学校"); return; }
  if (!validateQQ(data.value.qq)) { Toast.error("请填写 5-12 位 QQ号"); return; }
  const friendLink = data.value.qqFriendLink.trim();
  if (friendLink && !/^https:\/\/qm\.qq\.com\/q\/[^\s]+$/i.test(friendLink)) {
    Toast.error("QQ 加好友链接需为 https://qm.qq.com/q/ 开头的链接");
    return;
  }

  emit("submit", {
    ...data.value,
    qqFriendLink: friendLink,
    joinedAt: localInputToIso(data.value.joinedAt),
    annotations: { ...annotations, ...customAnnotations },
  });
}
</script>

<template>
  <FormKit id="member-form" name="member-form" type="form" :config="{ validationVisibility: 'submit' }" @submit="onSubmit">
    <div class=":uno: md:grid md:grid-cols-4 md:gap-6">
      <div class=":uno: md:col-span-1">
        <div class=":uno: sticky top-0">
          <span class=":uno: text-base text-gray-900 font-medium"> 常规 </span>
        </div>
      </div>
      <div class=":uno: mt-5 md:col-span-3 md:mt-0 divide-y divide-gray-100">
        <FormKit type="text" name="qq" v-model="data.qq" label="QQ号" validation="required" />

        <FormKit type="text" name="displayName" v-model="data.displayName" validation="required" label="账号名称" help="例如：清华大学表白墙" />
        <FormKit type="text" name="school" v-model="data.school" validation="required" label="所属学校" help="例如：清华大学" />
        <FormKit type="datetime-local" name="joinedAt" v-model="data.joinedAt" label="加入时间" help="用于成员排序与加入时间展示；留空则使用创建时间" />
        <FormKit type="text" name="qqFriendLink" v-model="data.qqFriendLink" label="QQ加好友链接" help="需为 https://qm.qq.com/q/ 开头的链接，可通过上传二维码自动获取" />
        <div class=":uno: py-2">
          <input type="file" accept="image/*" style="display:none" ref="qrInput" @change="handleQRCodeUpload" />
          <VButton size="sm" type="secondary" :loading="qrCodeProcessing" @click="qrInput?.click()">
            上传二维码
          </VButton>
        </div>

        <FormKit type="email" name="email" v-model="data.email" label="邮箱" help="用于审核通知" />
        <FormKit type="attachment" name="avatar" v-model="data.avatar" label="头像" />
        <FormKit type="attachment" name="background" v-model="data.background" label="背景图" help="成员卡片背景图片" />
        <FormKit type="select" name="groupName" v-model="data.groupName" label="所属分组"
          :options="[
            { label: '无分组', value: '' },
            ...groups.map((g) => ({ label: g.spec.displayName, value: g.metadata.name }))
          ]"
        />
        <FormKit v-if="showStatus" type="select" name="status" v-model="data.status" label="状态"
          :options="[
            { label: '待审核', value: 'PENDING' },
            { label: '已通过', value: 'APPROVED' },
            { label: '已拒绝', value: 'REJECTED' }
          ]"
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
        :value="formState?.annotations || {}"
        kind="Member"
        group="member.plugin.halo.run"
      />
    </div>
  </div>
</template>
