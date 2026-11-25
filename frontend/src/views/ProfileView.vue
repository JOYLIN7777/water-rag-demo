<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api'

const router = useRouter()
const auth = useAuthStore()
const info = ref<Record<string, any>>({
  user: auth.user?.user || '—',
  displayName: auth.user?.displayName || '—',
  tenant: auth.user?.tenant || '—',
  roles: (auth.user?.groups || []).join('、'),
  authType: '账号密码'
})

async function load() {
  // 权限接口带完整用户信息；也兼容后端额外字段
  await auth.loadPermissions()
  const u = auth.user
  if (u) info.value = { ...info.value, user: u.user, displayName: u.displayName, tenant: u.tenant, roles: u.groups.join('、') }
}
onMounted(load)

async function doLogout() {
  await auth.reset()
  await router.push('/login.html')
}
</script>

<template>
  <div class="page">
    <div class="page-head"><h1 class="page-title">个人中心</h1><p class="page-desc">查看账号信息与登录状态。</p></div>
    <div class="card" style="max-width:900px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="登录账号">{{ info.user }}</el-descriptions-item>
        <el-descriptions-item label="显示名称">{{ info.displayName }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ info.roles }}</el-descriptions-item>
        <el-descriptions-item label="所属组织">{{ info.tenant }}</el-descriptions-item>
        <el-descriptions-item label="登录方式">{{ info.authType }}</el-descriptions-item>
      </el-descriptions>
      <div style="margin-top:20px;display:flex;gap:10px">
        <el-button @click="doLogout">退出登录</el-button>
      </div>
    </div>
  </div>
</template>
