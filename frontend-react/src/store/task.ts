/** 全局任务进度：上传等后台任务切页面后仍要看到进度，因此放在全局 store 而非页面 state */
import { create } from 'zustand'

export interface TaskProgress {
  type: 'info' | 'ok' | 'err'
  text: string
  running: boolean
}

interface TaskState {
  upload: TaskProgress | null
  setUpload: (p: TaskProgress | null) => void
  clearUpload: () => void
}

export const useTaskStore = create<TaskState>((set) => ({
  upload: null,
  setUpload: (upload) => set({ upload }),
  clearUpload: () => set({ upload: null })
}))
