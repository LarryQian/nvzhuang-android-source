import { useSettingsStore } from '@/store/settings'

/**
 * GRSAI 直连（仅 APK 壳内可用）
 *
 * 壳里注册了 window.NativeGrsai，由 Java 侧完成 HTTPS 请求与图片读取，
 * 页面只负责给参数、等回调 —— 这样绕开 WebView 的 CORS 与本地文件限制。
 * 没有桥（H5 / 小程序 / 浏览器）时返回 false，页面走原来的后端流程。
 */

export interface GrsaiTryonResult {
  url: string
  remoteUrl?: string
  model?: string
}

interface Pending {
  resolve: (value: GrsaiTryonResult) => void
  reject: (err: Error) => void
  timer: ReturnType<typeof setTimeout>
}

const pending = new Map<string, Pending>()

const TIMEOUT_MS = 300000

function installCallback() {
  if (typeof window === 'undefined')
    return
  ;(window as any).__onGrsaiResult = (id: string, status: string, payload: string) => {
    const entry = pending.get(id)
    if (!entry)
      return
    pending.delete(id)
    clearTimeout(entry.timer)
    if (status === 'ok') {
      try {
        entry.resolve(JSON.parse(payload) as GrsaiTryonResult)
      }
      catch {
        entry.resolve({ url: payload })
      }
      return
    }
    entry.reject(new Error(payload || 'GRSAI 生成失败'))
  }
}

installCallback()

export function isGrsaiDirectAvailable(): boolean {
  return typeof window !== 'undefined' && Boolean((window as any).NativeGrsai)
}

let cachedPicked = ''
let cachedPickedFor = ''

function pullPickedImage(): string {
  try {
    const bridge = (window as any).NativeGrsai
    if (bridge && typeof bridge.takePickedImage === 'function')
      return String(bridge.takePickedImage() || '')
  }
  catch (e) {
    console.warn('takePickedImage failed', e)
  }
  return ''
}

function loadImageElement(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('图片解码失败'))
    img.src = url
  })
}

async function canvasToDataUrl(url: string): Promise<string | null> {
  try {
    const img = await loadImageElement(url)
    const width = img.naturalWidth || img.width
    const height = img.naturalHeight || img.height
    if (!width || !height)
      return null
    const scale = Math.min(1, 1600 / Math.max(width, height))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(width * scale))
    canvas.height = Math.max(1, Math.round(height * scale))
    const ctx = canvas.getContext('2d')
    if (!ctx)
      return null
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
    return canvas.toDataURL('image/jpeg', 0.88)
  }
  catch (e) {
    console.warn('canvasToDataUrl failed', e)
    return null
  }
}

/** 把「你的照片」存到 App 私有目录，返回 App 重启后仍然有效的地址（blob: 会随页面销毁失效） */
export function persistPersonImage(dataUrl?: string | null): string {
  if (!dataUrl || !dataUrl.startsWith('data:'))
    return ''
  try {
    const bridge = (window as any).NativeGrsai
    if (bridge && typeof bridge.savePersonImage === 'function')
      return String(bridge.savePersonImage(dataUrl) || '')
  }
  catch (e) {
    console.warn('persistPersonImage failed', e)
  }
  return ''
}

/** 历史记录里可能是已经失效的 blob: 地址，遇到就当没有 */
export function usableImageUrl(url?: string | null): string | null {
  if (!url)
    return null
  if (url.startsWith('blob:'))
    return null
  return url
}

/** 把任意参考图转成 data URL；http(s) 原样交给原生侧解析（优先读 APK 内镜像） */
export async function toReferenceUrl(url?: string | null): Promise<string | null> {
  if (!url)
    return null
  if (url.startsWith('data:'))
    return url
  if (/^https?:\/\//.test(url))
    return url

  // 本地相册照片：优先用原生直接解码的那张（同一张图重试时用缓存，避免二次调用拿不到）
  const fresh = pullPickedImage()
  if (fresh) {
    cachedPicked = fresh
    cachedPickedFor = url
    return fresh
  }
  if (cachedPicked && cachedPickedFor === url)
    return cachedPicked

  // 退路 1：fetch → FileReader
  try {
    const res = await fetch(url)
    const blob = await res.blob()
    if (blob && blob.size > 0) {
      const viaReader = await new Promise<string | null>((resolve) => {
        const reader = new FileReader()
        reader.onload = () => resolve(String(reader.result))
        reader.onerror = () => resolve(null)
        reader.readAsDataURL(blob)
      })
      if (viaReader && viaReader.length > 32)
        return viaReader
    }
  }
  catch (e) {
    console.warn('toReferenceUrl fetch failed, falling back to canvas', e)
  }

  // 退路 2：<img> + canvas
  return await canvasToDataUrl(url)
}

export function generateTryonWithGrsai(options: {
  personUrl: string
  garmentUrl: string
  /** 可选第三张：姿势参考图，按 [人像, 服装, 姿势] 顺序传给原生 */
  poseUrl?: string
  prompt: string
  aspectRatio?: string
}): Promise<GrsaiTryonResult> {
  const bridge = (window as any).NativeGrsai
  if (!bridge)
    return Promise.reject(new Error('当前环境不支持直连模式'))

  const settings = useSettingsStore()
  const apiKey = (settings.clientConfig?.apiKey || '').trim()
  const configured = (settings.clientConfig?.model || '').trim()
  // 只接受 GRSAI 认识的模型名，避免把 Gemini / 豆包的模型名发给它
  const model = /^(gpt-image|nano-banana)/.test(configured) ? configured : 'gpt-image-2.6'
  const requestId = `g${Date.now()}${Math.random().toString(36).slice(2, 7)}`

  const payload = {
    // 空字符串 = 用 APK 内置令牌；填了 sk- 开头的 Key 则优先
    key: apiKey.startsWith('sk-') ? apiKey : '',
    model,
    prompt: options.prompt,
    aspectRatio: options.aspectRatio || '1024x1536',
    images: [options.personUrl, options.garmentUrl, options.poseUrl].filter(Boolean),
  }

  return new Promise<GrsaiTryonResult>((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(requestId)
      reject(new Error('生成超时，请稍后重试'))
    }, TIMEOUT_MS)
    pending.set(requestId, { resolve, reject, timer })
    try {
      bridge.generate(requestId, JSON.stringify(payload))
    }
    catch (e: any) {
      pending.delete(requestId)
      clearTimeout(timer)
      reject(new Error(e?.message || '调用原生接口失败'))
    }
  })
}
