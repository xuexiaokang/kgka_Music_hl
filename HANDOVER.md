# KA-music 交接文档

> 更新时间：2026-10-30（本文取代 2026-10-01 旧版；旧版所述"本机无 Flutter 环境、编译全靠 CI、会话工作区在 WorkBuddy"等已失效）
> 目的：让任何人/任何工具接手本项目时不必重新踩坑。只写**事实与规则**，不含任何凭据明文。

---

## 1. 项目是什么

| 项 | 值 |
|---|---|
| 名称 | KA-music（`kgka_Music_hl`） |
| 性质 | Flutter 音乐 App，fork 自 `umr-xiaomai/kgka_Music_hl` |
| 本机克隆 | `E:\QClaw\KA-music` |
| 我的 fork | `https://github.com/xuexiaokang/kgka_Music_hl.git`（remote `origin`） |
| 上游 | `https://github.com/umr-xiaomai/kgka_Music_hl.git`（remote `upstream`，push 已禁用） |
| 默认分支 | `master`（不是 main） |
| 包名 | `com.hoilai.mm.music` |
| 目标设备 | 比亚迪海豚车机（弱 GPU / 弱网），另需适配手机与手表 |

本 fork 的**核心私有资产**是「全原生车机全屏播放器」——用 Android 原生 HWUI 覆盖 FlutterActivity，让全屏播放期间 Flutter 引擎彻底停帧，从而根治"KA 全屏播放时其他 App 掉帧"（酷我全屏不会）的问题。详见 §3。

---

## 2. 当前状态快照（2026-11-18，车机全屏差集功能已合入并实测，未发版）

```
HEAD   = 02c8bed feat: 音量均衡恢复增益补偿 postGain +3dB
origin = 已同步（8e76394 之后新增本次提交）
upstream/master = 落后 0、领先 22（全部已 push，不可再 squash——§7.3）
pubspec = version: 2026.11.16+20266   （准发版，tag 未打）
工作区 = 干净；当前 APK md5 892646ec…（25.2MB，正式签名，含差集功能 + 增益补偿）
```

**2026-11-19 新增：音量均衡恢复增益补偿（postGain +3dB）**

- 背景：排查确认音量均衡链路完整且 MuMu 实测生效（开/关/开闭环，`dumpsys media.audio_flinger`
  里 dynamics_processing effect 挂载/卸载联动），但 `bd9adc7`（v2026.10.28）把 `96b45bd`
  的增益补偿（preGain 2 + postGain 4 = +6dB）回退为 0，导致「只压不抬」、主观效果弱。
- 结论：本应用音源为纯在线流，播放前拿不到整曲 LUFS，真正的逐曲响度归一化纯端上不可行；
  用户拍板恢复增益补偿。
- 实施：`MainActivity.kt` MbcBand `postGain 0→+3.0f`，preGain 保持 0（不推高压缩器输入，
  避免增益被 MBC 压回）；limiter(-1dB/10:1) 兜底形成「抬底不削峰」准均衡。
  不恢复 +6dB（那是当初回退的原因）。
- MuMu 验证：新 APK 覆盖安装（开关 true 保留），起播后 `EffectHal::threadLoop`
  646→856→1711 持续增长证明音频流经效果器，无 FATAL；`EffectDP` 初始化瞬间的 5 条日志为
  AOSP 噪声（含 `SET_VOLUME invalid channel 0`），无功能影响。
- 局限：MuMu 无法客观测听感/削顶，真车机听感待实测；若仍嫌弱可升 +4dB，嫌突兀可降 +2dB。

**2026-11-18 新增：车机全屏「更多」面板差集功能（79b6ccc）**

上游 Flutter 车机页与本 fork 原生 `CarPlayerActivity` 的功能差集一次补齐，5 项全部在 MuMu
车机实例（Android 15）实测通过：

1. **歌词字号**：5 档（85/100/115/130/150%），`CarLyricView.setStyles` 缩放 +
   SharedPreferences（`car_player_prefs/lyric_scale`）持久化，仅影响车机全屏。
