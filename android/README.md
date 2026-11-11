# 拍片哥女装 · Android APK 构建

把 `app/`（uni-app / unibest 前端）编译出的 H5 产物装进一个原生 WebView 壳，产出可离线安装的 APK。
**不需要 HBuilderX，也不需要 Gradle**：只用 Android SDK 自带的 `aapt2` / `d8` / `zipalign` / `apksigner` + JDK 的 `javac`。

## 产物

```
release/nvzhuang-android-1.3.2.apk     # 73.4 MB，已签名（v1/v2/v3），可直接侧载安装
release/screenshots/                   # 一加 11（Android 16）实机截图
```

```bash
adb install -r release/nvzhuang-android-1.3.2.apk
```

包名 `com.paipiange.nvzhuang`，版本 1.3.2 (versionCode 132)，minSdk 21 / targetSdk 34。
实机验证：发现首页、图库（写实 1407 / 二次元 1320）、服装详情、AI 模特库（1499 位）、
姿势库（1439 张）、AI 试衣间（GRSAI 直连出图，支持人像 + 服装 + 姿势三图）、
保存到系统相册、系统相册选图、历史记录人像缩略图跨重启、返回键 —— 全部正常。

## AI 出图：GRSAI 直连（不需要后端）

试衣生成不走 GoFrame，直接由壳里的原生桥打 GRSAI（`gpt-image-2.6`）：

```
H5 组 prompt + 参考图 → window.NativeGrsai.generate(id, payload)
  → POST https://api.grsai.com/v1/draw/completions（SSE；失败自动回退 /v1/api/generate 与另两个节点）
  → 结果图下载落盘到 App 私有目录 → 页面用 https://appassets.local/.local/results/xxx 显示
```

- 令牌优先级：设置中心里「客户端模型直调 → API Key」（`sk-` 开头才生效）> APK 内置（`-GrsaiKey`）。
- 三张参考图顺序固定为 `[人像, 服装, 姿势]`，对应提示词里的 Image 1/2/3。
- 提示词按「服装是否二次元」×「是否选了姿势」四选一（`app/src/service/grsaiPrompt.ts` 的 `buildTryonPrompt`）：

  | 服装 | 姿势 | 用哪条 | 关键点 |
  |---|---|---|---|
  | 写实 | 无 | `GRSAI_TRYON_PROMPT` | 保持照片质感，只换衣服 |
  | 写实 | 有 | `GRSAI_POSE_TRYON_PROMPT` | 照片质感 + 严格复刻 Image 3 姿态 |
  | 二次元 | 无 | `GRSAI_ANIME_FROM_PHOTO_PROMPT` | **把人像整体转绘成 2D 二次元插画**再穿动漫服装，明确禁止「真人身体 + 动漫衣服」 |
  | 二次元 | 有 | `GRSAI_ANIME_POSE_PROMPT` | 二次元转绘 + 严格复刻姿势 |

  二次元两条都会强调：保留五官/发型/身材的可辨识度、服装细节不简化、人物与服装同一画风（线稿粗细、赛璐璐上色、光色统一）。
- 每张约 600 积分（GRSAI 计价），实测单张 40~70 秒。
- 只有把 key 放到服务器上（跑 `backend/`，`config.example.yaml` 已加 `grsai` 条目）才能多人共用
  而不暴露令牌；单机自用不需要任何服务器。

## 生成任务队列

试衣间的「开始 AI 换装」不再阻塞在页面里，而是**提交进队列就返回**，可以马上挑下一套继续提交。

- 队列在 `app/src/store/tasks.ts`：串行执行（GRSAI 单并发更稳），任务落 `localStorage`。
- 任务页 `/pages/tasks/index`（「我的 → 生成任务」或试衣间顶部的队列条进入）：
  进行中 / 排队中 / 已完成 / 失败 四个筛选，卡片显示状态、耗时、队列位次；成功可「查看结果」，失败可「重试」。
- 进程被杀时正在跑的任务在下次启动标为**失败·应用已重启**；排队中的任务会接着跑。
- 任务记录只存小体积地址（人像存成 `/.local/people/p-<hash>.jpg`，服装/姿势是镜像地址），
  不存 base64，所以刷新、重启都不丢，重试也不需要重新选图。
- 任务成功后自动写入「试穿记录」，结果页与保存相册的链路完全复用。

