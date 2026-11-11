<script lang="ts" setup>
import { computed, onUnmounted, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import type { GenTask } from '@/store/tasks'
import { useTaskStore } from '@/store/tasks'
import { showToast } from '@/utils/platform'

defineOptions({
  name: 'TasksPage',
})

definePage({
  style: {
    navigationStyle: 'custom',
    navigationBarTitleText: '生成任务',
  },
})

const taskStore = useTaskStore()

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null

onShow(() => {
  // 让「进行中」的耗时每秒刷新
  if (timer)
    clearInterval(timer)
  timer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})

const filter = ref<'all' | 'active' | 'success' | 'failed'>('all')

const filtered = computed<GenTask[]>(() => {
  const all = taskStore.list
  if (filter.value === 'all')
    return all
  if (filter.value === 'active')
    return all.filter(t => t.status === 'queued' || t.status === 'running')
  if (filter.value === 'success')
    return all.filter(t => t.status === 'success')
  return all.filter(t => t.status === 'failed')
})

const statusText: Record<GenTask['status'], string> = {
  queued: '排队中',
  running: '进行中',
  success: '已完成',
  failed: '失败',
}

const statusClass: Record<GenTask['status'], string> = {
  queued: 'chip-queued',
  running: 'chip-running',
  success: 'chip-success',
  failed: 'chip-failed',
}

function elapsed(task: GenTask): string {
  const start = task.startedAt || task.createdAt
  const end = task.finishedAt || now.value
  const seconds = Math.max(0, Math.round((end - start) / 1000))
  if (seconds < 60)
    return `${seconds}s`
  return `${Math.floor(seconds / 60)}m${String(seconds % 60).padStart(2, '0')}s`
}

function queueIndex(task: GenTask): number {
  const queued = taskStore.tasks.filter(t => t.status === 'queued')
  const idx = queued.findIndex(t => t.id === task.id)
  return idx < 0 ? 0 : idx + 1
}

function timeText(task: GenTask): string {
  const date = new Date(task.createdAt)
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function handleBack() {
  uni.navigateBack({
    fail: () => {
      uni.switchTab({ url: '/pages/tryon/index' })
    },
  })
}

function openResult(task: GenTask) {
  if (task.jobId) {
    uni.navigateTo({ url: `/pages/tryon/result?id=${encodeURIComponent(task.jobId)}` })
    return
  }
  if (task.resultUrl) {
    uni.previewImage({ urls: [task.resultUrl] })
  }
}

function handleRetry(task: GenTask) {
  taskStore.retry(task.id)
  showToast('已重新加入队列')
}

function handleCancel(task: GenTask) {
  taskStore.cancelQueued(task.id)
  showToast('已取消')
}

function handleRemove(task: GenTask) {
  taskStore.remove(task.id)
}

function handleClearFinished() {
  taskStore.clearFinished()
  showToast('已清理结束的任务')
}

function handleGoTryon() {
  uni.switchTab({ url: '/pages/tryon/index' })
}
</script>

<template>
  <view class="tasks-page">
    <view class="status-bar-placeholder pt-safe" />

    <view class="nav-bar">
      <view class="nav-btn" @tap="handleBack">
        <view class="i-carbon-chevron-left text-18px" />
      </view>
      <text class="nav-title">生成任务</text>
      <view class="nav-btn" @tap="handleClearFinished">
        <view class="i-carbon-clean text-16px text-#382e32" />
      </view>
    </view>

    <view class="summary">
      <view class="summary-item">
        <text class="summary-num">{{ taskStore.runningCount }}</text>
        <text class="summary-label">进行中</text>
      </view>
      <view class="summary-item">
        <text class="summary-num">{{ taskStore.queuedCount }}</text>
        <text class="summary-label">排队中</text>
      </view>
      <view class="summary-item">
        <text class="summary-num">{{ taskStore.tasks.length }}</text>
        <text class="summary-label">总任务</text>
      </view>
    </view>

    <view class="filter-row">
      <view
        v-for="item in [
          { key: 'all', label: '全部' },
          { key: 'active', label: '进行中' },
          { key: 'success', label: '已完成' },
          { key: 'failed', label: '失败' },
        ]"
        :key="item.key"
        class="filter-chip"
        :class="{ active: filter === item.key }"
        @tap="filter = item.key as any"
      >
        {{ item.label }}
      </view>
    </view>

    <view v-if="!filtered.length" class="empty">
      <text class="empty-title">队列里还没有任务</text>
      <text class="empty-desc">在 AI 试衣间点「开始 AI 换装」，任务会进这里排队</text>
      <view class="empty-btn" @tap="handleGoTryon">
        去试衣间
      </view>
    </view>

    <view v-else class="task-list">
      <view v-for="task in filtered" :key="task.id" class="task-card">
        <view class="task-thumb" @tap="task.status === 'success' ? openResult(task) : undefined">
          <image
            v-if="task.resultUrl || task.garmentThumb"
            :src="task.resultUrl || task.garmentThumb"
            mode="aspectFill"
            class="thumb-img"
          />
          <view v-else class="thumb-placeholder">
            <view class="i-carbon-image text-16px text-#c9bcbd" />
          </view>
          <view v-if="task.status === 'running'" class="thumb-mask">
            <text class="mask-text">{{ elapsed(task) }}</text>
          </view>
        </view>

        <view class="task-body">
          <view class="task-title-row">
            <text class="task-title">{{ task.title }}</text>
            <text class="task-chip" :class="statusClass[task.status]">{{ statusText[task.status] }}</text>
          </view>

          <text class="task-meta">
            {{ task.model }} · {{ timeText(task) }}
            <template v-if="task.status === 'success'"> · 耗时 {{ elapsed(task) }}</template>
            <template v-else-if="task.status === 'running'"> · 已用 {{ elapsed(task) }}</template>
            <template v-else-if="task.status === 'queued'"> · 队列第 {{ queueIndex(task) }} 位</template>
          </text>

          <text v-if="task.status === 'failed' && task.error" class="task-error">{{ task.error }}</text>

          <view class="task-actions">
            <view v-if="task.status === 'success'" class="action-btn primary" @tap="openResult(task)">
              查看结果
            </view>
            <view v-if="task.status === 'failed'" class="action-btn primary" @tap="handleRetry(task)">
              重试
            </view>
            <view v-if="task.status === 'queued'" class="action-btn ghost" @tap="handleCancel(task)">
              取消
            </view>
            <view
              v-if="task.status === 'success' || task.status === 'failed'"
              class="action-btn ghost"
              @tap="handleRemove(task)"
            >
              删除
            </view>
          </view>
        </view>
      </view>
    </view>

    <view class="h-24" />
  </view>
</template>

<style scoped lang="scss">
.tasks-page {
  min-height: 100vh;
  background-color: #fcfbfa;
  color: #272225;
  padding: 0 32rpx;
}

.nav-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 20rpx 0 24rpx;
}