2. **详细信息**：`openPage(songDetail)` → `SongDetailPage`（专辑/发行/时长/音源/ID/Hash）。
3. **查看评论**：`openPage(comments)` → `CommentPage`（meta 带 `canComment`：酷狗 hash /
   网易 albumAudioId，本地不显示）。
4. **添加到歌单**：`openPage(addToPlaylist)` → `showAddToPlaylistSheet`（meta 带
   `canAddToPlaylist`：仅酷狗）。
5. **歌词设置**：桌面歌词开启时出现磁贴，`openPage(lyricsSettings)` →
   `DesktopLyricsSettingsPage`。首次开启桌面歌词需悬浮窗权限（系统弹授权页，MuMu 里已用
   `appops set … SYSTEM_ALERT_WINDOW allow` 授予；真机首次使用会走系统弹窗）。

跨层生命周期：原生页发 `openPage` → Flutter `player_page._openCarPage`：
`dismissNative()`（close 通道，只 finish 不发 closed）+ `CarPlayerService.close()`（否则
重进时 `_active` 短路）+ 120ms 延迟 → 根 Navigator push 目标页 → 返回后 setState 重置
`_carHandoff/_handoffSuppressed/_handoffScheduled` → 原生全屏自动重进。三条路径均实测重进正常。

MuMu 点击坐标换算（防再次踩坑）：MuMu 窗口 `nemuwin` 为 1366×768（与设备 1:1），
`_cap_pw.ps1` 截图输出 900×506（×1.5178 缩小）。`input tap` 用**设备坐标**，直接拿截图
像素点会偏。验证可用 `uiautomator` 在 `sh` 通道不可用（libnativeloader 链接失败）。

**领先上游 21 个提交的结构**（在原 18 个基础上新增 3 个）：

| 类别 | 提交 |
|---|---|
| release 里程碑（带 tag，绝不可改写） | `fdad8bd`(20261) → `2167769`(20262) → `bd9adc7`(20263) → `c159f35`(20264) → `9e63193`(20265) |
| merge 上游 | `25e6903` → `dd05918`(60bd37d) → `5757ff1`(ced8c47) → `330c506`(60a5f04) |
| 功能/性能 | `d02b51d` 渲染节流 → `bec1aa8` 黑胶 v6 + 停跟踪 pkkuwo → `49066eb` 停跟踪旧 APK → `2a49664` 测试适配 |
| release 准备 | `3773922` 版本号 20266 |
| docs/chore | `62121c1`/`ce9b2d3`/`63347d1` 交接文档、`5d5ace6` lock 刷新、`6e2ef74` .gitignore 忽略根目录 apk、`79b6ccc` 车机差集功能 |

> 这 18 个全部已 push 到 origin。**不要再做「领先 N 就压缩」**：squash 必须 force-push，
> 违反 §7.3（改写已发布历史 + 破坏 5 个 release tag + 摚乱与 upstream 的 merge 基准）。
> GitHub 显示「领先 18」无任何实际代价。上游活跃期「落后」提示会反复出现，属正常。

**2026-11-16 上游合并记录（两批，共 21 个上游提交）**：

1. **60bd37d**（10 提交）：收藏按钮含系统媒体通知、排行榜页、播放恢复/队列重建/搜索竞态修复、R8 开启。冲突 2 处：`albumSongs` 双语义并存（`fetchAll:true` 与 `pageSize:5000` 都可用）；`ios-build.yml` 恢复+改手动触发。
2. **60a5f04**（11 提交）：来电打断误恢复（#175）、蓝牙断开防外放、桌面歌词黑屏（#174）、车载蓝牙歌词标题同步（#166）、下载元数据（#176）。冲突仅 update.md；上游 30 个新测试在 fork 挂（Fake 缺 `errorStream` stub、车机黑宿主架构），已适配。

- CI 决策：`ios/windows-build.yml` 仅手动触发（计费 + 配额）；`flutter-ci.yml` 无 push 触发。
- R8 fullMode + shrinkResources 开启：APK **31.4 MB → 25.2 MB**（-20%），原生层静态引用安全。

线上最新 Release：**v2026.10.31**（2026-10-31 发布，release commit `9e63193`），资产 `app-release.apk` 31,393,657 B，正式签名。**v2026.11.16（20266）待车机实测悬浮视频后发布**。