## 架构

壳是一个 Activity + WebView，前端产物、图库缩略图、姿势缩略图都打进 APK 的 `assets/`：

| 内容 | 来源 | 运行时 |
|---|---|---|
| H5 页面 | `app/dist/build/h5` → `assets/` | 虚拟 https 源 `https://appassets.local/`，`shouldInterceptRequest` 直供 |
| 服装缩略图 | `android/mirror/thumbs`（写实 1407）<br>`android/mirror/anime/thumbs`（二次元 1320）<br>`android/mirror/portrait/thumbs`（模特 1499） | 拦截 `https://paipiange.github.io/nvzhuang/assets/...`，命中则本地返回，未命中才走网络 |
| 姿势缩略图 | `android/mirror/poses`（1439，来自 ai-pose-gallery-zh） | 同上，`.../nvzhuang/assets/poses/xxx.jpg` |
| 全尺寸原图 | 未打包 | 请求 `assets/img|png/...` 时自动回退到同名缩略图 |
| 目录数据 | `catalog-lite.json`（全量 2727 + 1499）+ `poses.json`（1439） | 内置于 JS bundle，离线可用 |
| 生成结果 | `filesDir/results/` | 用 `https://appassets.local/.local/results/xxx` 读，保存相册时由原生写入 MediaStore |

其它：

- 外链（淘宝图搜、GitHub、B 站）交系统浏览器，不劫持 WebView
- `onShowFileChooser` 接系统相册选择器；选中的图**由原生直接解码成 data URL**（`takePickedImage()`），
  页面不再 `fetch(blob:)` —— 自建 https 源下 blob 取内容不可靠，这正是「参考图读取失败」的根因
- 保存到相册走 `NativeGrsai.saveImage()` → MediaStore（`Pictures/拍片哥女装`），
  不走 `<a download>` + DownloadManager（虚拟地址它抓不到）
- 返回键优先走 WebView 历史（uni-app 是 hash 路由）
- 构建时 `VITE_USE_MOCK=true`，`settings.useMock` 默认 true → 不发 `/api/v1/*` 请求，
  也就没有「网络错误，换个网络试试」的 toast（数据来自内置目录）

> 试衣生成已经改为 GRSAI 直连，不再依赖 `http://127.0.0.1:8000`。

## 重新构建

```powershell
# 1) 全量目录数据（生成 app/src/static/data/catalog-lite.json 与 backend 的 catalog.json）
cd nvzhuang
node scripts/export-catalog.mjs
Copy-Item backend\internal\logic\catalog\data\catalog.json app\src\static\data\catalog-lite.json -Force

# 2) 图库缩略图镜像（首次约 50 MB；仓库里已有的写实缩略图会直接复制，其余从 Pages 下载）
cd android
node fetch-mirror.mjs

# 2b) 姿势缩略图（来自 ai-pose-gallery-zh 的 thumbs/，1439 张 22 MB）
#     复制到 android/mirror/poses/，文件名不变；构建脚本会整体打进 assets/poses/
Copy-Item ..\..\pose-gallery\thumbs\* .\mirror\poses\ -Force

# 3) 前端 H5 产物（首次需要 pnpm install）
cd ..\app
pnpm install
pnpm build:h5            # 输出 app/dist/build/h5

# 4) 打 APK（-GrsaiKey 决定内置令牌；不传则只能在设置里填）
cd ..\android
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 `
    -VersionName 1.3.2 -VersionCode 132 -GrsaiKey "sk-你的GRSAI令牌"
