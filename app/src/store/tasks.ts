import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { generateTryonWithGrsai } from '@/service/grsaiDirect'
import { buildTryonPrompt } from '@/service/grsaiPrompt'
import { useTryonStore } from '@/store/tryon'

/**
 * 生图任务队列。
 *
 * 提交后立刻返回，任务在后台串行跑（GRSAI 单并发更稳），页面可以继续提交下一个。
 * 任务记录落在 localStorage（只存小体积的地址，不存 base64），App 重启后：
 * - 排队中的任务继续跑；
 * - 跑到一半被打断的标记为失败（原生那条请求已经没了）。
 */

export type GenTaskStatus = 'queued' | 'running' | 'success' | 'failed'

export interface GenTask {
  id: string
  status: GenTaskStatus
  /** 服装是否来自二次元库：为真时提示词要把真人照片整体转成二次元插画 */
  anime: boolean
  /** 是否带姿势参考图 */
  pose: boolean
  /** 旧记录兼容字段，新任务不再写 */
  promptKind?: 'normal' | 'anime' | 'pose'
  title: string
  personUrl: string
  garmentUrl: string
  poseUrl?: string
  personThumb: string
  garmentThumb: string
  poseThumb?: string
  model: string
  resultUrl?: string
  jobId?: string
  error?: string
  createdAt: number
  startedAt?: number
  finishedAt?: number
}

const STORAGE_KEY = 'nvzhuang:tasks:v1'
const MAX_TASKS = 60

function promptFor(task: Pick<GenTask, 'anime' | 'pose'>): string {
  return buildTryonPrompt({ anime: task.anime, pose: task.pose })
}

export const useTaskStore = defineStore('tasks', () => {
  const tasks = ref<GenTask[]>([])
  const pumping = ref(false)

  const queuedCount = computed(() => tasks.value.filter(t => t.status === 'queued').length)
  const runningCount = computed(() => tasks.value.filter(t => t.status === 'running').length)
  const activeCount = computed(() => queuedCount.value + runningCount.value)
  const list = computed(() => [...tasks.value].reverse())

  function persist() {
    try {
      uni.setStorageSync(STORAGE_KEY, tasks.value)
    }
    catch (e) {
      console.warn('persist tasks failed', e)
    }
  }

  function init() {
    try {
      const stored = uni.getStorageSync(STORAGE_KEY)
      const value = Array.isArray(stored) ? stored : (typeof stored === 'string' && stored ? JSON.parse(stored) : [])
      tasks.value = (value as GenTask[]).map((t) => {
        // 旧记录只有 promptKind，迁移成 anime / pose 两个标记
        const legacyAnime = t.promptKind === 'anime'
        const legacyPose = t.promptKind === 'pose' || Boolean(t.poseUrl)
        const anime = typeof t.anime === 'boolean' ? t.anime : legacyAnime
        const pose = typeof t.pose === 'boolean' ? t.pose : legacyPose
        // 上次进程里正在跑的任务，重启后原生请求已经断了
        if (t.status === 'running')
          return { ...t, anime, pose, status: 'failed', error: '应用已重启，任务中断', finishedAt: Date.now() } as GenTask
        return { ...t, anime, pose }
      })
    }
    catch (e) {
      console.warn('init tasks failed', e)
      tasks.value = []
    }
  }

  function enqueue(input: {
    personUrl: string
    garmentUrl: string
    poseUrl?: string
    anime: boolean
    title: string
    personThumb: string
    garmentThumb: string
    poseThumb?: string
  }): GenTask {
    const task: GenTask = {
      id: `t${Date.now()}${Math.random().toString(36).slice(2, 6)}`,
      status: 'queued',
      anime: Boolean(input.anime),
      pose: Boolean(input.poseUrl),
      title: input.title || 'AI 换装',
      personUrl: input.personUrl,
      garmentUrl: input.garmentUrl,
      poseUrl: input.poseUrl,
      personThumb: input.personThumb,
      garmentThumb: input.garmentThumb,
      poseThumb: input.poseThumb,
      model: 'gpt-image-2.6',
      createdAt: Date.now(),
    }
    tasks.value.push(task)
    if (tasks.value.length > MAX_TASKS)
      tasks.value = tasks.value.slice(-MAX_TASKS)
    persist()
    void pump()
    return task
  }

  async function pump() {
    if (pumping.value)
      return
    pumping.value = true
    try {
      for (;;) {
        const next = tasks.value.find(t => t.status === 'queued')
        if (!next)
          break
        next.status = 'running'
        next.startedAt = Date.now()
        persist()
        try {
          const result = await generateTryonWithGrsai({
            personUrl: next.personUrl,
            garmentUrl: next.garmentUrl,
            poseUrl: next.poseUrl,
            prompt: promptFor(next),
            aspectRatio: '1024x1536',
          })
          next.status = 'success'
          next.resultUrl = result.url
          next.model = result.model || next.model
          next.jobId = `grsai-${next.id}`

          const tryonStore = useTryonStore()
          tryonStore.addHistory({
            id: next.jobId,
            client_request_id: next.id,
            status: 'success',
            garment_id: null,
            model: next.model,
            result_url: result.url,
            garment_image_url: next.garmentThumb,
            person_image_url: next.personThumb,
            pose_image_url: next.poseThumb,
            created_at: new Date(next.createdAt).toISOString(),
            completed_at: new Date().toISOString(),
          } as any)
        }
        catch (e: any) {
          next.status = 'failed'
          next.error = e?.message || '生成失败'
        }
        finally {
          next.finishedAt = Date.now()
          persist()
        }
      }
    }
    finally {
      pumping.value = false
    }
  }

  function retry(id: string) {
    const task = tasks.value.find(t => t.id === id)
    if (!task || task.status === 'running' || task.status === 'queued')
      return
    task.status = 'queued'
    task.error = undefined
    task.resultUrl = undefined
    task.jobId = undefined
    task.startedAt = undefined
    task.finishedAt = undefined
    persist()
    void pump()
  }

  function remove(id: string) {
    tasks.value = tasks.value.filter(t => t.id !== id)
    persist()
  }

  function clearFinished() {
    tasks.value = tasks.value.filter(t => t.status === 'queued' || t.status === 'running')
    persist()
  }

  function cancelQueued(id: string) {
    const task = tasks.value.find(t => t.id === id)
    if (!task || task.status !== 'queued')
      return
    task.status = 'failed'
    task.error = '已取消'
    task.finishedAt = Date.now()
    persist()
  }

  init()
  // 重启后：排队中的继续跑
  if (tasks.value.some(t => t.status === 'queued'))
    void pump()

  return {
    tasks,
    list,
    queuedCount,
    runningCount,
    activeCount,
    enqueue,
    retry,
    remove,
    clearFinished,
    cancelQueued,
    pump,
  }
})