已发布版本链（每个 tag = 一次发版）：

| tag | versionCode | 主要内容 | 发布方式 |
|---|---|---|---|
| v2026.10.10 | 20261 | 原生车机全屏播放器首版、CI 省额度、8 图标 rounded、版本号规范 | tag→CI |
| v2026.10.14 | 20262 | 网络兜底（等网续播 / 智能降音质）、同步上游本地歌词匹配与歌单导入 | tag→CI |
| v2026.10.28 | 20263 | 同步上游 3.2.0/3.3.0、CI 去 iOS、`await` 编译修复、本地构建脚本 | 本地构建 + 手动发布 |
| v2026.10.29 | 20264 | 专辑详情无歌修复、车机黑胶唱片按网易云样式复刻 | 本地构建 + 手动发布 |
| v2026.10.31 | 20265 | 黑胶重做（深黑盘体+窄叶方向光+极简外缘）、停止跟踪 `pkkuwo/` | 本地构建 + 手动发布 |

**尚未发布的提交**：`3773922`（版本号）→ `2a49664`（测试）→ `6e2ef74`（gitignore）→
`79b6ccc`（车机差集功能）等 20266 系列，随 v2026.11.16 一起发版。

相对上游的私有差异共 83 文件（+7277 / −431），大头是 `android/` 原生播放器与 `third_party/flutter_lyric/`。

---

## 3. 核心架构：全原生车机全屏播放器

### 3.1 为什么这么做

反编译酷我车机版确认它是**纯原生 HWUI/Skia/GLES**（`DrawLyricView extends android.view.View`，无 `libflutter.so`），而 KA 用 Flutter Impeller/Vulkan。KA 全屏播放时会霸占弱车机的 GPU，导致其他 App 掉帧。结论：全屏期间必须让 Flutter 不再产生任何帧，而不是继续在 Flutter 里抠性能。

### 3.2 组成

| 文件 | 规模 | 职责 |
|---|---|---|
| `android/.../CarPlayerActivity.kt` | ~1600 行 | 全屏 UI 全原生绘制：封面取色渐变背景、左侧黑胶转盘、右侧歌词、进度条、控制行、队列/更多底部弹窗 |
| `android/.../CarPlayerBridge.kt` | ~93 行 | 注册两条 MethodChannel + 缓存状态快照 `snapshot` |
| `android/.../CarLyricView.kt` | ~448 行 | 原生歌词滚动渲染 |
| `android/.../CarLyricPlatformView.kt` | ~81 行 | 把 `CarLyricView` 作为 PlatformView 嵌回 Flutter 侧复用 |
| `lib/services/car_player_service.dart` | — | Dart 控制器：`open()` 拉起原生页、每秒 `_pushSync`、接收原生事件 |
| `lib/ui/pages/player_page.dart` | — | 车机横屏时 Flutter 只返回**纯黑宿主 Scaffold**，绝不绘制自己的全屏 |

通道：`ka.car_player/native`（Dart→原生：open / sync / meta / lyrics / close）与 `ka.car_player/flutter`（原生→Dart：playPause / seek / next / prev / closed / requestSync）。

关键时序：open 时读 `CarPlayerBridge.snapshot` 立即建 UI → `onResume` 发 `requestSync` 让 Dart 推权威状态 → 两次 sync 之间用本地 `elapsedRealtime` 平滑推进进度与歌词 → 暂停即停帧让 GPU idle。播放动作仍回传 Dart 的 `PlayerController`，**音频链路完全不变**。

### 3.3 进入条件

`isCarLayout = 横屏 && ThemeController.instance.carModeEnabled`。开关在「设置 → 车机模式」，持久化键 `theme.car_mode_enabled`；检测到 automotive 设备时默认开启。原生页关闭时（`onClosed`）置 `_handoffSuppressed = true` 并直接退出全屏，不回落到会掉帧的 Flutter 横屏界面。

### 3.4 黑胶唱片的渲染参数（2026-10-30 v6 版：深黑盘体+窄叶光+极简外缘，调参基线）