```

可选参数：`-AssetsDir`（H5 产物）、`-MirrorDir`（图片镜像，默认 `android/mirror`）、`-GrsaiKey`、`-OutApk`、`-VersionName`、`-VersionCode`。

构建脚本自动完成：`aapt2 compile` → `aapt2 link`（含 `--java` 生成 R.java）→ `javac` → `d8`
→ 注入 `classes.dex` + 全部 assets（H5 产物 + 图片镜像）→ `zipalign -p 4` → `apksigner`
（首次自动生成 `android/keystore/nvzhuang.jks`，密码/别名均为 `nvzhuang`）。

## 目录

```
android/
├─ AndroidManifest.xml                  # 包名/权限/图标/主题
├─ build-apk.ps1                        # APK 构建脚本（含内置 GRSAI 令牌注入）
├─ fetch-mirror.mjs                     # 服装/模特缩略图镜像下载
├─ src/com/paipiange/nvzhuang/
│  ├─ MainActivity.java                 # WebView 壳：双源拦截 / 文件选择 / 下载 / 保存相册 / 返回键
│  ├─ GrsaiBridge.java                  # JS ↔ 原生桥：GRSAI 生成、参考图解码、保存相册
│  ├─ UriImage.java                     # 相册选图 → data URL（缩放 + JPEG 编码）
│  └─ GallerySaver.java                 # 写入 MediaStore（Pictures/拍片哥女装）
├─ res/values/{strings,styles,grsai}.xml # grsai.xml 由构建脚本按 -GrsaiKey 生成
├─ res/mipmap-*/ic_launcher.png
├─ mirror/                              # 服装 2727 + 模特 1499 + 姿势 1439 张缩略图（约 72 MB）
└─ keystore/nvzhuang.jks                # 构建时自动生成；正式发版请替换为自有签名
```

## 本次构建对仓库文件的改动（本地构建配置，可按需回滚）

| 文件 | 改动 | 原因 |
|---|---|---|
| `app/src/static/data/catalog-lite.json` | 换成全量目录（原文件备份为 `catalog-lite.json.lite-backup`） | 无后端时只显示 128 条兜底数据 |
| `app/src/store/settings.ts` | `useMock` 默认值改为读 `VITE_USE_MOCK` | 避免每个页面都弹「网络错误」 |
| `app/env/.env` | `VITE_USE_MOCK = true`（新建，原为 `.env.example` 复制） | 同上 |
| `backend/internal/logic/catalog/data/catalog.json` | `export-catalog.mjs` 生成 | 全量数据来源 |
| `backend/manifest/config/config.example.yaml` | 新增 `grsai` 模型条目（`provider: openai`, `gpt-image-2.6`） | 自建后端也能走 GRSAI |
| `app/src/service/grsaiDirect.ts` 等 | 新增 GRSAI 直连 + 姿势支持 | 见上文 |
| `app/src/pages/poses/index.vue`、`app/src/static/data/poses.json` | 新增姿势库页面与数据 | 来自 ai-pose-gallery-zh |

## 坑位记录

1. **`aapt2 link -A` 在 Windows 会把 assets 条目名写成反斜杠**（`assets\foo.js`），
   `AssetManager` 解析不到 → 白屏。故 assets 改由脚本用 .NET Zip 以 `/` 写入。
2. **`file://` 源** 下 `IndexedDB` / `fetch` 受限，改用 `https://appassets.local` 拦截方案。
3. **PowerShell 5.1 读无 BOM 的 UTF-8 脚本会解析失败**，构建脚本保持纯 ASCII。
4. **原生 stderr 会被 `$ErrorActionPreference='Stop'` 当异常中断**（keytool 打横幅即中断），
   脚本改为 `Continue` + 显式检查 `$LASTEXITCODE`。
5. **国内网络访问 `paipiange.github.io` 会失败**（实测手机 curl 返回 000，PC 正常），
   这就是把缩略图镜像进 APK 的直接原因；`cdn.jsdelivr.net` 与 `raw.githubusercontent.com`
   在国内手机可通，可作为备选 CDN 写回 `VITE_ASSET_BASE_URL`。
6. `adb shell pm clear` 在 OxygenOS 上被拒（缺 `CLEAR_APP_USER_DATA`），清数据用
   `adb uninstall` + 重装代替。
7. **`uni.chooseImage` 在 H5 给的是 `blob:` 地址**，在自建 https 源的 WebView 里
   `fetch(blob:)` 取不到内容 → 「参考图读取失败」。改为原生 `UriImage` 直接解码选中图，
   页面用 `NativeGrsai.takePickedImage()` 取 data URL。
8. **「保存到相册」不能走 `<a download>` + DownloadManager**：结果图是壳的虚拟地址
   `https://appassets.local/.local/results/...`，DownloadManager 解析不到域名，只会进队列然后静默失败。
   改为 `NativeGrsai.saveImage()` → MediaStore（Android 10+）/ Pictures + 媒体扫描（旧系统）。
9. **`adb shell input tap` 用的是屏幕物理像素**，截图读坐标时要按 imagem 实际像素算，
   别拿预览缩放后的比例（这轮踩过一次，白点了几下）。