.nav-btn {
  width: 66rpx;
  height: 66rpx;
  border-radius: 50%;
  border: 1px solid #eae5e4;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #ffffff;
}

.nav-title {
  font-size: 30rpx;
  font-weight: 800;
}

.summary {
  display: flex;
  background: #ffffff;
  border: 1px solid #f2eceb;
  border-radius: 24rpx;
  padding: 22rpx 0;
  margin-bottom: 22rpx;
}

.summary-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
}

.summary-num {
  font-size: 34rpx;
  font-weight: 800;
  color: #cd7188;
}

.summary-label {
  font-size: 18rpx;
  color: #a89b99;
}

.filter-row {
  display: flex;
  gap: 12rpx;
  margin-bottom: 22rpx;
}

.filter-chip {
  padding: 10rpx 22rpx;
  border-radius: 60rpx;
  background: #f6f1f0;
  color: #9b8f8d;
  font-size: 22rpx;
  font-weight: 600;

  &.active {
    background: #f6e5ea;
    color: #b86a7c;
  }
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  padding: 120rpx 0;
}

.empty-title {
  font-size: 26rpx;
  font-weight: 700;
}

.empty-desc {
  font-size: 20rpx;
  color: #a89b99;
  text-align: center;
}

.empty-btn {
  margin-top: 16rpx;
  background: #cd7188;
  color: #fff;
  font-size: 22rpx;
  font-weight: 700;
  padding: 16rpx 40rpx;
  border-radius: 60rpx;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 18rpx;
}

.task-card {
  display: flex;
  gap: 18rpx;
  background: #ffffff;
  border: 1px solid #f2eceb;
  border-radius: 24rpx;
  padding: 18rpx;
}

.task-thumb {
  position: relative;
  width: 112rpx;
  height: 150rpx;
  border-radius: 16rpx;
  overflow: hidden;
  background: #f7f3f2;
  flex-shrink: 0;
}

.thumb-img {
  width: 100%;
  height: 100%;
}

.thumb-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.thumb-mask {
  position: absolute;
  inset: 0;
  background: rgba(39, 34, 37, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
}

.mask-text {
  color: #fff;
  font-size: 20rpx;
  font-weight: 700;
}

.task-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.task-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
}

.task-title {
  font-size: 24rpx;
  font-weight: 700;
  color: #382e32;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-chip {
  font-size: 18rpx;
  font-weight: 700;
  padding: 4rpx 14rpx;
  border-radius: 40rpx;
}

.chip-queued {
  background: #f4f1ee;
  color: #a08d84;
}

.chip-running {
  background: #fdeef2;
  color: #c75f78;
}

.chip-success {
  background: #eaf6ee;
  color: #3f9464;
}

.chip-failed {
  background: #fdeceb;
  color: #c0503f;
}

.task-meta {
  font-size: 18rpx;
  color: #a89b99;
}

.task-error {
  font-size: 18rpx;
  color: #c0503f;
  line-height: 1.4;
}

.task-actions {
  display: flex;
  gap: 12rpx;
  margin-top: 6rpx;
}

.action-btn {
  font-size: 20rpx;
  font-weight: 700;
  padding: 10rpx 24rpx;
  border-radius: 40rpx;

  &.primary {
    background: #cd7188;
    color: #fff;
  }

  &.ghost {
    background: #f7f1f0;
    color: #9b8f8d;
  }
}

.h-24 {
  height: 48rpx;
}
</style>