> 演进：v4 被判"不够黑、发灰" → v5 深黑化（底色压深+面光收窄 ±55°）通过；v5 外缘宽暗带（0.925R~R 叠受光弧+暗侧面+硬收边）被判"外圈大边不和谐" → v6b 极简边：暗侧面收窄到 0.99R~R 且强度减半，受光弧峰值减半、带收窄，沟槽带延到 0.955R。

**架构约束（重要）**：盘体 bitmap 随 discGroup 旋转，因此**只有径向对称元素**（底色/沟槽/暗侧面）能画进盘体位图；一切方向相关效果（光感/受光弧）必须放在不旋转的静态 sheen 层。

`makeVinylBitmap(size)` — 盘体（**2x 超采样**后 FILTER_BITMAP 缩回，cap 2048；否则细沟槽与像素网格共振产生摩尔纹）：

- 底色 RadialGradient：`0xFF0E0E11 → 0xFF0A0A0C(0.7) → 0xFF060608`（v4 的 1B/12/0A 偏灰被实测否决）
- 沟槽：**55 条**，`0.645R ~ 0.955R`，线宽 `ss*0.0016`，交替 `0x0EFFFFFF` / `0x14000000`
- 最外暗侧面（v6b）：**`0.99R~R`** 叠 `0x4D000000`（v5 的 0.985R/0x8C 宽暗带被判不和谐）
- 封面分界暗环：`0.60R`，`0x66000000`
- 最外硬收边：`0xFF060607`

`buildDisc(sizePx)` — 叠加层：封面占 `0.585R`。

`makeSheenBitmap(size)` — 静态光泽层（不旋转）：

1. **窄叶方向光感（v5）**：峰 `-125°`（左上主光源），峰值 `0x26FFFFFF`，**半宽 ±55°**（SweepGradient stops `0/0.0765/0.153/0.847/0.9235/1`，半宽处平滑归零），遮罩 `0.60R~R`；受光区沟槽更明显，其余盘面纯黑
2. **外缘斜面受光亮弧（v6b）**：峰值 **`0x16FFFFFF`**，仅受光侧 ±75°，落在 **`0.955R~0.985R`**（v5 的 0.925R/0x30 宽亮带被判不和谐）

> 调参教训：①盘面"黑度"由底色+面光覆盖角共同决定；②外缘处理宽度超过 ~2% 半径就会读成独立一圈"厚边"，且暗侧面/受光弧任一过强都会放大它——**边缘存在感应远小于沟槽带**；③1px 沟槽必须 2x 超采样；④主观调优必须先渲染候选图供用户选；⑤amend 前必须确认 HEAD 就是目标提交（曾误把代码 amend 进 docs 提交）；⑥多轮迭代后重建提交链用 `git reset --soft <基线>` + 分次 add，比 amend 可靠。

---

### 3.5 全屏页渲染节流（2026-10-31 加，性能基线）

> 背景：车机全屏播放时，同屏的悬浮视频（其他应用）被拖慢。根因不是音频也不是 Flutter，而是**原生全屏页满帧重绘吃满 SurfaceFlinger 合成预算**——窗口每帧 damage，与它同屏的其它层一起被拖慢。

实测（MuMu，发布包 v2026.10.31，`dumpsys gfxinfo` 隔 20 秒取两次 `Total frames rendered` 差值）：

| 阶段 | 20 秒帧数 | 帧率 | App CPU |
|---|---|---|---|
| 优化前（Choreographer 每帧刷新） | 1220 | **61 fps** | 116% |
| 暂停（对照组） | 20 | 1 fps | — |
| 第一步（三个源各自定时节流） | 605 | 30 fps | 42% |
| 最终（统一 tick） | 427 | **21 fps** | 4% |

根因三条：

1. `frameCb`（Choreographer.FrameCallback）每帧写 seek/时间/歌词 → 强制 60Hz 重绘；而进度显示只到秒精度，1Hz 都够
2. 旋转组 `discGroup` 无硬件层，组内 `cover` 反而设了 `LAYER_TYPE_HARDWARE` → 每帧多一次离屏合成 + 重采样约 528×528 盘面位图
3. **多个刷新源若各自独立驱动，invalidate 会落在不同帧上叠加**（15+12.5+5≈32fps，实测 30fps 吻合）

