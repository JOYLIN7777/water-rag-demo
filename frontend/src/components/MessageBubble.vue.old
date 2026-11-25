<script setup>
import { ref, computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import SourceCard from './SourceCard.vue'

const props = defineProps({
  role: { type: String, required: true },      // user | assistant
  content: { type: String, default: '' },
  sources: { type: Array, default: () => [] },
  streaming: { type: Boolean, default: false }
})

const sourcesOpen = ref(false)
const isUser = computed(() => props.role === 'user')

// 模型输出经marked转HTML后用DOMPurify消毒，防止恶意文档内容在页面注入脚本
const rendered = computed(() => DOMPurify.sanitize(marked.parse(props.content || '')))
</script>

<template>
  <div class="message-row" :class="{ 'is-user': isUser }">
    <div class="avatar" :class="isUser ? 'avatar-user' : 'avatar-ai'">
      {{ isUser ? '我' : 'AI' }}
    </div>
    <div class="message-body">
      <div class="message-sender">{{ isUser ? '你' : '水利知识库助手' }}</div>

      <SourceCard
        v-if="!isUser"
        :sources="sources"
        :open="sourcesOpen"
        @toggle="sourcesOpen = !sourcesOpen"
      />

      <div class="message-bubble" :class="{ 'bubble-user': isUser, 'bubble-ai': !isUser }">
        <!-- 用户消息按纯文本显示，避免输入内容被当作 Markdown 解析 -->
        <template v-if="isUser">{{ content }}</template>
        <!-- 助手消息渲染 Markdown -->
        <div v-else class="md-body" v-html="rendered"></div>
        <span v-if="streaming" class="cursor"></span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.message-row { display: flex; gap: 12px; margin-bottom: 22px; align-items: flex-start; }
.message-row.is-user { flex-direction: row-reverse; }
.avatar {
  width: 34px; height: 34px; border-radius: 9px; flex: 0 0 34px;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600;
}
.avatar-user { background: #dbeafe; color: #1d4ed8; }
.avatar-ai { background: #2563eb; color: #fff; }
.message-body { max-width: min(800px, 82%); min-width: 0; }
.message-sender { font-size: 12px; color: #6b7280; margin: 0 0 6px 2px; }
.is-user .message-sender { text-align: right; margin-right: 2px; }
.message-bubble {
  padding: 12px 16px; border-radius: 12px; font-size: 14px;
  box-shadow: 0 1px 2px rgba(0,0,0,.04); white-space: pre-wrap;
}
.bubble-user { background: #2563eb; color: #fff; border-bottom-right-radius: 3px; }
.bubble-ai { background: #fff; color: #1f2937; border: 1px solid #e5e7eb; border-bottom-left-radius: 3px; }
.cursor {
  display: inline-block; width: 7px; height: 15px; background: #2563eb;
  vertical-align: text-bottom; margin-left: 2px; animation: blink 1s steps(2) infinite;
}
@keyframes blink { 50% { opacity: 0; } }
</style>
