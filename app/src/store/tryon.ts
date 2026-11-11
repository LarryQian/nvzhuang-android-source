import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { TryonDraft, TryonJob, TryonSize } from '@/types/tryon'
import type { Garment, ModelPortrait } from '@/types/garment'

const HISTORY_KEY = 'nvzhuang:history:v1'
const DRAFT_KEY = 'nvzhuang:tryon:draft:v1'

export const useTryonStore = defineStore('tryon', () => {
  const draft = ref<TryonDraft>({
    garmentId: null,
    garmentImageUrl: null,
    garmentTitle: null,
    personLocalPath: null,
    personPersistedUrl: null,
    personFileId: null,
    selectedPortraitId: null,
    selectedPortraitUrl: null,
    poseId: null,
    poseUrl: null,
    poseTitle: null,
    modelKey: 'gemini',
    size: '1024x1536',
    keepFace: true,
  })

  const history = ref<TryonJob[]>([])
  const currentJob = ref<TryonJob | null>(null)

  const initHistory = () => {
    try {
      const stored = uni.getStorageSync(HISTORY_KEY)
      if (Array.isArray(stored)) {
        history.value = stored
      }
      else if (typeof stored === 'string' && stored) {
        history.value = JSON.parse(stored)
      }
    }
    catch (e) {
      console.warn('Failed to load tryon history', e)
    }
  }

  const persistHistory = () => {
    try {
      uni.setStorageSync(HISTORY_KEY, history.value)
    }
    catch (e) {
      console.warn('Failed to persist tryon history', e)
    }
  }

  /** 上次选好的服装 / 姿势 / 模特要留着，下次进来还是这一套（blob 地址不持久化，只存稳定地址） */
  const persistDraft = () => {
    try {
      const d = draft.value
      uni.setStorageSync(DRAFT_KEY, {
        garmentId: d.garmentId,
        garmentImageUrl: d.garmentImageUrl,
        garmentTitle: d.garmentTitle,
        personPersistedUrl: d.personPersistedUrl || null,
        selectedPortraitId: d.selectedPortraitId,
        selectedPortraitUrl: d.selectedPortraitUrl,
        poseId: d.poseId,
        poseUrl: d.poseUrl,
        poseTitle: d.poseTitle,
        modelKey: d.modelKey,
        size: d.size,
        keepFace: d.keepFace,
      })
    }
    catch (e) {
      console.warn('Failed to persist tryon draft', e)
    }
  }

  const initDraft = () => {
    try {
      const stored = uni.getStorageSync(DRAFT_KEY)
      const value = typeof stored === 'string' ? (stored ? JSON.parse(stored) : null) : stored
      if (value && typeof value === 'object')
        Object.assign(draft.value, value)
    }
    catch (e) {
      console.warn('Failed to restore tryon draft', e)
    }
  }

  const setGarment = (garment: Garment) => {
    draft.value.garmentId = garment.id
    draft.value.garmentImageUrl = garment.image_url || garment.thumbnail_url
    draft.value.garmentTitle = garment.title
    persistDraft()
  }

  const setPortrait = (portrait: ModelPortrait) => {
    draft.value.selectedPortraitId = portrait.id
    draft.value.selectedPortraitUrl = portrait.image_url || portrait.thumbnail_url
    // 清空本地上传路径，二选一
    draft.value.personLocalPath = null
    draft.value.personPersistedUrl = null
    draft.value.personFileId = null
    persistDraft()
  }

  const setPersonPhoto = (localPath: string) => {
    draft.value.personLocalPath = localPath
    draft.value.personPersistedUrl = null
    // 清空模特选择，二选一
    draft.value.selectedPortraitId = null
    draft.value.selectedPortraitUrl = null
    persistDraft()
  }

  /** 相册图落盘后的稳定地址，重启后仍可显示/复用 */
  const setPersonPersisted = (url: string | null) => {
    draft.value.personPersistedUrl = url
    persistDraft()
  }

  const setModel = (modelKey: string) => {
    draft.value.modelKey = modelKey
    persistDraft()
  }

  /** 姿势参考是可选第三张图，选了就在直连出图时一起送过去 */
  const setPose = (id: string, url: string, title: string) => {
    draft.value.poseId = id
    draft.value.poseUrl = url
    draft.value.poseTitle = title
    persistDraft()
  }

  const clearPose = () => {
    draft.value.poseId = null
    draft.value.poseUrl = null
    draft.value.poseTitle = null
    persistDraft()
  }

  const setSize = (size: TryonSize) => {
    draft.value.size = size
    persistDraft()
  }

  const setKeepFace = (val: boolean) => {
    draft.value.keepFace = val
    persistDraft()
  }

  const clearDraft = () => {
    draft.value.personLocalPath = null
    draft.value.personFileId = null
    draft.value.selectedPortraitId = null
    draft.value.selectedPortraitUrl = null
    draft.value.poseId = null
    draft.value.poseUrl = null
    draft.value.poseTitle = null
    persistDraft()
  }

  const addHistory = (job: TryonJob) => {
    // 限制历史最多 50 条
    history.value.unshift(job)
    if (history.value.length > 50) {
      history.value = history.value.slice(0, 50)
    }
    persistHistory()
  }

  const deleteHistory = (id: string) => {
    const idx = history.value.findIndex(item => item.id === id)
    if (idx > -1) {
      history.value.splice(idx, 1)
      persistHistory()
    }
  }

  const clearHistory = () => {
    history.value = []
    persistHistory()
  }

  initHistory()
  initDraft()

  return {
    draft,
    history,
    currentJob,
    setGarment,
    setPortrait,
    setPersonPhoto,
    setPersonPersisted,
    setModel,
    setPose,
    clearPose,
    setSize,
    setKeepFace,
    clearDraft,
    persistDraft,
    addHistory,
    deleteHistory,
    clearHistory,
    initHistory,
  }
})