现行机制：

- **统一 tick**（`tickMs = 66ms`，15Hz）：进度/时间/歌词/角度全部在 `tickTask` 里更新 → 同帧刷新
- 进度/时间只在**秒变化**时才写，避免无谓 invalidate
- 旋转**不用 ValueAnimator**（它由 AnimationHandler 每帧驱动，与 tick 不同步会额外造帧），改为 `stepRotation()` 按经过时间算角度：`(rotAnchorAngle + dt/32000f*360f) % 360f`
- `discGroup` 设 `LAYER_TYPE_HARDWARE`，**必须放在 `startRotation()` 的早退之前**——`buildDisc()` 重建后是新的 discGroup，新对象要重新挂层
- `cover` 的嵌套硬件层已删除

剩余 21fps 的构成：15fps 基础 tick + 歌词切行时平滑滚动动画（`CarLyricView` 的 `postInvalidateOnAnimation`）的短暂爆发。再降只能动歌词滚动，会损失体验，暂不做。

> 教训：①**多个刷新源必须合并到同一 tick**，否则各自节流也会叠成倍数；②判断页面是否持续重绘，用 `dumpsys gfxinfo` 差值法最快，不需要 root 与 profiler；③**暂停对照组**是证明"持续重绘"最有力的证据（1fps 说明没有隐藏的常驻渲染源）；④全屏 Activity 用不透明主题时下层 FlutterActivity 会 `onStop` 并停帧，已从 `dumpsys gfxinfo` 的窗口段确认（MainActivity 的 ViewRootImpl `visibility=8`）。

## 4. 其他 fork 侧功能

- **网络兜底**（`lib/controllers/player_controller.dart`）：卡顿时置 `_waitingForNetwork`，等网络恢复后自动续播（`isWaitingForNetwork` 暴露给 UI 显示提示）；`smartQualityEnabled`（键 `settings.smart_quality_enabled`，**默认关**）只在打开时才在取不到播放 URL 时降档重试。
- **专辑详情无歌修复**：`lib/services/music_api.dart` 的 `albumSongs(id, fetchAll: true)` 分页拉取（每页 50；`/album/songs` 会拒绝 pagesize≥60），`playlist_detail_page.dart` 专辑分支改用 `fetchAll`。
- **切歌卡顿修复**：`CarPlayerActivity` 队列面板改**懒重建**——`onMeta` 只缓存 `pendingQueueItems` 并置 `queueDirty`，面板可见/再次打开时才真正 `removeAllViews` 重建。原先每次切歌都在 UI 线程逐行 inflate + 逐行起下载线程。
- **vendored 歌词组件**：`third_party/flutter_lyric/`，`pubspec.yaml` 用 `dependency_overrides: flutter_lyric: path: third_party/flutter_lyric` 指向本地副本，以便直接改歌词渲染实现而不受 pub 版本约束。
- **桌面歌词 / 媒体会话歌词**：设置页可把实时歌词推送到车机或第三方车载歌词 App / 媒体标题（供手表、车机仪表盘显示）。
- **CI**：`android-build.yml`（仅 tag `v*` 触发 + `workflow_dispatch`，含可选 `--profile` 包用于帧率分析）、`flutter-ci.yml`；`ios-build.yml` 已在 20263 移除，故此后 Release 只有 APK。

---

## 5. 本地构建环境（已就绪，可直接编）

| 组件 | 位置 / 版本 |
|---|---|
| Flutter | `C:\flutter\flutter`，3.47.8（Dart 3.13.7） |
| JDK | scoop `temurin17`（**必须 JDK17**；系统默认 JDK25 会报 `JdkImageTransform`） |
| Android SDK | `C:\Android\Sdk`：build-tools 37、platform-tools、cmake 3.31.6、NDK 28.2.13676358 |
| platform-37 坑 | 它是 preview 版，AGP 会下成目录 `android-37.0`；已手工建 `android-37` 别名目录并把 `source.properties` 的 ApiLevel 改为 37。cmdline-tools 需最新版（旧 12.0 认不了 android-37） |
| 一键脚本 | `scripts/build-android.bat`：pub get → analyze（仅 error 阻断）→ `build apk --release`；路径可用 `FLUTTER_HOME`/`JAVA_HOME`/`ANDROID_HOME` 覆盖，内置 flutter-io.cn 镜像 |
| 产物 | `build/app/outputs/flutter-apk/app-release.apk`（R8 开启后约 **25 MB**，非 split，可直接装 arm64/x86_64） |
| 耗时 | 冷编约 5–8 分钟；本次含 analyze 实测 3m23s（增量） |

