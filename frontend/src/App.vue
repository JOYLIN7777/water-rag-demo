<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const isLogin = computed(() => route.name === 'login')
</script>

<template>
  <div v-if="isLogin" class="auth-shell"><RouterView /></div>
  <RouterView v-else />
</template>
