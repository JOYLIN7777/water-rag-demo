/**
 * 后端接口封装。
 * 关键点：SSE 走 POST（后端 @PostMapping），浏览器原生 EventSource 只支持 GET，
 * 所以用 fetch + ReadableStream 手动解析 SSE 事件块（event/data 行，空行分隔）。
 */

const BASE = ''

/** 上传文档，返回 {fileName, chunks, chars} */
export async function uploadDocument(file, onProgress) {
  const form = new FormData()
  form.append('file', file)
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${BASE}/api/docs/upload`)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(JSON.parse(xhr.responseText))
      } else {
        let msg = '上传失败'
        try { msg = JSON.parse(xhr.responseText).message || msg } catch { /* 非 JSON 响应 */ }
        reject(new Error(msg))
      }
    }
    xhr.onerror = () => reject(new Error('网络异常，请检查后端是否已启动'))
    xhr.send(form)
  })
}

/** 查询后端支持的文档格式 */
export async function fetchFormats() {
  const res = await fetch(`${BASE}/api/docs/formats`)
  if (!res.ok) throw new Error('获取格式列表失败')
  return res.json()
}

/**
 * 流式 RAG 问答。
 * @param {string} sessionId 会话 ID
 * @param {string} question 问题
 * @param {(event: string, data: string) => void} onEvent 事件回调
 *        event 取值：sources（引用片段 JSON）| message（回答文本增量）| done（结束）
 */
export async function streamChat(sessionId, question, onEvent) {
  const res = await fetch(`${BASE}/api/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, question })
  })
  if (!res.ok) throw new Error(`请求失败（HTTP ${res.status}）`)
  if (!res.body) throw new Error('浏览器不支持流式响应')

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // SSE 事件以空行（\n\n）分隔，兼容 \r\n
    buffer = buffer.replace(/\r\n/g, '\n')
    let splitIndex
    while ((splitIndex = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, splitIndex)
      buffer = buffer.slice(splitIndex + 2)
      let event = 'message'
      const dataLines = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      if (dataLines.length) onEvent(event, dataLines.join('\n'))
    }
  }
}

/** 读取会话历史 */
export async function fetchHistory(sessionId) {
  const res = await fetch(`${BASE}/api/chat/history?sessionId=${encodeURIComponent(sessionId)}`)
  if (!res.ok) throw new Error('读取历史失败')
  return res.json()
}

/** 清空会话历史 */
export async function clearHistory(sessionId) {
  const res = await fetch(`${BASE}/api/chat/history?sessionId=${encodeURIComponent(sessionId)}`, {
    method: 'DELETE'
  })
  if (!res.ok) throw new Error('清空历史失败')
  return res.json()
}

/** 工具直调（演示用） */
export async function callWaterTool(station) {
  const res = await fetch(`${BASE}/api/chat/tool/water?station=${encodeURIComponent(station)}`, {
    method: 'POST'
  })
  if (!res.ok) throw new Error('工具调用失败')
  return res.text()
}
