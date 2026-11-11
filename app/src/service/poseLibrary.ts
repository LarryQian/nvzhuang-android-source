/**
 * 姿势参考库（数据来自 pose-gallery 的 pose-zh.json 精简版）。
 *
 * poses.json 结构：
 *   v      版本号
 *   base   缩略图相对目录（poses/）
 *   aspect 默认宽高比
 *   groups 体系名数组，poses[].g 是它的下标
 *   cats   分类名数组，poses[].c 是它的下标
 *   poses  { id, g, c, t 中文标签, f 缩略图文件名, a? 宽高比 }
 *
 * 缩略图 URL 必须按约定拼成 pages 站点的 assets 路径，
 * 原生拦截器会把 .../nvzhuang/assets/<tail> 映射到 APK 内 assets/<tail>，离线也能取到图。
 */

import posesJson from '@/static/data/poses.json'

export interface PoseItem {
  id: string
  g: number
  c: number
  t: string
  f: string
  a?: string
}

export interface PoseData {
  v: number
  base: string
  aspect: string
  groups: string[]
  cats: string[]
  poses: PoseItem[]
}

export interface PoseFilterOption {
  key: string
  name: string
  count: number
}

/** 缩略图线上地址前缀（离线镜像命中后不走网络） */
export const POSE_ASSET_BASE = 'https://paipiange.github.io/nvzhuang/assets/'

export const POSE_DATA = posesJson as unknown as PoseData

export const POSE_ITEMS: PoseItem[] = POSE_DATA.poses

export const POSE_ALL_KEY = 'all'

/** 把缩略图文件名拼成可被原生拦截器命中的完整地址 */
export function poseThumbUrl(fileName: string): string {
  return `${POSE_ASSET_BASE}${POSE_DATA.base}${fileName}`
}

/** 姿势的宽高比，缺省用全局默认值 */
export function poseAspect(item: PoseItem): string {
  return item.a || POSE_DATA.aspect
}

function buildOptions(labels: string[], indexOf: (item: PoseItem) => number): PoseFilterOption[] {
  const counts = new Map<number, number>()
  for (const item of POSE_ITEMS) {
    const idx = indexOf(item)
    counts.set(idx, (counts.get(idx) || 0) + 1)
  }
  return labels
    .map((name, idx) => ({ key: String(idx), name, count: counts.get(idx) || 0 }))
    .filter(option => option.count > 0)
}

/** 体系分组（全部 + 各体系） */
export function getPoseGroups(): PoseFilterOption[] {
  return [
    { key: POSE_ALL_KEY, name: '全部', count: POSE_ITEMS.length },
    ...buildOptions(POSE_DATA.groups, item => item.g),
  ]
}

/** 分类标签（按条目数排序，双人/特殊等长尾分类放后面） */
export function getPoseCategories(): PoseFilterOption[] {
  return [
    { key: POSE_ALL_KEY, name: '全部分类', count: POSE_ITEMS.length },
    ...buildOptions(POSE_DATA.cats, item => item.c).sort((a, b) => b.count - a.count),
  ]
}
