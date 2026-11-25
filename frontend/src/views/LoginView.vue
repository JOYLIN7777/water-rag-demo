<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { encryptPassword, saveSession } from '@/api/auth'
import { authApi } from '@/api'

const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const remember = ref(false)
const showPw = ref(false)
const busy = ref(false)
const error = ref('')

try {
  const saved = localStorage.getItem('wrk_remember_user')
  if (saved) { username.value = saved; remember.value = true }
} catch { /* 隐私模式 */ }

async function submit() {
  if (!username.value || !password.value) { error.value = '请输入用户名和密码'; return }
  busy.value = true; error.value = ''
  try {
    const enc = await encryptPassword(password.value)
    const j = await authApi.login(username.value, enc, true, enc) as any
    saveSession(j)
    try {
      if (remember.value) localStorage.setItem('wrk_remember_user', username.value)
      else localStorage.removeItem('wrk_remember_user')
    } catch { /* 同上 */ }
    await auth.loadPermissions()
    router.replace(auth.landingPage())
  } catch (e: any) {
    error.value = e.message || '登录失败，请稍后重试'
  } finally { busy.value = false }
}
</script>

<template>
  <div class="login-card">
    <div class="login-brand">
      <div class="brand-mark">◈</div>
      <div><h1>水利知识库</h1><p>RAG 智能问答系统</p></div>
    </div>
    <form @submit.prevent="submit">
      <div class="field"><label>用户名</label><el-input v-model="username" placeholder="请输入用户名" autocomplete="username" /></div>
      <div class="field">
        <label>密码</label>
        <el-input v-model="password" :type="showPw ? 'text' : 'password'" placeholder="请输入密码" autocomplete="current-password">
          <template #append><el-button @click="showPw = !showPw">{{ showPw ? '隐藏' : '显示' }}</el-button></template>
        </el-input>
      </div>
      <div class="row"><el-checkbox v-model="remember">记住用户名</el-checkbox></div>
      <div v-if="error" class="error">{{ error }}</div>
      <el-button type="primary" native-type="submit" :loading="busy" style="width:100%">登 录</el-button>
    </form>
    <div class="demo">
      <b>演示账号（点击填入）</b>
      <div v-for="d in [{u:'admin',p:'Admin@123456',n:'系统管理员'},{u:'analyst',p:'Analyst@123456',n:'水利分析员'}]" :key="d.u">
        <code>{{ d.u }} / {{ d.p }}</code><span>{{ d.n }}</span>
        <el-button link size="small" @click="username=d.u; password=d.p">填入</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-card { width: 420px; max-width: 92vw; background: #fff; border-radius: 16px; box-shadow: var(--shadow-md); padding: 34px; }
.login-brand { display: flex; align-items: center; gap: 12px; margin-bottom: 26px; }
.login-brand h1 { font-size: 20px; margin: 0; }
.login-brand p { font-size: 12px; color: var(--muted); margin: 3px 0 0; }
.field { margin-bottom: 16px; }
.field label { display: block; font-size: 12px; color: var(--muted); margin-bottom: 6px; }
.row { display: flex; justify-content: space-between; margin-bottom: 14px; }
.error { color: var(--danger); font-size: 12.5px; margin-bottom: 12px; }
.demo { margin-top: 24px; padding-top: 18px; border-top: 1px dashed var(--line); font-size: 12px; }
.demo > div { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.demo code { flex: 1; }
</style>
