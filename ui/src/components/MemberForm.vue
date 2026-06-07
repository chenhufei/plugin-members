<script lang="ts" setup>
import { useMemberGroupFetch } from "@/composables/use-group-fetch";
import type { MemberFormState } from "@/types";
import { Toast, VButton } from "@halo-dev/components";
import jsQR from "jsqr";
import { nextTick, onMounted, ref, shallowRef, toRaw, useTemplateRef } from "vue";

const props = defineProps<{
  name?: string;
  formState?: MemberFormState;
}>();

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
  qqFriendLink: "",
  groupName: "",
  status: "PENDING",
  priority: 0,
});

const qqNicknameLoading = shallowRef(false);
const qrCodeProcessing = shallowRef(false);
const userModified = ref({ displayName: false, email: false, avatar: false });
const qrInput = useTemplateRef<HTMLInputElement>("qrInput");

onMounted(() => {
  if (props.formState) {
    data.value = { ...toRaw(props.formState) };
    // 编辑模式标记所有字段为已修改
    userModified.value = { displayName: true, email: true, avatar: true };
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

async function fetchQQNickname(qq: string) {
  const apis = [
    `https://wiki.kikiw.cn/qq.php?qq=${qq}`,
    `https://api.vvhan.com/api/qq.info?qq=${qq}`,
  ];
  for (const url of apis) {
    try {
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), 3000);
      const res = await fetch(url, { signal: ctrl.signal, cache: "no-cache" });
      clearTimeout(t);
      if (!res.ok) continue;
      const body = await res.json();
      const nick = body.data?.nick || body.nick || body.nickname || body.name || body.data?.nickname;
      if (nick) return nick.trim();
    } catch {
      continue;
    }
  }
  return null;
}

async function handleFetchQQInfo() {
  if (!validateQQ(data.value.qq)) return;
  qqNicknameLoading.value = true;
  try {
    if (!userModified.value.avatar) data.value.avatar = `https://q1.qlogo.cn/g?b=qq&nk=${data.value.qq}&s=640`;
    if (!userModified.value.email && (!data.value.email || data.value.email.endsWith("@qq.com")))
      data.value.email = `${data.value.qq}@qq.com`;
    const nick = await fetchQQNickname(data.value.qq);
    if (nick && !userModified.value.displayName && (!data.value.displayName || data.value.displayName.startsWith("QQ用户"))) {
      data.value.displayName = nick;
      Toast.success(`已获取QQ昵称：${nick}`);
    }
  } finally {
    qqNicknameLoading.value = false;
  }
}

function handleQRCodeUpload(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  qrCodeProcessing.value = true;
  const url = URL.createObjectURL(file);
  const img = new Image();
  img.onload = () => {
    const canvas = document.createElement("canvas");
    canvas.width = img.width; canvas.height = img.height;
    const ctx = canvas.getContext("2d");
    if (!ctx) { qrCodeProcessing.value = false; return; }
    ctx.drawImage(img, 0, 0);
    const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const code = jsQR(imageData.data, imageData.width, imageData.height);
    if (code?.data) {
      if (code.data.includes("qm.qq.com") || code.data.includes("qq.com") || code.data.includes("tencent://")) {
        data.value.qqFriendLink = code.data;
        Toast.success("二维码解析成功！");
      } else {
        const match = code.data.match(/(\d{5,12})/);
        if (match) {
          data.value.qqFriendLink = `https://qm.qq.com/cgi-bin/qm/qr?k=${match[1]}`;
          Toast.success(`识别到QQ号：${match[1]}，已生成链接`);
        } else {
          Toast.warning("不是QQ加好友链接");
        }
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

  emit("submit", {
    ...data.value,
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
        <FormKit type="text" name="qq" v-model="data.qq" label="QQ号" validation="required" help="填写后点按钮获取昵称和头像" />
        <div class=":uno: py-2">
          <VButton size="sm" type="secondary" :loading="qqNicknameLoading" @click="handleFetchQQInfo">
            获取QQ信息
          </VButton>
        </div>

        <FormKit type="text" name="displayName" v-model="data.displayName" validation="required" label="账号名称" help="例如：清华大学表白墙" @input="() => userModified.displayName = true" />
        <FormKit type="text" name="school" v-model="data.school" validation="required" label="所属学校" help="例如：清华大学" />
        <FormKit type="text" name="qqFriendLink" v-model="data.qqFriendLink" label="QQ加好友链接" help="可通过上传二维码自动获取" />
        <div class=":uno: py-2">
          <input type="file" accept="image/*" style="display:none" ref="qrInput" @change="handleQRCodeUpload" />
          <VButton size="sm" type="secondary" :loading="qrCodeProcessing" @click="qrInput?.click()">
            上传二维码
          </VButton>
        </div>

        <FormKit type="email" name="email" v-model="data.email" label="邮箱" help="用于审核通知" @input="() => userModified.email = true" />
        <FormKit type="attachment" name="avatar" v-model="data.avatar" label="头像" @input="() => userModified.avatar = true" />
        <FormKit type="select" name="groupName" v-model="data.groupName" label="所属分组"
          :options="[
            { label: '无分组', value: '' },
            ...groups.map((g) => ({ label: g.spec.displayName, value: g.metadata.name }))
          ]"
        />
        <FormKit type="select" name="status" v-model="data.status" label="状态"
          :options="[
            { label: '待审核', value: 'PENDING' },
            { label: '已通过', value: 'APPROVED' },
            { label: '已拒绝', value: 'REJECTED' }
          ]"
        />
        <FormKit type="number" name="priority" v-model="data.priority" label="优先级" />
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