> R8：上游 2026-11-16 起 `isMinifyEnabled = true` + `isShrinkResources = true` + `android.enableR8.fullMode=true`。原生层依赖 `R.drawable.*` 静态引用与 `-keep class com.hoilai.mm.music.**`，**不要**改用 `getIdentifier` 动态取资源，否则会被资源裁剪删掉。

**正式签名**（与线上 Release 同证书）：`android/key.properties` + `android/release-keystore.p12`（PKCS12，alias `ka`），口令在 `build-artifacts/keystore-password.txt`。三者均在 `.gitignore` 内，**不得入库**。证书 SHA-256 = `76a87e479a20f071c829b99d6fa700a886c2d9b08d13a1605777ef153728c750`。缺 `key.properties` 时构建会**回退 debug 签名**——发版前务必确认走的是正式签名（可与线上 APK 的证书指纹比对）。

---

## 6. 版本号规范与发版流程

**版本号**：`versionName` = 发版日期（如 `2026.10.29`）；`versionCode` **从 20261 起逐次 +1**。`pubspec.yaml` 写 `version: 2026.10.29+20264`，`android/app/build.gradle` 自动跟随；同时手改 `lib/config/app_config.dart` 的 `appVersion` / `appVersionCode`。

> 为何不从 1 起：App 内"检查更新"是纯数值比较（服务端 versionCode > 本机），服务端仍是旧 semver `v3.1.0 = 310`，本机用 1/2/3 会永久误报"有更新"。也**禁止**整日期形式 `20261010`（会触发降级/冲突）。

**发版步骤（当前推荐：本地构建 + 手动发布，因 Actions 额度常被打爆）**：

1. 确认工作区干净、功能已提交；`pubspec.yaml` + `app_config.dart` 版本号 +1。
2. 提交并 push master（**必须先 push 再打 tag**，否则建 tag 报 422 "Could not verify object"）。
3. 跑 `scripts\build-android.bat`，产物即 `app-release.apk`。
4. GitHub API 发布：`POST /repos/{o}/{r}/git/tags`（annotated，指向刚推的 commit）→ `POST /repos/{o}/{r}/git/refs` 建 `refs/tags/v<日期>` → `POST /repos/{o}/{r}/releases`（**必须带 markdown 正文**）→ 资产上传到 `uploads.github.com`。
5. 若走 tag→CI 路线：CI 的 release 正文取自 `git tag -l --format='%(contents)'`，在浅克隆里会**退化成 commit message**；构建成功后必须再 `PATCH /repos/{o}/{r}/releases/{id}` 用本地 UTF-8 markdown 覆写正文。凡动过 tag 都要重跑构建 + 重刷正文。

---

## 7. Git 协作规范（2026-10-28 拍板，以此为准）

1. **一个功能 = 一个 squash 提交**。同一功能的开发、复审 fixup、小改在本地攒着，做完压成单提交再 push；不在 master 上散落 `feat` + `fix(review)` + `chore` 碎提交。
2. **只有发版才打 tag**。日常 push master 不触发构建（CI 已去掉 push 触发）。
3. **绝不改写已发布历史**：不对已 push / 已打 tag 的提交做 rebase、squash、force-push。此前"领先 N 个就整理"的做法已作废。
4. **同步上游用 merge**：`git fetch upstream && git merge upstream/master`，保留 merge 提交，冲突按本 fork 意图解（`pubspec.yaml` 保本地版本号 + `dependency_overrides`，`update.md` 双方功能并入「## 未发布」）。合并前先打安全 tag。
5. fork 私有内容**不得提上游 PR**（CI workflow、签名出库、`.gitignore`、版本号日期化）。真要向上游贡献：从 `upstream/master` 最新 commit 新建干净分支，只移植功能代码。

