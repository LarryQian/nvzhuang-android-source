<script lang="ts" setup>
import { computed, ref } from 'vue'
import { onReachBottom } from '@dcloudio/uni-app'
import type { PoseItem } from '@/service/poseLibrary'
import {
  getPoseCategories,
  getPoseGroups,
  POSE_ALL_KEY,
  POSE_ITEMS,
  poseThumbUrl,
} from '@/service/poseLibrary'
import { useTryonStore } from '@/store/tryon'
import { showToast } from '@/utils/platform'

defineOptions({
  name: 'PosesPage',
})

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '选择姿势参考',
  },
})

const tryonStore = useTryonStore()

const groupTabs = getPoseGroups()
const categoryTabs = getPoseCategories()

const activeGroup = ref(POSE_ALL_KEY)
const activeCategory = ref(POSE_ALL_KEY)

/** 1439 张缩略图不一次性挂到 DOM，滚动到底再追加 */
const PAGE_STEP = 60
const visibleCount = ref(PAGE_STEP)

const filteredPoses = computed(() => {
  return POSE_ITEMS.filter((item) => {
    if (activeGroup.value !== POSE_ALL_KEY && String(item.g) !== activeGroup.value)
      return false
    if (activeCategory.value !== POSE_ALL_KEY && String(item.c) !== activeCategory.value)
      return false
    return true
  })
})

const visiblePoses = computed(() => filteredPoses.value.slice(0, visibleCount.value))

const hasMore = computed(() => visibleCount.value < filteredPoses.value.length)

const selectedPoseId = computed(() => tryonStore.draft.poseId)

function handleGroupClick(key: string) {
  if (activeGroup.value === key)
    return
  activeGroup.value = key
  visibleCount.value = PAGE_STEP
}

function handleCategoryClick(key: string) {
  if (activeCategory.value === key)
    return
  activeCategory.value = key
  visibleCount.value = PAGE_STEP
}

function handleSelectPose(item: PoseItem) {
  tryonStore.setPose(item.id, poseThumbUrl(item.f), item.t)
  showToast(`已选择姿势：${item.t}`)
  setTimeout(() => {
    uni.navigateBack({
      fail: () => {
        uni.switchTab({ url: '/pages/tryon/index' })
      },
    })
  }, 350)
}

function handleBack() {
  uni.navigateBack({
    fail: () => {
      uni.switchTab({ url: '/pages/tryon/index' })
    },
  })
}

onReachBottom(() => {
  if (hasMore.value) {
    visibleCount.value += PAGE_STEP
  }
})
</script>

<template>
  <view class="poses-page">
    <view class="status-bar-placeholder pt-safe" />

    <view class="nav-bar">
      <view class="nav-btn" @tap="handleBack">
        <view class="i-carbon-chevron-left text-18px" />
      </view>
      <text class="nav-title">选择姿势参考</text>
      <view class="w-68rpx" />
    </view>

    <view class="page-body">
      <!-- 体系分组 -->
      <scroll-view scroll-x class="tabs-scroll" :show-scrollbar="false">
        <view class="tabs-row">
          <view
            v-for="tab in groupTabs"
            :key="tab.key"
            class="tab-chip"
            :class="{ active: activeGroup === tab.key }"
            @tap="handleGroupClick(tab.key)"
          >
            {{ tab.name }}
          </view>
        </view>
      </scroll-view>

      <!-- 分类标签 -->
      <scroll-view scroll-x class="tabs-scroll" :show-scrollbar="false">
        <view class="tabs-row">
          <view
            v-for="tab in categoryTabs"
            :key="tab.key"
            class="tab-chip small"
            :class="{ active: activeCategory === tab.key }"
            @tap="handleCategoryClick(tab.key)"
          >
            {{ tab.name }}
          </view>
        </view>
      </scroll-view>

      <view class="hint-text">
        姿势参考为可选第三张图，选中后出图会严格贴合该姿态与构图；不选则按原流程出图。
      </view>

      <!-- 姿势卡片网格 -->
      <view class="poses-grid">
        <view
          v-for="item in visiblePoses"
          :key="item.id"
          class="pose-card"
          :class="{ selected: selectedPoseId === item.id }"
          @tap="handleSelectPose(item)"
        >
          <image
            :src="poseThumbUrl(item.f)"
            mode="aspectFill"
            class="pose-img"
            lazy-load
          />
          <view class="pose-info">
            <text class="pose-name">{{ item.t }}</text>
          </view>
          <view v-if="selectedPoseId === item.id" class="pose-check">
            <view class="i-carbon-checkmark text-11px" />
          </view>
        </view>
      </view>

      <view v-if="!filteredPoses.length" class="py-10 text-center text-11px text-#bba">
        该组合下暂无姿势，换个分组试试
      </view>

      <view v-else-if="hasMore" class="py-8 text-center text-11px text-#bba">
        继续下滑查看更多姿势（{{ visiblePoses.length }} / {{ filteredPoses.length }}）
      </view>

      <view v-else class="py-8 text-center text-11px text-#bba">
        已展示全部 {{ filteredPoses.length }} 个姿势
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.poses-page {
  min-height: 100vh;
  background-color: #fcfbfa;
  color: #272225;
}

.nav-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 32rpx;
  height: 88rpx;
}

.nav-btn {
  width: 68rpx;
  height: 68rpx;
  border-radius: 50%;
  background: #ffffff;
  border: 1px solid #eae5e4;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nav-title {
  font-size: 28rpx;
  font-weight: 700;
}

.page-body {
  padding: 16rpx 32rpx 40rpx;
}

.tabs-scroll {
  width: 100%;
  white-space: nowrap;
  margin-bottom: 14rpx;
}

.tabs-row {
  display: inline-flex;
  gap: 14rpx;
  padding-bottom: 4rpx;
}

.tab-chip {
  padding: 12rpx 26rpx;
  border-radius: 40rpx;
  font-size: 20rpx;
  background: #f4f1f0;
  color: #7a7072;
  font-weight: 600;
  white-space: nowrap;

  &.small {
    font-size: 19rpx;
    padding: 9rpx 22rpx;
  }

  &.active {
    background: #cb7788;
    color: #ffffff;
  }
}

.hint-text {
  font-size: 20rpx;
  color: #9c8e90;
  margin: 12rpx 0 24rpx;
  line-height: 1.5;
}

.poses-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14rpx;
}

.pose-card {
  position: relative;
  background: #ffffff;
  border-radius: 20rpx;
  overflow: hidden;
  border: 2rpx solid #f0e6e7;
  cursor: pointer;

  &:active {
    transform: scale(0.98);
  }

  &.selected {
    border-color: #cb7788;
  }
}

.pose-img {
  width: 100%;
  height: 240rpx;
  object-fit: cover;
  display: block;
  background: #f6efe9;
}

.pose-info {
  padding: 10rpx 12rpx 12rpx;
}

.pose-name {
  font-size: 18rpx;
  font-weight: 600;
  color: #4d4448;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.pose-check {
  position: absolute;
  top: 8rpx;
  right: 8rpx;
  width: 34rpx;
  height: 34rpx;
  border-radius: 50%;
  background: #cb7788;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
