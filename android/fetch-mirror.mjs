// 把全量缩略图下载到 android/mirror/ 下，供 APK 内离线使用。
// 目录映射（与 GitHub Pages 上的路径一一对应）：
//   /nvzhuang/assets/thumbs/X.webp        -> mirror/thumbs/X.webp        （仓库里已有，直接复制）
//   /nvzhuang/assets/anime/thumbs/X.webp  -> mirror/anime/thumbs/X.webp  （下载）
//   /nvzhuang/assets/portrait/thumbs/X.webp -> mirror/portrait/thumbs/X.webp（下载）
// 全尺寸 img/ 不落地，运行时由壳回退到缩略图。
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(__dirname, '..')
const mirrorDir = path.join(rootDir, 'android/mirror')
const catalogPath = path.join(rootDir, 'backend/internal/logic/catalog/data/catalog.json')
const PAGES = 'https://paipiange.github.io/nvzhuang/assets/'

const catalog = JSON.parse(fs.readFileSync(catalogPath, 'utf-8'))
const urls = new Set()
for (const item of [...catalog.garments, ...catalog.portraits]) {
  for (const key of ['thumbnail_url', 'image_url']) {
    const u = item[key]
    if (typeof u === 'string' && u.startsWith(PAGES) && (u.includes('/thumbs/') || u.includes('/img/'))) {
      urls.add(u)
    }
  }
}

// img/ 不作为本地资源保留；只在缺少 thumbs 映射时占位
const targets = [...urls].filter(u => u.includes('/thumbs/'))
console.log(`候选缩略图: ${targets.length} 个`)

// 仓库里已有的真实服装缩略图直接复制
const repoSrc = path.join(rootDir, 'assets/thumbs')
const mirrorThumbs = path.join(mirrorDir, 'thumbs')
await fsp.mkdir(mirrorThumbs, { recursive: true })
let copied = 0
for (const f of await fsp.readdir(repoSrc)) {
  await fsp.copyFile(path.join(repoSrc, f), path.join(mirrorThumbs, f))
  copied++
}
console.log(`已复制仓库内真实服装缩略图: ${copied} 个`)

const localPathFor = (url) => {
  const rel = decodeURIComponent(new URL(url).pathname.replace('/nvzhuang/assets/', ''))
  return path.join(mirrorDir, rel)
}

const queue = []
for (const url of targets) {
  const dest = localPathFor(url)
  if (!fs.existsSync(dest))
    queue.push({ url, dest })
}
console.log(`需下载: ${queue.length} 个`)

let done = 0
let failed = 0
const CONCURRENCY = 16

async function fetchOne({ url, dest }) {
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(30000) })
      if (!res.ok)
        throw new Error(`HTTP ${res.status}`)
      const buf = Buffer.from(await res.arrayBuffer())
      if (buf.length < 100)
        throw new Error(`too small: ${buf.length}`)
      await fsp.mkdir(path.dirname(dest), { recursive: true })
      await fsp.writeFile(dest, buf)
      return true
    }
    catch (e) {
      if (attempt === 3) {
        failed++
        console.warn(`FAIL ${url}: ${e.message}`)
        return false
      }
      await new Promise(r => setTimeout(r, 800 * (attempt + 1)))
    }
  }
}

const workers = Array.from({ length: CONCURRENCY }, async () => {
  while (queue.length) {
    const job = queue.shift()
    if (!job)
      break
    await fetchOne(job)
    done++
    if (done % 200 === 0)
      console.log(`进度 ${done}/${queue.length + done} ...`)
  }
})
await Promise.all(workers)
console.log(`完成: 下载 ${done} 个, 失败 ${failed} 个`)
