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

## 2. 当前状态快照（2026-10-30）

```
HEAD   = cd171d0  polish(car-player): 黑胶盘叠静态双叶镜面扫光+外缘倒角高光
origin = 已同步（0/0）
upstream/master = 落后 0、领先 9
pubspec = version: 2026.10.29+20264   （app_config.dart 同步 appVersion/appVersionCode）
工作区 = 干净
```

线上最新 Release：**v2026.10.29**（2026-10-28 发布），资产 `app-release.apk` 31.4 MB，正式签名。

已发布版本链（每个 tag = 一次发版）：

| tag | versionCode | 主要内容 | 发布方式 |
|---|---|---|---|
| v2026.10.10 | 20261 | 原生车机全屏播放器首版、CI 省额度、8 图标 rounded、版本号规范 | tag→CI |
| v2026.10.14 | 20262 | 网络兜底（等网续播 / 智能降音质）、同步上游本地歌词匹配与歌单导入 | tag→CI |
| v2026.10.28 | 20263 | 同步上游 3.2.0/3.3.0、CI 去 iOS、`await` 编译修复、本地构建脚本 | 本地构建 + 手动发布 |
| v2026.10.29 | 20264 | 专辑详情无歌修复、车机黑胶唱片按网易云样式复刻 | 本地构建 + 手动发布 |

**尚未发布的 4 个提交**（下次发版即 20265）：

```
a59b110  chore(repo): 停止跟踪 pkkuwo/ 第三方参考 APK
ae2ec9b  polish(car-player): 黑胶沟槽改真实唱片式——降密度+轨道分隔间隙+反光边界
f27a6db  polish(car-player): 黑胶唱片真实化——去假高光带+细密低对比沟槽+双叶柔反射
cd171d0  polish(car-player): 黑胶盘叠静态双叶镜面扫光+外缘倒角高光
```

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

### 3.4 黑胶唱片的渲染参数（当前值，调参基线）

`makeVinylBitmap(size)` — 盘体静态位图：

- 底色 RadialGradient：`0xFF1B1B1F → 0xFF121215(0.7) → 0xFF0A0A0C`
- 沟槽：46 条，半径 `0.66R ~ 0.965R`，线宽 `size*0.0016`，奇偶交替 `0x0AFFFFFF` / `0x1A000000`（低对比，避免"靶心/光盘"感）
- 曲目分隔环：`0.80R`、`0.90R`，`0x14FFFFFF`，线宽 `size*0.004`
- 封面/盘体分界暗环：`0.635R`，`0x66000000`
- 外缘：`0.985R` 一丝 `0x14FFFFFF` + 最外 `0xFF060607` 收边（网易云盘边是干净暗边，无强高光）

`buildDisc(sizePx)` — 叠加层：封面占 `0.64R`；两层径向柔光 `glossMain`（中心 0.32/0.26，半径 0.95size，`0x1EFFFFFF→0x0BFFFFFF→透明`）、`glossSecondary`（0.72/0.8，0.72size，`0x14FFFFFF→透明`）。

`makeSheenBitmap(size)` — **静态 conic 镜面扫光（不随盘旋转）**：`SweepGradient` 双叶（0°/180° 峰值、90°/270° 透明），整体 `Matrix.setRotate(45°)`，用 `Path` + `EVEN_ODD` 遮成环形带。盘面主体峰值 `0x30FFFFFF`（环 0.66R~0.965R），外缘倒角带峰值 `0x55FFFFFF`（环 0.952R~0.974R）。实测 1366×768 截图环向亮度：盘面 ±55、倒角弧 ±98。

> 调参教训：①0x22 峰值数值上已有 ±40 但肉眼几乎看不出，"像唱片"需要 0x30 + 一条明确的倒角高光弧；②"硬斜向高光条"和"14 条粗沟槽"两版都被判为假并回退；③网易云车机版唱片贴图 `ap9.png` 实测参数（中心透明孔 0.630R、沟槽周期约 12px、不透明外缘 0.924R、无亮边高光）是本轮参数的来源——**只取参数，不复制其素材/代码**。

---

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
| 产物 | `build/app/outputs/flutter-apk/app-release.apk`（约 30 MB，非 split，可直接装 arm64/x86_64） |
| 耗时 | 冷编约 5–8 分钟，热编 2–3 分钟 |

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

- 设备物理竖屏 768×1366、系统强制横屏，截图 1366×768；`input tap` 直接用**截图像素坐标**。
- 直接 `am start .CarPlayerActivity` 只会看到空壳——它需要 Dart 侧的 `CarPlayerBridge.snapshot`，必须走 App 正常路径进入。
- `monkey -p ... -c android.intent.category.LAUNCHER 1` 经 MuMuManager 会被参数拆分而失效，用 `am start`。
- 确认设备上就是刚编的包：本地 `md5sum app-release.apk` 对比设备 `md5sum /data/app/*/com.hoilai.mm.music*/base.apk`。
- 视觉类改动要**量化**而非凭感觉：截图后用 PIL 沿半径采样环向亮度（如 r=0.75R / 0.85R / 0.963R，每 15° 一点），比较改动前后幅度再下结论。

---

## 10. 待办与已知问题

- [ ] **下次发版 20265**：把 §2 列出的 4 个未发提交（唱片真实化 + 双叶扫光 + pkkuwo 去跟踪）一并发出。
- [ ] **仓库里仍跟踪着两个 29.7 MB 的历史 APK**：`KA_Music_v2026.10.10.apk`、`KA_Music_v2026.10.10_b2.apk`（早期误提交）。建议 `git rm --cached` + 补 `.gitignore`。`.git` 已 192 MB，历史 blob 想彻底清需 git-filter-repo，但那会改动所有 tree、破坏已对齐的 release tag —— **不要做**。
- [ ] `pkkuwo/`（第三方参考 APK，逆向唱片贴图用）已停止跟踪，但历史 blob 仍在，同上不清。
- [ ] 黑胶观感仍属主观调优项，参数基线见 §3.4；若要再改，先渲染候选图供用户选，再动代码。
- [ ] Actions 免费额度常被耗尽（曾出现 "Actions has been disabled"），因此**本地构建是主路径**，CI 为备用。
- [ ] 20263 起不再产 iOS 包；如需 iOS，要恢复 `ios-build.yml` 并重新验证。
- [ ] 上游（`umr-xiaomai`）仍在活跃提交，定期 `git fetch upstream` 看是否有值得并入了的功能（本 fork 的播放器页与上游冲突面较大，merge 时留意 `player_page.dart` / `player_controller.dart`）。

---

## 11. 记忆与日志位置

- 项目长期记忆：`~/.qoderworkcn/awareness/main/memory/ka-music.md`（含线性化压缩手法、本地构建环境明细、黑胶参数、MuMu 进车机全屏流程）。
- 每日日志：`~/.qoderworkcn/awareness/main/memory/YYYY-MM-DD.md`（append-only，用 memory 工具写）。
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
