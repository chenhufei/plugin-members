<template>
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="visible" class="withdraw-modal-overlay" @click.self="close">
        <div class="withdraw-modal">
          <div class="withdraw-modal-header">
            <h3>撤回成员申请</h3>
            <button class="close-btn" @click="close">✕</button>
          </div>
          
          <div class="withdraw-modal-body">
            <!-- Step 1: 验证身份 -->
            <div v-if="step === 1" class="withdraw-step">
              <p class="withdraw-hint">
                请输入您的QQ号和申请邮箱以验证身份
              </p>
              
              <div class="form-group">
                <label>QQ号</label>
                <input
                  v-model="qq"
                  type="text"
                  placeholder="请输入您的QQ号"
                  class="form-input"
                />
              </div>
              
              <div class="form-group">
                <label>申请邮箱</label>
                <input
                  v-model="email"
                  type="email"
                  placeholder="请输入您申请时使用的邮箱"
                  class="form-input"
                />
              </div>
              
              <button
                class="btn-withdraw-submit"
                @click="verifyIdentity"
                :disabled="!qq || !email || sendingCode"
              >
                {{ sendingCode ? '发送中...' : '发送验证码' }}
              </button>
            </div>
            
            <!-- Step 2: 输入验证码 -->
            <div v-if="step === 2" class="withdraw-step">
              <p class="withdraw-hint">
                验证码已发送到 {{ email }}，请输入收到的验证码
              </p>
              
              <div class="form-group">
                <label>验证码</label>
                <input
                  v-model="code"
                  type="text"
                  placeholder="请输入6位验证码"
                  class="form-input form-code-input"
                  maxlength="6"
                />
              </div>
              
              <div class="form-group">
                <label>撤回原因（可选）</label>
                <textarea
                  v-model="reason"
                  placeholder="请简述撤回原因..."
                  class="form-textarea"
                  rows="3"
                ></textarea>
              </div>
              
              <div class="withdraw-actions">
                <button
                  class="btn-withdraw-back"
                  @click="step = 1"
                  :disabled="submitting"
                >
                  返回
                </button>
                <button
                  class="btn-withdraw-submit"
                  @click="submitWithdraw"
                  :disabled="!code || submitting"
                >
                  {{ submitting ? '提交中...' : '提交撤回' }}
                </button>
              </div>
            </div>
            
            <!-- Step 3: 结果 -->
            <div v-if="step === 3" class="withdraw-result">
              <div :class="result.success ? 'success-icon' : 'error-icon'">
                {{ result.success ? '✓' : '✕' }}
              </div>
              <h4>{{ result.title }}</h4>
              <p>{{ result.message }}</p>
              <button class="btn-withdraw-submit" @click="close">
                关闭
              </button>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref } from 'vue'

const visible = ref(false)
const step = ref(1)
const qq = ref('')
const email = ref('')
const code = ref('')
const reason = ref('')
const sendingCode = ref(false)
const submitting = ref(false)
const result = ref({
  success: false,
  title: '',
  message: ''
})

const open = () => {
  visible.value = true
  reset()
}

const close = () => {
  visible.value = false
}

const reset = () => {
  step.value = 1
  qq.value = ''
  email.value = ''
  code.value = ''
  reason.value = ''
  result.value = { success: false, title: '', message: '' }
}

// 发送验证码
const sendVerificationCode = async () => {
  const API_BASE = '/apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers'
  try {
    sendingCode.value = true
    const resp = await fetch(`${API_BASE}/membersubmits/-/send-verification-code`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: email.value, qq: qq.value })
    })
    const data = await resp.json()
    if (data.failed) {
      alert(data.message || '发送验证码失败')
      return false
    }
    return true
  } catch (err) {
    console.error('发送验证码失败:', err)
    alert('发送验证码失败，请重试')
    return false
  }
}

// 提交撤回
const submitWithdraw = async () => {
  const API_BASE = '/apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers'
  try {
    submitting.value = true
    const resp = await fetch(`${API_BASE}/membersubmits/-/withdraw`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: email.value,
        code: code.value,
        qq: qq.value,
        reason: reason.value
      })
    })
    const data = await resp.json()
    if (data.failed) {
      result.value = {
        success: false,
        title: '撤回失败',
        message: data.message || '提交撤回失败，请重试'
      }
    } else {
      result.value = {
        success: true,
        title: data.message.includes('等待管理员') ? '撤回申请已提交' : '撤回成功',
        message: data.message
      }
    }
    step.value = 3
  } catch (err) {
    console.error('提交撤回失败:', err)
    result.value = {
      success: false,
      title: '撤回失败',
      message: '提交失败，请重试'
    }
    step.value = 3
  } finally {
    submitting.value = false
  }
}

// 步骤1：验证身份并发送验证码
const verifyIdentity = async () => {
  if (!qq.value || !email.value) {
    alert('请填写QQ号和邮箱')
    return
  }
  const success = await sendVerificationCode()
  if (success) {
    step.value = 2
    code.value = ''
  }
}

// 暴露方法给父组件
defineExpose({ open })
</script>

<style scoped>
.withdraw-modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
}

.withdraw-modal {
  background: white;
  border-radius: 12px;
  width: 480px;
  max-width: 90vw;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.withdraw-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid #eee;
}

.withdraw-modal-header h3 {
  margin: 0;
  font-size: 18px;
  color: #333;
}

.close-btn {
  background: none;
  border: none;
  font-size: 20px;
  color: #999;
  cursor: pointer;
}

.withdraw-modal-body {
  padding: 24px;
}

.withdraw-hint {
  color: #666;
  font-size: 14px;
  margin-bottom: 20px;
}

.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  font-size: 14px;
  color: #333;
  margin-bottom: 6px;
  font-weight: 500;
}

.form-input,
.form-textarea {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  transition: border-color 0.2s;
  box-sizing: border-box;
}

.form-input:focus,
.form-textarea:focus {
  outline: none;
  border-color: #1890ff;
  box-shadow: 0 0 0 2px rgba(24, 144, 255, 0.1);
}

.form-code-input {
  font-size: 20px;
  letter-spacing: 8px;
  text-align: center;
}

.form-textarea {
  resize: vertical;
  font-family: inherit;
}

.withdraw-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  margin-top: 20px;
}

.btn-withdraw-submit {
  background: #1890ff;
  color: white;
  border: none;
  padding: 10px 24px;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-withdraw-submit:hover {
  background: #096dd9;
}

.btn-withdraw-submit:disabled {
  background: #b3d4ff;
  cursor: not-allowed;
}

.btn-withdraw-back {
  background: #f5f5f5;
  color: #333;
  border: 1px solid #ddd;
  padding: 10px 24px;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
}

.withdraw-result {
  text-align: center;
  padding: 20px 0;
}

.withdraw-result h4 {
  font-size: 18px;
  margin: 16px 0 8px;
}

.withdraw-result p {
  color: #666;
  margin-bottom: 24px;
}

.success-icon {
  width: 64px;
  height: 64px;
  background: #52c41a;
  color: white;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  margin: 0 auto;
}

.error-icon {
  width: 64px;
  height: 64px;
  background: #ff4d4f;
  color: white;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  margin: 0 auto;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