---

## 8. 推送与凭据

- 裸 `git push` 会失败（全局无 `credential.helper`）。用 `git -c credential.helper=store push origin master`。
- 有效 token 在 `~/.git-credentials.bak`（fine-grained PAT，`github_pat_` 开头）；`~/.git-credentials` 里的 `ghp_` token **已失效勿用**。历史遗留的 token 明文文件不应再读取，更不应提交。
- 我的账号对 `umr-xiaomai` 无写权限，`git push upstream` 会 403（且 push URL 已设为 `no_push`）。
- **git-over-HTTPS 代理（192.168.7.26:20170）时好时坏**；代理挂但 `api.github.com` 可达时，改走 **Git Data API** 推送：`POST /git/trees`（`base_tree` + 内联 content）→ `POST /git/commits` → `PATCH /git/refs/heads/master`。
  ⚠️ Data API 绕过 autocrlf，写进去的是 **CRLF**，而 base/上游是 LF → 之后 merge 会出现"整文件假冲突"。**用 Data API 前后都要把文件规范成 LF。**
- 历史上做过一次「线性化压缩」（21→15→5 个领先提交，全程 tree 逐字节不变：`git commit-tree` 造里程碑 + 顶部 merge 保 upstream 为祖先 + Data API 重对齐 tag）。**今后不再对已发布历史使用**，仅作技术记录。

---

## 9. 测试方法（MuMu 模拟器 x86_64）

工具：`C:\Program Files\Netease\MuMu\nx_main\MuMuManager.exe`（**不要用鼠标点 Windows UI**，走命令行）。会话工作区有 `mm.sh` 封装：`bash mm.sh "shell cmd"` 执行、`bash mm.sh shot name.png` 截图回传。

```bash
MM="C:/Program Files/Netease/MuMu/nx_main/MuMuManager.exe"
"$MM" control launch -v 1                      # 未运行时先启动，等 sys.boot_completed=1
"$MM" control app install -v 1 -apk "E:/QClaw/KA-music/build/app/outputs/flutter-apk/app-release.apk"
"$MM" sh -v 1 --cmd "am force-stop com.hoilai.mm.music; am start -n com.hoilai.mm.music/.MainActivity"
sleep 8; "$MM" sh -v 1 --cmd "input tap 190 260"   # 点迷你播放器封面 → 横屏自动 handoff 进原生全屏
```

要点与坑：

- 设备物理竖屏 768×1366、系统强制横屏，窗口 `nemuwin` 为 1366×768（与设备 1:1）。当前 `_cap_pw.ps1` 截图输出 **900×506**（缩小 ×1.5178）：`input tap` 必须用**设备坐标 = 截图坐标 × 1.5178**，直接拿截图像素会偏（曾点左列命中右列）。量坐标时可用 `_cap_mumu.ps1`（不缩放）或除回 1.5178。
- `uiautomator` 在 `MuMuManager sh` 通道不可用（`libnativeloader.so` 链接失败）；`adb connect 127.0.0.1:<adb_port>` 会被拒，只能走 `sh -c`。悬浮窗权限可直接 `appops set com.hoilai.mm.music SYSTEM_ALERT_WINDOW allow`。
- 直接 `am start .CarPlayerActivity` 只会看到空壳——它需要 Dart 侧的 `CarPlayerBridge.snapshot`，必须走 App 正常路径进入。
- `monkey -p ... -c android.intent.category.LAUNCHER 1` 经 MuMuManager 会被参数拆分而失效，用 `am start`。
- 确认设备上就是刚编的包：本地 `md5sum app-release.apk` 对比设备 `md5sum /data/app/*/com.hoilai.mm.music*/base.apk`。
- 视觉类改动要**量化**而非凭感觉：截图后用 PIL 沿半径采样环向亮度（如 r=0.75R / 0.85R / 0.963R，每 15° 一点），比较改动前后幅度再下结论。

---

## 10. 待办与已知问题

