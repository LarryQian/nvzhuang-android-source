import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/**
 * 姿势收藏。和服装收藏（store/favorites.ts）分开存，id 空间不同：
 * 服装是 `real:SPR-0001` / `anime:SPR-0001`，姿势是 poses.json 里的 pose id。
 */

const STORAGE_KEY = 'nvzhuang:pose-favorites:v1'

export const usePoseFavoritesStore = defineStore('poseFavorites', () => {
  const favoriteIds = ref<string[]>([])

  const init = () => {
    try {
      const stored = uni.getStorageSync(STORAGE_KEY)
      if (Array.isArray(stored))
        favoriteIds.value = stored
      else if (typeof stored === 'string' && stored)
        favoriteIds.value = JSON.parse(stored)
    }
    catch (e) {
      console.warn('Failed to load pose favorites', e)
    }
  }

  const persist = () => {
    try {
      uni.setStorageSync(STORAGE_KEY, favoriteIds.value)
    }
    catch (e) {
      console.warn('Failed to persist pose favorites', e)
    }
  }

  const isFavorite = (id: string) => favoriteIds.value.includes(id)

  const toggleFavorite = (id: string) => {
    const idx = favoriteIds.value.indexOf(id)
    let added = false
    if (idx > -1) {
      favoriteIds.value.splice(idx, 1)
    }
    else {
      favoriteIds.value.unshift(id)
      added = true
    }
    persist()
    return added
  }

  const clearFavorites = () => {
    favoriteIds.value = []
    persist()
  }

  const count = computed(() => favoriteIds.value.length)

  init()

  return {
    favoriteIds,
    count,
    isFavorite,
    toggleFavorite,
    clearFavorites,
    init,
  }
})