- [x] ~~下次发版 20265~~：**已于 2026-10-31 发布**（tag `v2026.10.31`，Release id 429646924，正文带 markdown）。
- [ ] **下次发版 20266**（版本号 `2026.11.16+20266`，已 push）：含 ①全屏页渲染节流（`d02b51d`，§3.5）②两批上游合并（收藏/排行榜/R8/打断修复/蓝牙防外放/下载元数据）③R8 裁剪 ④**车机全屏差集 5 项功能**（`79b6ccc`，MuMu 全实测）⑤**音量均衡恢复增益补偿 +3dB**（2026-11-19，MuMu 验证效果器生效）。剩：真车机实测过门（含音量均衡听感） → 打 tag `v2026.11.16` → 发 Release。**发版门：真车机实测悬浮视频流畅 + 来电/蓝牙断开行为**。
- [x] ~~合并后 MuMu 实测（R8 首开风险点）~~：2026-11-16 于车机实例（Android 15）冒烟全部通过——冷启 968ms、Flutter 分栏首页/排行榜/专辑详情正常、原生全屏页黑胶/歌词/控制完整、收藏变实心、帧率 19.5fps、无崩溃。详见 `memory/2026-11-16.md`。
- [ ] 渲染节流仍需**真车机**实测悬浮视频是否恢复流畅（MuMu 19.5fps 已验证节流生效）；未通过则降 tick 至 12Hz（旋转每帧 0.9°）。
- [ ] 若车机实测仍嫌卡：下一档可动歌词切行滚动动画频率（§3.5 末段），或把 tick 降到 12Hz（旋转每帧 0.9°，仍在可接受范围）。
- [x] ~~领先提交精简~~：2026-10-31 做了一次「外科式压缩」15→8（保留全部 5 个 release 里程碑及其 tag；只压缩中间过程提交，tree 逐字节不变）。备份分支 `backup-pre-squash-20261031`。
- [x] ~~仓库里仍跟踪着两个 29.7 MB 的历史 APK~~：2026-11-16 已 `git rm --cached`（`49066eb`）。历史 blob 仍在，彻底清需 git-filter-repo —— **不要做**（破坏 release tag）。
- [ ] 黑胶观感仍属主观调优项，当前基线为 v6（§3.4）；若要再改，先渲染候选图供用户选，再动代码。
- [ ] 性能回归检查：改动任何周期刷新逻辑后，用 §3.5 的 gfxinfo 差值法复测帧率，目标 ≤25fps。
- [ ] Actions 免费额度常被耗尽（曾出现 "Actions has been disabled"），因此**本地构建是主路径**，CI 为备用。
- [ ] 20263 起不再产 iOS 包；如需 iOS，要恢复 `ios-build.yml` 并重新验证。
- [ ] 上游（`umr-xiaomai`）仍在活跃提交，定期 `git fetch upstream` 看是否有值得并入了的功能（本 fork 的播放器页与上游冲突面较大，merge 时留意 `player_page.dart` / `player_controller.dart`）。

---

## 11. 记忆与日志位置

- 会话工作区（WorkBuddy）：`C:\Users\XXK\WorkBuddy\代可行`。项目长期记忆 `MEMORY.md`；每日日志 `memory/YYYY-MM-DD.md`（append-only）。
- 跨项目通用技巧（GitHub Data API 推送、MuMu 操作、Windows 中文写入用 Python/heredoc 等）在全局 `MEMORY.md`。
- 临时脚本与截图放会话工作区（`C:\Users\XXK\.qoderworkcn\workspace\<id>`），不要往仓库根扔。**注意**：MSYS 的 `/tmp` 原生 Python 读不到，落盘要用工作区绝对路径。

---

## 12. 常用命令速查

```bash
cd /e/QClaw/KA-music
git status -sb
git rev-list --left-right --count upstream/master...master     # behind / ahead
git fetch upstream && git merge upstream/master                # 同步上游
git -c credential.helper=store push origin master              # 推送

# 本地构建（release，正式签名）
cmd //c "scripts\\build-android.bat"            # 建议把输出重定向到 build_*.log（*.log 已 gitignore）

# 版本号核对
grep -n "^version:" pubspec.yaml
grep -n "appVersion\|appVersionCode" lib/config/app_config.dart

# 静态检查
flutter analyze --no-fatal-infos --no-fatal-warnings
```
