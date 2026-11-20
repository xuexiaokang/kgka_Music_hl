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

## 2. 当前状态快照（2026-11-20，v2026.11.20.2 已发版）

```
HEAD   = dff23b3 fix(audio): 音量均衡 AGC 四轮修复与复审收口（v2026.11.20.2）
         （tag 锚在 dff23b3；其后的文档收尾提交另算，不打 tag）
origin = 已同步（0 未 push）；tag v2026.11.20.2（annotated, a3e2ff4e→dff23b3）
Release = v2026.11.20.2（id 452385977，正文 1712 字符，资产 app-release.apk 26,500,897 B，md5 c4d60dbf…，latest）
         上两版 v2026.11.20（id 451755386）/ v2026.11.20.1（id 452112971）保持不变
upstream/master = 落后 0（本 fork 领先；push 仍为 no_push，不可 squash——§7.3）
pubspec = version: 2026.11.20+20281
工作区 = 干净；续一~续四共四轮 AGC 修复与复审收口全部随本版发版，见下文五节
当前 APK md5 c4d60dbf5b550a7f68d487b7c210cd8e（26,500,897 B，15:20 重建，versionCode 20281，正式签名 SHA-256 76a87e47…）
```

> 上一版 `v2026.11.20` 为 v2026.10.31 之后的累计发版（18 个提交、66 文件 +8633 行）。音量均衡链路历经 AGC 开环前馈重构、MBC 透明增益级、时域变体回退等多轮修复，最终语义为**只补不减**（增益 `[0,+18]dB`），目标响度是补增益上限而非双向归一化目标。本次 `v2026.11.20.2` 为同日修订版二（versionCode 20281），把续一~续四共四轮 AGC 修复与复审收口一并发布，详见下文续五节。MuMu 实证覆盖安装与效果链，真车机逐曲听感仍未实测。

**2026-11-20 续五：发版 v2026.11.20.2（同日修订版二，versionCode 20281）**

- 触发：用户「发版」。续一~续四共四轮 AGC 修复与复审一次性收口全部随本版发布；按 §7.3 不改写已发布历史，因 `v2026.11.20`、`v2026.11.20.1` 的 tag/Release 均已占用，新建 `v2026.11.20.2`，上两版保持不动。
- 版本：`pubspec.yaml` `version: 2026.11.20+20281`、`lib/config/app_config.dart` `appVersionCode='20281'`；`versionName` 仍为 `2026.11.20`（应用内检查更新为纯数值比较，只递增 versionCode）。
- 产物：`build/app/outputs/flutter-apk/app-release.apk`，**26,500,897 B / md5 `c4d60dbf5b550a7f68d487b7c210cd8e`**（15:20:28 构建，晚于版本号改动）；`aapt dump badging` 实证 `versionCode=20281 / versionName=2026.11.20`；`apksigner verify --print-certs` 实证 `CN=KA Music Release` / SHA-256 `76a87e47…`，与线上同证书、可直接覆盖安装。
- 构建：`flutter build apk --release --no-pub` 成功（Gradle assembleRelease 87.0s，Kotlin 编译通过；仅 Gradle 8.14 / AGP 8.11.1 / Kotlin 2.2.20 即将不再受 Flutter 支持的既有 warning）；`pubspec.lock` 未被改写。
- 发版素材：`build/commit_msg.txt`（提交说明）与 `build/release_body.md`（Release 正文，markdown：四轮修复表格 + 复审三处缺陷 + 红线 + 验证 + 版本说明，如实标注「真车机逐曲听感仍未实测」）；两者在 `/build/` 忽略目录，不入库。
- 流程（§6）：先 push master → Git Data API 两步建 annotated tag `v2026.11.20.2`（tag object + ref）→ Release API 建 Release 并带 markdown 正文 → `uploads.github.com` 上传 `app-release.apk` → 核对 latest 与资产 md5。tag 锚在功能提交，文档收尾另起一个提交（与 `v2026.11.20.1` 同）。
- 历史节标题中的「未发版，工作区未提交」是当时状态的如实记录，不逐一改写；统一以本节为准。
- **发布结果**：annotated tag object `a3e2ff4ec5b20c770e6503ca2accf790edec702c` → `refs/tags/v2026.11.20.2`（指向 `dff23b3`）；Release **id `452385977`**，https://github.com/xuexiaokang/kgka_Music_hl/releases/tag/v2026.11.20.2 ，正文 1712 字符 markdown、`draft=false` / `prerelease=false` / `make_latest=true`；资产 id `770882549`，`app-release.apk` 26,500,897 B、`state=uploaded`、服务端 `digest=sha256:68d93616aca4e8033e99f1abbe8d1c769767ad72a9f0f700ace6b67db0fe47ce`；`GET /releases/latest` 返回 `v2026.11.20.2` ✅。本地 md5 `c4d60dbf…` 与字节数一致。
- 状态：**已发版**（功能提交 `dff23b3` + 文档收尾提交；tag 锚在 `dff23b3`，与 v2026.11.20.1 同构）。真车机逐曲听感仍未实测。

**2026-11-20 续四：复审发现的真实缺陷与文档失真一次修正（未发版，工作区未提交）**

- 触发：用户「问题全部一起修正」，把 13:55 复审列出的 P1/P2/P3 全部落地，不打补丁、统一收口。
- **逻辑修复（`MainActivity.kt`）**：
  - **P1-1 fixed→auto 起点未归零**：`setAgcActive(true)` 只翻 `agcActive` + `resetAgcTrackEstimate()`，不经过 configure，导致从固定档位（默认 +3、最高 +18）切到 auto 时沿用旧 postGain；偏响歌 desired≈0 需 0.6dB/步慢降十几秒仍被放大并触发 limiter。现 active 分支同步写 `applyMbcAndLimiter(..., 0f)` + `agcCurrentGainDb=0f`，与 configure「auto 起点固定 0dB」纪律一致；仍零 binder 重建（只写参数），不卡顿。
  - **P1-2 音频会话重建重置锁定增益**：`androidAudioSessionIdStream` 每次变化都走 `_applyVolumeNormalization()` → configure()，原无条件归零 + 清估计，车机蓝牙/USB 切换会把已冻结的 +15dB 瞬间归零再花 3~5 秒升回。现 configure 在 `ensureDynamicsProcessing` 之前判定 `sessionChanged && autoGain && agcLocked`，保存 `preservedGain/preservedDecisionDb`，末尾（因 `releaseDynamicsProcessing`/`startLoudnessAgc` 都会 reset 估计）统一恢复 `agcLocked=true` / desired=current=preservedGain 并显式写回 postGain，新增日志 `AGC preserved across session change`。切歌路径天然安全：`resetAgcForTrackChange` 在 `play()` 前同步 await 清锁归零，即便 session 回调抢先，随后归位与 configure 仍把新歌重置到 0dB。
  - **P2 改档响应慢**：`setAgcTarget` 锁定时重算 desired，但下降恒 0.6dB/步，+15→0 需约 10 秒而 UI 已显示「已锁定」。新增 `agcTargetAdjusting`：用户改档后临时把下降步长放开到 2dB/步，追平即清；`resetAgcTrackEstimate()` 统一复位。
  - **P3 clamp 统一**：`agcCurrentGainDb` 的 clamp 由 `[0,18]` 改为 `[0,+15]`（与 `agcDesiredGainDb` 一致）；`AGC_MAX_GAIN_DB=18` 仅保留给 fixed 档位与 `resetAgcForTrackChange` 归位 clamp。
  - **窗口常量与实现对齐**：删除 `AGC_WINDOW_SAMPLES=75`（从未填满，12 个样本即锁定），窗口数组、`%` 取模、`currentLoudnessPercentile()` 一律改用 `AGC_DECIDE_CYCLES=12`。行为不变，消除「最近 30 秒 / 75 样本」的失真。
- **注释订正（`MainActivity.kt`，不动逻辑）**：顶部大注释明确「12 个样本即锁定、无 30 秒滚动窗口」「极轻歌 desired 直接打满 +15，稳定性来自上限保护而非 P80 与增益自然相交」；`agcDecisionDb` 注释去掉已删除的「单向下调」；`resetAgcTrackEstimate()` 调用点去掉「目标档位变更」（实际不调）；configure 的 `[0,+18]` 改 fixed/auto 两档；归位「约 3 秒」改 4.8 秒；分位数注释「75 个 float」改「最多 12 个」；「整曲代表性响度」改「本曲开场代表性响度」。
- **UI 文案（`settings_page.dart`）**：锁定状态由「已按整曲响度锁定」改「已锁定本曲增益」——依据仅开场 4.8 秒 P80，不是整曲扫描；副标题「开场快速测定后锁定增益」「开场几秒给足后整曲不变」保持不变。
- **文档订正**：本文件续三节「保留 30 秒窗口 P80」「自然相交」「已按整曲响度锁定」三处、`update.md` 第 2 条「最近 30 秒有效样本的 P80 分位」均按最终实现改写；续二/续一历史节中「75 样本 / 30 秒」是当时实现的如实记录，不改写历史，仅在本节统一说明。
- **红线复核**：只补不减 `[0,+15]`（auto）、开环前馈无积分律、MBC 透明增益级 + Nyquist cutoff、variant 0、limiter 只兜峰、PEAK_RMS/scalingMode=1、权限前置与降级、切歌 `await` 归位、锁定后零估计零调整，全部保持。
- **验证（14:40~15:05，MuMu 车机实例 index 1 / Android 15）**：
  - `flutter analyze --no-pub` 14 项均为项目既有 info/warning，无新增；`pubspec.lock` 未改。`flutter build apk --release --no-pub` 成功（Gradle assembleRelease 178.7s，Kotlin 编译通过；仅 Gradle/AGP/Kotlin 版本即将不再受 Flutter 支持的既有 warning）。
  - 覆盖安装：MuMu adb 直连（`127.0.0.1:16416` / `192.168.7.188:5555`）均失败、`uiautomator` 因缺 `libnativeloader.so` 不可用，改用 `MuMuManager control -v 1 app close -pkg ...` + `control -v 1 -ver 15 app install -apk ...`，返回 `{"package":"com.hoilai.mm.music"}`，**安装成功，无 -502/7001**。
  - 冷启动实证（`sh -v 1 -c 'logcat -d -v time -s VolumeNormalization:V'`；流式 `-f` 后台在本机不落盘，改用短窗口 dump 避开 Vulkan/MESA 刷屏）：`configure: enabled=true autoGain=true gainDb=0.0 targetRmsDb=-16.0 session=57` → `AGC started: mode=PEAK_RMS` → 2/4/6/8/10/12/14/15dB 每 400ms 一步（3.2s 给足）→ `AGC track locked: desired=15.0 p80=-36.096 samples=12`（4.8s）。**desired 全程恒为 +15（上限保护），实测再次印证续四订正后的注释**。
  - 自动切到下一首（15:04:38）：`resetAgcForTrackChange: baseGain=0.0 agcActive=true` → `configure: ... session=57`（session 未变）→ 从 0 重新快测 → 4.8 秒重新 `track locked: desired=15.0 p80=-40.96 samples=12`。**session 未变故 `AGC preserved across session change` 未误触发（日志 0 次）**，切歌归位与「不误保留上一首增益」成立；锁定后日志尾部无 `AGC pulse`（整曲冻结保持）。
  - **验证边界（如实标注）**：`uiautomator` 不可用、adb 直连失败，无法自动操作设置页 UI，故「运行中 fixed→auto 归零」「运行中改档快速对齐」未能在 MuMu 复现；蓝牙/USB 音频切换导致的 session 重建也未能模拟。P1-1 / P1-2 / P2 的正向日志（如 `setAgcActive: AUTO gain=0.0`、`AGC preserved across session change`）**尚未取得实跑证据**，仅由 Kotlin 编译、代码路径审查与主路径实跑（含切歌不误触发）保证。真车机可手动复验：① 策略从固定（+12dB）切回自动，看增益是否立刻回 0 再 3~5 秒给足，而非从 +12 慢降；② 锁定后把目标响度由响亮改轻柔，看是否 1~2 秒内快速降回 0（而非十几秒）；③ 播放中切蓝牙/USB 音频，听是否还有 +15→0 的音量骤降。
- 产物：`build/app/outputs/flutter-apk/app-release.apk`，**26,500,905 B / md5 `44ca4ef84fb3b7ccc1178d2590c2d62a`**（14:59:27 构建，晚于本轮源码改动）；`versionCode=20280` / `versionName=2026.11.20`（**版本号未递增**），正式签名 `CN=KA Music Release` / SHA-256 `76a87e47…`（`aapt dump badging` + `apksigner verify --print-certs` 实证，与线上一致，可覆盖安装）。
- 状态：**未提交、未 push、未打 tag、未发 Release**；发版需先递增到 `+20281` 并重建（`v2026.11.20`、`v2026.11.20.1` 已占用，按 §7.3 应新建 `v2026.11.20.2`）。真车机逐曲听感仍未实测。

**2026-11-20 续三：修复「切歌后偏小声歌曲反而比之前更小声」（未发版，工作区未提交）**

- 触发：用户实测反馈「现在实测《别怕我伤心》反而比前面一般还要小声」。
- **实测取证**（MuMu index 1 / Android 15，《别怕我伤心》- 张信哲，PAUSED 在 1:43，档位 `loud` -12dBFS / auto）：12:34:48 切歌后 4.8 秒内增益仅 **+1.04dB**，而按 p80=-26.61dBFS 应补 **+14.61dB**（缺口 13.57dB）；中位段落输出 -31.47dBFS，离目标 -12 差 19.47dB；渐入速率 0.2dB/秒 ⇒ 补到 +14.6 需 **68 秒**，全程 `locked=false`。相对「前面一般歌曲」：偏响歌源 -13.88+0 ≈ -13.9dBFS，这首 -26.6+1.04 ≈ -25.6dBFS，实际小约 12dB。`audio_flinger` 确认 session 33 上 Visualizer(75) / Dynamics Processing(67) 均 Enabled ⇒ **不是功能失效，是增益给得太慢太少**。
- 根因：上一版 `ceiling = 有效样本数 × 0.08dB`（0.2dB/秒）为防开场打满，把增益放开拖到约 90 秒，等于让偏小声歌曲前半首乃至大半首几乎无补偿；日志还显示 `10:30:50→10:32:06` 花 76 秒才锁定、`12:27:54→12:34:43` **6 分 49 秒都没锁定**（增益只爬到 +12），即 ceiling 与 P80 上包络竞速导致锁不定。上一节把「172 帧/约 69 秒单调渐入」当成优点，其实是本次回归元凶；所谓「最终 +9.16dB」是播到 3:38 才达到，用户在 1:43 暂停根本没等到。旧版虽过补+回落，但 17 秒就冲到 +18，听感至少是「响」。
- 用户选择「快速测定一次给足（推荐）」后实施（`MainActivity.kt`，收口 `onAgcMeasuredRms`，整体替换模型，不打补丁）：
  - 删除 `AGC_RAMP_DB_PER_SAMPLE` / `agcRampCycles` / `ceiling` / `AGC_REESTIMATE_MARGIN_DB` / 锁定后单向下调整套机制；新增 `agcMeasureCycles`、`AGC_DECIDE_CYCLES=12`（4.8 秒）、`AGC_FAST_RISE_DB=2.0`（上升 5dB/s）、`AGC_AUTO_MAX_GAIN_DB=15.0`。
  - 开场测量期**边测边写**：P80 上包络只升不降 ⇒ desired 只降不升；增益以 2dB/步快升去追，窗口结束（`samples=12`）即 `agcLocked=true`、冻结 `agcDesiredGainDb`，**锁定后完全不做响度估计、不随段落调整**，仅用户改档 `setAgcTarget` 时重算。上升快（2dB/步）、下降慢（0.6dB/步）。
    **订正（续四）**：旧描述「P80 上升更快、两者自然相交、不再用轻前奏打满 +18 再回落」与实测 `desired=15.0 p80=-40.022` 不符——极轻歌曲开场 p80 低至 -40dBFS 时 rawDesired 远超上限，desired 直接打满 `AGC_AUTO_MAX_GAIN_DB=15dB`，稳定性来自**上限保护**，不是分位数与增益相交。
  - **+15 上限的由来**：首次实测（无上限版）发现开场 4.8 秒常只采到轻前奏（p80 低至 -38~-40dBFS），desired 直接打满 +18 并冻结整曲；而该曲人声峰值约 -17.7dBFS，+18 会推到 0dBFS 以上让 limiter 持续限幅（音质劣化，用户明确要求避免）。+15 留约 3dB 峰值余量，且无论开场采到前奏还是人声都稳定收敛到 +14.6~15dB，消除「有时 +14.6 / 有时 +18」的随机。`AGC_MAX_GAIN_DB=18` 仍用于 fixed 档位与归位 clamp。
  - 保留：**开场 12 个有效样本（4.8 秒）的 P80**、只补不减、开环前馈、MBC 透明增益级、PEAK_RMS、切歌先归位再播放、权限主动申请、`getAgcStatus().locked`。（续四订正：旧写「30 秒窗口 P80」失真——`AGC_DECIDE_CYCLES=12` 即锁定，窗口从未填满 75 个样本；最终实现已把窗口大小直接收敛为 12。）
- UI（`settings_page.dart`）：总开关副标题「开场快速测定后锁定增益」、auto 策略「开场几秒给足后整曲不变」、tile 状态「已锁定本曲增益 / 开场快速测定中」。（续四订正：旧写「已按整曲响度锁定」，依据仅开场 4.8 秒 P80，非整曲扫描。）
- **MuMu 实证**（同曲从头播放，档位 `standard` -16dBFS；改用后台流式 logcat 写 `/data/local/tmp/*.log`，避开 Vulkan/MESA 刷屏冲掉日志）：
  - 切歌后增益 0→**+15.0dB 仅用 4.8 秒**（到 +5dB 2.8s / +10dB 3.6s / +14dB 4.4s），**未打满 +18**；`AGC track locked: desired=15.0 p80=-40.022 samples=12`。
  - 锁定后连续播放至 2:26 **零次 `AGC pulse`**（整曲冻结）；measured 波动跨度 29.35dB（-54.49~-25.14dBFS，确有人声与纯音乐段落）而增益不变 ⇒ 歌内不突变保持。
  - 偏响歌（连续切歌段）`AGC track locked: desired=0.274 p80=-16.274` ≈ 0dB，红线保持；`audio_flinger` 确认 Visualizer + Dynamics Processing 仍在链上。
  - 与本次回归对照：切歌后 5 秒增益 **+1.04 → +15.0dB**；给足耗时 **68 秒 → 约 3~5 秒**；中位段落输出 -31.47 → 约 -19dBFS（改善约 12dB）。
- **如实标注**：
  - 档位已从 `loud`(-12) 变为 `standard`(-16)（覆盖安装后 App 重新读取 prefs；上一节曾 sed 改过但未 force-stop，本次生效）。该曲在两档下 rawDesired 均远超 +15、结果相同，不影响结论。
  - 切歌时 `resetAgcForTrackChange` 与紧随的 `configure` 会各调一次 `resetAgcTrackEstimate()`，导致开场第一帧样本被丢弃、日志里 p80 出现一次回退；但增益轨迹仍单调递增（2→4→…→15）、无突降，听感无影响。属已知边缘，本轮未强行改动。
  - P80 达标意味着高位人声/副歌已与别的歌对齐，中位/主歌仍偏低是该曲 16dB 动态范围所致，在「只补不减 + 无动态压缩」约束下无法消除（压缩会削波，用户此前明确要求移除）。若真车机听感仍偏小，可把百分位降到 P70 或给目标加 +3dB 偏置，属参数微调，待用户听感反馈，本轮未擅自改。
- 产物：`build/app/outputs/flutter-apk/app-release.apk`，26,500,625 B，md5 `c659c2f2eb87fe4f5dbe4bdb3d02ad5c`，`versionCode=20280` / `versionName=2026.11.20`（**版本号未递增**），正式签名 `CN=KA Music Release` / SHA-256 `76a87e47…`（`aapt dump badging` + `apksigner verify` 实证）。`flutter analyze` 14 项均为项目既有 info/warning（中途误带一个 `}` 已即时修正并复跑确认）；Kotlin 编译通过；`pubspec.lock` 已还原。
- 状态：**未提交、未 push、未打 tag、未发 Release**；发版需先递增到 `+20281` 并重建（`v2026.11.20`、`v2026.11.20.1` 已占用，按 §7.3 应新建 `v2026.11.20.2`）。真车机逐曲听感仍未实测。

**2026-11-20 续二：重构 AGC 估计模型，适配偏小声歌曲（未发版，工作区未提交）**

- 触发：用户要求复测「现在的参数是否适合声音小的歌曲，比如当前正在播放的《别怕我伤心》」。MuMu 车机实例（index 1 / Android 15）确认当时正播放《别怕我伤心》- 张信哲，档位 `loud`（-12.0dBFS）、模式 `auto`。
- **结论：上一版参数不适合**，三个量化硬伤（同曲播两遍稳定复现）：
  - 开场 `AGC_EVALUATE_CYCLES=8`（3.2s）只采到轻前奏 `-36.52dBFS`，算出的真实需求 +24.52dB 被 `AGC_MAX_GAIN_DB=18` 直接 clamp 打满；17 秒内增益 0→+18。
  - 人声/副歌渐响把峰值上包络从 -36.52 刷到 -17.72（+18.8dB），触发 7 次 `AGC gain revised down`，desired 18→5.72（**-12.28dB**），**过补状态持续约 3 分 27 秒**才稳定，听感是「前奏越放越响、人声进来后越唱越轻」。
  - 最终仍**补不够**：整曲 measured 中位数 -34.04dBFS、峰值 -17.72dBFS（动态 16.32dB），按峰值定增益只补 +5.72dB → 中位段落输出 -28.32dBFS，离目标 -12 差 16dB。这正是用户反复反馈「《别怕我伤心》还是非常小声」的根源。
  - 有效的那一半：歌内 `desired` 上升 0 次，纯音乐段确实不会抬增益（上一轮修复成立）；问题在**代表性响度怎么估**。与档位无关：标准档 -16 开场需求 +20.52 仍被 clamp 到 +18，终值只剩 +1.72dB（按公式推导，未实跑）。
- 根因：`3.2s` 固定窗口 + **峰值 max** 模型与「渐入式、大动态」歌曲结构性不匹配——窗口太短用最轻前奏定增益（过补），峰值只反映少数最响副歌（少补），两个偏差方向相反，造成「先猛补 → 一路撤 → 还是不够」。
- 用户选择「整体重构估计模型」后实施（`MainActivity.kt`，收口到 `onAgcMeasuredRms`，不打补丁）：
  - 代表性响度改为**最近 75 个有效样本（约 30 秒）的 P80 分位**的只升不降上包络 `agcRepresentativeDb`（新增 `currentLoudnessPercentile()`，dB 域线性插值；静音 < -55dBFS 不入窗也不推进计数）。删除 `agcObservedPeakDb` / `agcDecisionPeakDb` / `agcEvaluateCycles` 及 `AGC_EVALUATE_CYCLES` / `track decided` 整套旧机制。
  - 新增**开场缓升上限** `ceiling = 有效样本数 × 0.08dB`（≈0.2dB/s，约 90 秒放开到 +18dB）：由帧数驱动、与音乐段落无关，增益从 0 单调渐入；`ceiling >= rawDesired` 时 `agcLocked=true`、`agcDecisionDb=agcRepresentativeDb`，此后完全由整曲响度决定。
  - 锁定后仅当上包络再升超过 `AGC_REESTIMATE_MARGIN_DB=1.5` 才允许**单向下调**（防过补/削波），日志 `AGC gain revised down`；上包络只升不降保证 desired 只降不升。
  - `resetAgcTrackEstimate()` 扩展为清窗口/上包络/ramp/锁定，覆盖全部 6 个重置点；`setAgcTarget` 在已锁定时用新目标立即重算且不重置 ramp，未锁定时下一帧自然按新目标重算。
  - 保留：只补不减 `[0,+18]dB`、开环前馈、MBC 透明增益级、PEAK_RMS、切歌先归位再播放、权限主动申请、`getAgcStatus().locked` 语义（改为「已按整曲响度锁定」）。
- UI（`settings_page.dart`）：总开关副标题改「开场渐入后按整曲响度锁定」，auto 策略副标题改「开场渐入后锁定」，tile 状态改「已按整曲响度锁定 / 开场渐入中」，避免与缓升行为冲突。
- **MuMu 实证（同曲、同档位 -12dBFS，从头播放 + reset 后完整 3 分 38 秒）**：

  | 指标 | 旧版（峰值 max + 3.2s 窗口） | 新版（P80 + 缓升上限） |
  |---|---|---|
  | 开场峰值增益 | **+18.00 dB（打满）** | **+13.76 dB（未打满）** |
  | 渐入期 | 17 秒冲到上限 | 172 帧 / 约 69 秒单调渐入，gain 与 ceiling 完全同步、单调不降 |
  | 单向下调 | 7 次 / **-12.28 dB** / 3 分 27 秒 | 3 次 / **-4.60 dB** |
  | 最终增益 | +5.72 dB | **+9.16 dB（多补 3.44 dB）** |
  | 中位段落输出 | -28.32 dBFS | -24.88 dBFS（改善 3.44 dB） |
  | 歌内 desired 上升 | 0 | **0（锁定期）**，低电平段最低 -24.75dBFS 期间未上升 |
  | 旧机制残留 | — | `release/unlock/track decided` **0 次** |

  - P80 上包络渐入期 -46.76→-25.78dBFS 只升不降；measured 波动跨度 35.99dB（确有人声与纯音乐强弱段落）。锁定事件 `desired=13.72dB p80=-25.72dB samples=173 ceiling=13.84dB`。
  - 红线复核：偏响歌《怨苍天变了心》p80=-13.88dBFS，响亮档锁定 +1.88dB（标准档 -16 下按公式为 0dB），未被减也未被大幅补。
- **如实标注的物理限制**：P80 达标 -12dBFS 意味着高位人声/副歌段已与别的歌对齐；中位/主歌段仍低约 12.9dB 是这首歌本身 16dB 动态范围所致，在「只补不减 + 无动态压缩」约束下无法消除（压缩会让峰值削波，用户此前明确要求移除压缩）。若真车机听感仍觉得偏小，后续可把百分位降到 P70（预计再多补约 3~5dB，峰值仍不削波）或给目标加 +3dB 偏置——属参数微调，需用户听感反馈后定，本轮未擅自改。
- **档位纠正**：上一节末「测试后已把档位还原 standard」不成立（只改写 prefs 未 force-stop，App 内存态/后续写入覆盖回 `loud`）；本次复核确认实际仍为响亮档，为与旧数据同条件对比**全程保持 -12.0dBFS**。是否还原 standard 待用户决定，未擅自 force-stop 打断播放。
- 产物：`build/app/outputs/flutter-apk/app-release.apk`，26,500,353 B，md5 `e2c81d16f86499805d049f674496ca71`，`versionCode=20280` / `versionName=2026.11.20`（**版本号未递增，与已发布的 v2026.11.20.1 相同**），正式签名 `CN=KA Music Release` / SHA-256 `76a87e47…`（`aapt dump badging` + `apksigner verify` 实证）。`flutter analyze` 14 项均为项目既有 info/warning；Kotlin 编译通过；`pubspec.lock` 已还原。
- 状态：**未提交、未 push、未打 tag、未发 Release**；发版需先递增到 `+20281` 并重建（`v2026.11.20` 与 `v2026.11.20.1` 已占用，按 §7.3 应新建 `v2026.11.20.2`）。真车机逐曲听感仍未实测。

**2026-11-20 续：修复「歌内纯音乐段落突变增益」（未发版，工作区未提交）**

- 现象：自动增益下，每首歌里没有人声、只有伴奏/前奏/间奏的段落增益突然变大，人声进来又变小，反复跳变。
- 根因（**算法模型缺陷，非参数问题**）：`onAgcMeasuredRms()` 每 400ms 拿**瞬时**短窗 RMS 直接算 `desiredGain = target - measured`。有人声的主歌/副歌电平高（-13~-16dBFS），纯音乐段落天生低 6~12dB（-22~-30dBFS），于是人声段 `desiredGain=0` 走「独立归零分支」把增益每步 -0.6dB 拉回 0 **并强制解锁**，间奏段 `desiredGain=+9` 又每步 +0.6dB 爬升，增益在 `0 ↔ +9dB` 间随人声进出反复跳。原「死区 + 锁定」防呼吸效应完全失效：段落电平差 8~12dB 远超 1.6dB 解锁阈值，且归零分支会立即清锁。
- 修复（`MainActivity.kt`，收口到决策模型，不打补丁）：
  - 决策依据改为**整曲代表性响度** `agcObservedPeakDb`（本曲迄今最高 RMS 的上包络，只升不降）；瞬时 `measured` 仅用于 UI 展示与更新上包络，绝不直接换算增益。
  - 新增开场评估窗口 `AGC_EVALUATE_CYCLES=8`（3.2s）：只累积上包络、**不写增益**，避免用一段轻前奏草率定下高增益；窗口结束用 `peakHold` 一次性算出 `agcDesiredGainDb` 并 `agcLocked=true` 锁定整曲。
  - 上包络只升不降 ⇒ `desiredGain` 只降不升 ⇒ 增益在一首歌内单调不增，**纯音乐段落永远无法把增益抬起来**。
  - 已锁定后仅当出现比决策依据高 `1.5dB` 的更响段落（开场窗口只覆盖到轻前奏）才允许重估，且**只允许单向下调**（防过补与削波），日志 `AGC gain revised down`。
  - 删除失效旧机制：0.8dB 死区、12/3 次锁定计数、独立归零分支、`AGC unlock/release`；统一为「按 0.6dB/步平滑逼近 `agcDesiredGainDb`」。
  - 新增 `resetAgcTrackEstimate()` 统一清状态，覆盖 `configure` / `setAgcTarget` / `setAgcActive` / `resetAgcForTrackChange` / `startLoudnessAgc` / `stopLoudnessAgc`；`setAgcTarget` 在已锁定时立即用新目标重算，用户改档即时生效且不重开窗口。
  - 保留全部既有语义：只补不减 `[0,+18]dB`、开环前馈、MBC 透明增益级、PEAK_RMS 测量、切歌先归位再播放、权限主动申请。
- UI（`settings_page.dart`）：总开关副标题改「每首歌自动测定一次增益」，auto 策略副标题加「整曲测定后歌内不变」，实时增益 tile 把「测得」改「瞬时」并把「已稳定/调节中」改「已按整曲锁定/正在测定本曲」，避免用户看到瞬时值跳动却增益不变时误判为失灵。
- **MuMu 车机实例（index 1 / Android 15）实测**：覆盖安装后冷启动自动续播，`configure: autoGain=true` → `AGC started: mode=PEAK_RMS`。标准档下连切 4 首，5 首歌各出现一次 `AGC track decided`，`desired` 均为 0 且锁定后**零 `AGC pulse`**（增益全程 0，无段落调整）。为覆盖偏轻歌，临时把档位改为「响亮」(-12dBFS) 重启再测：5 首歌 / 36 次 pulse，`measured` 波动跨度 **41.74dB**（-54.96~-13.22dBFS，确有人声与纯音乐强弱段落），而每首歌内 `desired` **全部非增、上升次数 0**；旧机制 `AGC release/unlock/locked` 出现 **0 次**，仅 1 次 `AGC gain revised down`（单向下调）。增益只在开场平滑收敛一次（含一首极轻歌 0→+18dB 上限，属既有「只补不减」语义，非歌内突变），歌内不再随段落跳变。**更正：当时「已还原 standard」的说法不成立**——只改写 prefs 未 force-stop，App 内存态/后续写入把档位覆盖回 `loud`；2026-11-20 复核确认实际仍为响亮档，因此后续所有对比测试均在 `-12.0dBFS` 同条件下进行。
- 产物：`build/app/outputs/flutter-apk/app-release.apk`，26,499,833 B，md5 `0ba2156f26ab7a1edee69d3e730b84cf`，`versionCode=20280` / `versionName=2026.11.20`（**版本号未递增，与已发布的 v2026.11.20.1 相同**），正式签名 SHA-256 `76a87e47…` 未变。`flutter analyze` 14 项均为项目既有 info/warning，无新增问题；`pubspec.lock` 已还原。
- 状态：**未提交、未 push、未打 tag、未发 Release**；发版需先递增到 `+20281` 并重建（`v2026.11.20` 与 `v2026.11.20.1` tag/Release 均已占用，按 §7.3 应新建 `v2026.11.20.2`）。真车机逐曲听感仍未实测。

**2026-11-20 发版后修复：打开音量均衡开关未主动申请 RECORD_AUDIO（已发版 v2026.11.20.1）**

- 现象：首次安装 / 从未手动切换过增益策略时，直接打开「音量均衡」总开关不弹录音权限请求；原生 `startLoudnessAgc()` 检查到权限未授予即打印 `AGC skipped: RECORD_AUDIO permission not granted` 并直接返回，Visualizer 不创建、测量线程不跑，**实时增益永远无数据**，而 UI 仍显示「自动适配」，形成开关已开却无效果的假成功。
- 根因：`_ensureRecordAudioPermission()` 只挂在 `setVolumeGainMode(auto)`（手动切策略）一个入口，而 `volumeGainMode` 默认值就是 `auto`。因此「打开总开关」「冷启动恢复设置」「audioSessionId 变化（切歌）」三条路径都绕过权限申请，直接以 `autoGain=true` 下发原生并被静默跳过。
- 修复（`lib/controllers/player_controller.dart` + `lib/ui/pages/settings_page.dart`）：
  - 权限确保下沉到 `_applyVolumeNormalization()` —— 所有 `autoGain=true` 下发的唯一汇合点，且**严格先于** `configureVolumeNormalization`，保证原生启动 Visualizer 时权限已到位。
  - `_applyVolumeNormalization()` 改为返回 `bool`；权限被拒时降级 `fixed`（内存态 + 持久化 + 清 `agcStatus` + `notifyListeners`），以 `autoGain=false` 下发，静态增益补偿照常工作，杜绝「显示自动、实际无 AGC」的假成功。
  - `setVolumeNormalizationEnabled()` 改为 `Future<bool>` 并顺序 await；设置页开关 `onChanged` 拿到 false 时 Toast 提示「未获得录音权限，已回退固定增益模式」。
  - `_ensureRecordAudioPermission()` 增加 in-flight Future 去重：冷启动恢复与首次 `audioSessionId` 上报几乎同时触发下发，合并为一次系统弹窗；已授予时缓存命中，切歌等高频调用零 platform channel 往返。
  - `setVolumeGainMode()` 被拒分支补持久化 `fixed`，避免每次冷启动反复弹权限对话框。
- 原生层未改动：`AndroidManifest.xml` 早已声明 `RECORD_AUDIO`；`startLoudnessAgc()` 的权限检查保留作为最后一道防线。
- **MuMu 车机实例（index 1 / Android 15）实测**：预置 `enabled=true` + `mode=auto` + `pm revoke RECORD_AUDIO` 后冷启动 → `GrantPermissionsActivity` 弹出（证明主动申请）→ 权限变为 `granted=true, USER_SET` → logcat `configure: enabled=true autoGain=true` + `AGC started: session=17 mode=PEAK_RMS`，全程无 `AGC skipped`，`AGC pulse` 持续输出实测电平（-17~-35dBFS，增益收敛 +9~11dB）。`flutter analyze` 无新增问题（14 项为项目既有 info/warning）。
- 产物：`build/app/outputs/flutter-apk/app-release.apk`，26,499,833 B，md5 `9a19172c04c3df7dbfe6b3543bb51713`，`versionCode=20280` / `versionName=2026.11.20`（`aapt dump badging` 实证），正式签名 SHA-256 `76a87e47…`（`apksigner verify --print-certs` 实证，与上一版同证书）。已发布为 **`v2026.11.20.1`**（同日修订版）：`v2026.11.20` 的 tag 与 Release 已占用，按 §7.3 不得改写已发布历史，故新建 tag 与独立 Release，上一版保持不动。
- 未实测：权限被「拒绝」时的降级分支（MuMu 会自动授予权限对话框），代码路径已静态确认。

**2026-11-19 修复：实时增益测得值不准（c1c2cbf）**

- 现象：上一版虽修了无符号基准与目标钳制，但测得值仍不准。根因是 AGC 从 8bit 波形手算 RMS 的方案本身有根本缺陷：
  - **`scalingMode=0` 实为 `SCALING_MODE_NORMALIZED`**（按内容放大到满幅，测不出真实响度）。SDK 中根本没有上一版注释以为存在的 `SCALING_MODE_NONE`，`0` 就是 NORMALIZED。
  - 8bit 波形有量化误差，且 AS_PLAYED 模式受系统音量缩放影响。
- 改用官方 `setMeasurementMode(MEASUREMENT_MODE_PEAK_RMS)` + `getMeasurementPeakRms()`：系统级 16bit 精度、绝对 mB 电平，`dBFS = mRms/100`；`scalingMode` 设 `AS_PLAYED(1)`。
- 后台 `HandlerThread` 每 400ms 轮询（binder 调用不阻塞主线程，根治历史卡顿）；`quit()` 清理延迟消息防 release 后失效句柄。
- 验证：MuMu 实测 `AGC started mode=PEAK_RMS`；pulse 在后台线程；measured 真实动态 `-42.5→-35.9→-45.5dBFS`；误差方向正确（轻歌加增益）；音量 0 时仍测到歌曲电平，证明测量不受系统音量误导。

**2026-11-19 修复：自动增益方向错乱（df0a18e）**

- 现象：响的歌增益比轻歌加得更多，与 AGC 目标相反。MuMu 日志铁证：测得恒为 `0dBFS`、下发 `-16` 却生效 `-8`。两个叠加 bug：
  - **① RMS 漏减无符号基准**：`Visualizer.getWaveForm()` 返回 8bit **无符号** PCM（0~255，静音=128），原按有符号算 → 静音被当满幅 1.0 = 0dBFS，所有歌测得挤在 0dB 附近、响度差异消失，静音冻结(-55)失效。改 `(toInt() and 0xFF)-128` 再归一化。
  - **② 响度目标被增益范围钳制**：`coerceIn(-8,18)` 把 -20/-16/-12 全抬到 -8，三档形同虚设。新增独立范围 `[-40,-3]dBFS`。
  - 验证：数值模拟（静音→-∞、轻-35/中-21.5/响-12.2dBFS）+ MuMu 真机日志（修复前 `measured=0.0 target=-8.0` → 修复后 `measured=-9.01 target=-16.0 err=-7`，响歌正确减增益）。

**2026-11-19 修复：实时增益不刷新（9ea8b4a）**

- 现象：切歌后「实时增益」数值看不出变化。根因：`refreshAgcStatus()` 只在点击/切模式/audioSession 变化时调用，而 just_audio 切歌复用同一 session → 不刷新，一直显示旧快照。AGC 内部其实在收敛，只是界面不更新。
- 修复：新增 `_AgcLiveTile`（StatefulWidget），设置页存活期间每 2 秒轮询，离开自动 cancel，首帧后先拉一次；`refreshAgcStatus()` 加状态比较，值未变不 notifyListeners，避免轮询本身造成重建卡顿。
- 附带诊断（未改代码，用户选择只修轮询）：①《别怕我伤心》偏小是母带电平低 + limiter(-1dB) 削峰 + 增益上限 +18dB 的物理限制，纯在线端上闭环补不动极端安静母带；②"测得 dBFS"是 400ms 窗口、8bit、效果器输出后的瞬时电平，粗估非精密；③dBFS 是振幅单位非响度（LUFS）。

**2026-11-19 优化：音量均衡 auto/fixed 策略切换卡顿（5bec594）**

- 现象：切换「自动适配 / 固定增益」策略时卡顿一会。
- 根因：切模式走完整 `configureVolumeNormalization`，会 stop+release Visualizer 再 new+setDataCaptureListener+enabled=true（主线程 binder 同步调用），音频服务繁忙时阻塞 UI 掉帧；切 auto 前还同步 await 权限查询。
- 修复：Visualizer 改为音效开启期间常驻（两模式共用，`onAgcWaveform` 在 fixed 早退），模式只由 `agcActive` 标志决定；新增轻量通道 `setAgcActive`（只翻开关 + fixed 时写一次 postGain）；控制器切模式不再 `_applyVolumeNormalization`；权限状态进程内缓存，已授权切 auto 零 channel 往返。
- MuMu 实测：AGC 正常启动（`configure autoGain=true` + `AGC started session=105`）、无崩溃；代码路径确认切换不再重建 Visualizer。
- 未验证：真车机连点切换手感（MuMu UI 自动化受限）。

**2026-11-19 修复：音量均衡设置菜单假死（c1b8967）**

- 现象：选择「目标响度」等菜单偶发假死（点击后无响应 / 状态不刷新）。
- 根因：切档走 `_applyVolumeNormalization` → 原生 `configureVolumeNormalization`，重建 DynamicsProcessing + Visualizer（主线程 binder 同步调用），且 Dart 侧 `notifyListeners()` 排在所有串行 await 之后；音频服务繁忙时主线程阻塞、选中态不刷新。
- 修复：
  - 原生新增 `setAgcTarget` 轻量通道，只更新 `agcTargetRmsDb` + 重置锁定，不重建效果器 / Visualizer；`startLoudnessAgc` 幂等化（同 session 且已激活时跳过重建）。
  - 四个 setter（开关 / 固定增益 / 模式 / 目标响度）改为先 `notifyListeners()` 刷新 UI，再 `unawaited` 异步持久化 + 下发；目标响度走轻量 `setAgcTarget`，不再每次重建效果器。
  - `refreshAgcStatus` 补 `notifyListeners`（修「实时增益」点击不刷新）；设置页该 tile 点击不再 await 原生。
- 版本 `2026.11.20+20268` → **`+20269`**；新 APK md5 `2b22a48ac43db2fb6671cf9e611ea577`、versionCode 20269。MuMu 实测 AGC 正常启动、无崩溃、无阻塞日志。真车机菜单连点待实测。

**2026-11-19 新增：实时响度自动增益 AGC（6469f47）**

- 背景：固定增益档（0~+12dB）对所有歌曲加同样的 dB，导致本就响的歌被过度放大触发 limiter、轻的歌又不够。纯在线流播放前拿不到整曲 LUFS，无法做真·逐曲归一化，故改用端上实时 AGC。
- 原生层（`MainActivity.kt`）：`Visualizer` 挂到播放 `audioSessionId` 测波形 RMS（`SCALING_MODE_NONE`），慢速反馈环每 400ms 调一次 `MbcBand.postGain`（每步 ±0.6dB，范围 -8~+18dB）；死区 ±0.8dB 连续 12 次锁定、偏离 2×死区连续 3 次解锁；RMS < -55dBFS 视为静音冻结。MBC 压缩参数与 limiter(-1dB/10:1) 保持不变，preGain 恒 0。
- 权限：新增 `RECORD_AUDIO`（仅读播放混音，不采麦克风）。拒绝时 AGC 优雅跳过，效果器仍以固定 gainDb 工作，音效不失效；Flutter 侧 `setVolumeGainMode(auto)` 也先申请权限，拒绝则保持 fixed 并 Toast 提示。
- Flutter 层：`VolumeGainMode{fixed,auto}` + `AutoLoudnessLevel{gentle,standard,loud}`（-20/-16/-12 dBFS，默认 standard）持久化；`AgcStatus` 快照与 `getAgcStatus` 通道供设置页展示实时增益/测得响度/锁定态。
- 设置页：音量均衡开启后显示「音量增益策略」（自动/固定）；自动模式显示「目标响度」+「实时增益」（点击刷新），固定模式保留原 5 档增益。
- **MuMu 车机实例（index 1 / YOK-AN10）实测**：授予权限后 `configure: autoGain=true` + `AGC started` 正常、无崩溃；撤销权限后 `AGC skipped: RECORD_AUDIO permission not granted` 优雅降级、进程健康。真车机逐曲听感（响/轻歌增益差异）未实测，待过门。

**2026-11-19 版本推进与仓库整理（本轮）**

- 版本：`2026.11.16+20266` → **`2026.11.19+20267`**（`pubspec.yaml` /
  `app_config.dart` / `update.md` 段标题同步）。因 20266 从未打 tag，直接把未发布的
  `v2026.11.16` 日志段升格为 `v2026.11.19`，不新增空段。
- 新 APK：26,495,237 B，md5 `7e5f7b5979330ff0866fbb50ce996c70`（含可调增益版本），
  versionCode 20267 / versionName 2026.11.19，签名 SHA-256 不变。**未打 tag、未发 Release**。
- 仓库整理：删除本地 9 个 `pre-*` 备份 tag + origin 上 2 个；删除 3 个历史重写前旧分支
  （本地 3 + origin 2）。全部 16 个 `v*` 发布 tag 保留。SHA 归档见 §13。
- 在线核对：origin/master `765b866` 与本地一致；Release 5 个（最新 v2026.10.31）；
  本仓库 artifacts/cache 均为 0；账户 33 仓库 Actions 存储 **34.06 MB / 500 MB**，无复发。
- 上游反馈：`umr-xiaomai/kgka_Music_hl#181`（改进建议：postGain 可调 / +3dB 折中，
  引用 #125/#108/#153/`e9dde17`，明确「非 bug、功能未失效」）。需用 classic PAT 提交
  （fine-grained PAT 对第三方仓库 403）。
- **重要更正**：`96b45bd`(+6dB) 与 `e9dde17`(回退 0/0) 都是**上游自己的提交**；
  `bd9adc7` 只是同步上游把 0/0 带进 fork，并非 fork 主动回退。上游 0/0 是针对 #153
  「开均衡首次播放变电流声」的有意修复。

**2026-11-19 新增：可调「音量增益」设置（5e6756d）**

- 触发：用户反馈《别怕我伤心》开启音量均衡后仍很小声。排除法实测：
  `STREAM_MUSIC 100/100` 未静音、开关 `true`、`Effect ID 267 Enabled y`、
  `threadLoop` 持续增长、AudioTrack `G db=0` 无 ducking、logcat 无打断事件
  → 功能正常，根因是 **+3dB 对低电平音源不足**，且 MBC(2:1 / -16dB)
  只压超阈值部分、对低电平信号不抬升。功能定位是「削弱响的歌」，不是放大轻的歌。
- 实现：设置页「音量增益」5 档（0 / +3 / +6 / +9 / +12 dB），**默认 +3dB**
  （不回退现有听感；0dB 对应上游行为）；`settings.volume_gain_db` 持久化；
  Dart → MethodChannel → `MbcBand.postGain`，会话复用时直接重设参数、无需重建 effect。
- MuMu 实测：`VolumeNormalization: configure: ... gainDb=3.0/6.0/9.0 session=41`
  逐档下发、SP 写入 9.0、force-stop 重启后首条即 9.0、effect chain 保持挂载、无 FATAL。
- 局限：MuMu 无法客观测听感/削顶，真车机需按音源自调；+12dB 大概率触发 limiter。
- 车机全屏原生 `CarPlayerActivity` 的「更多」面板**暂未加**此入口（当前仅设置页），
  如需可后续补 bridge 事件接到 `PlayerController.setVolumeGainDb`。

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

**领先上游提交的结构**（此前 26 个，本轮 2 提交后共 28 个）：

| 类别 | 提交 |
|---|---|
| release 里程碑（带 tag，绝不可改写） | `fdad8bd`(20261) → `2167769`(20262) → `bd9adc7`(20263) → `c159f35`(20264) → `9e63193`(20265) |
| merge 上游 | `25e6903` → `dd05918`(60bd37d) → `5757ff1`(ced8c47) → `330c506`(60a5f04) |
| 功能/性能 | `d02b51d` 渲染节流 → `bec1aa8` 黑胶 v6 + 停跟踪 pkkuwo → `49066eb` 停跟踪旧 APK → `2a49664` 测试适配 → `79b6ccc` 车机差集功能 → `02c8bed` 音量均衡 +3dB → `5e6756d` 可调音量增益 |
| release 准备 | `3773922` 版本号 20266 → 本轮 `2026.11.19+20267` 版本推进 |
| docs/chore | `62121c1`/`ce9b2d3`/`63347d1` 交接文档、`5d5ace6` lock 刷新、`6e2ef74` .gitignore 忽略根目录 apk、`8e76394` 差集快照、`765b866` 增益补偿快照、20267 整理文档、本轮增益功能文档 |

> 这些提交全部已 push 到 origin。**不要再做「领先 N 就压缩」**：squash 必须 force-push，
> 违反 §7.3（改写已发布历史 + 破坏 5 个 release tag + 扰乱与 upstream 的 merge 基准）。
> GitHub 显示「领先 28」无任何实际代价。上游活跃期「落后」提示会反复出现，属正常。

**2026-11-16 上游合并记录（两批，共 21 个上游提交）**：

1. **60bd37d**（10 提交）：收藏按钮含系统媒体通知、排行榜页、播放恢复/队列重建/搜索竞态修复、R8 开启。冲突 2 处：`albumSongs` 双语义并存（`fetchAll:true` 与 `pageSize:5000` 都可用）；`ios-build.yml` 恢复+改手动触发。
2. **60a5f04**（11 提交）：来电打断误恢复（#175）、蓝牙断开防外放、桌面歌词黑屏（#174）、车载蓝牙歌词标题同步（#166）、下载元数据（#176）。冲突仅 update.md；上游 30 个新测试在 fork 挂（Fake 缺 `errorStream` stub、车机黑宿主架构），已适配。

- CI 决策：`ios/windows-build.yml` 仅手动触发（计费 + 配额）；`flutter-ci.yml` 无 push 触发。
- R8 fullMode + shrinkResources 开启：APK **31.4 MB → 25.2 MB**（-20%），原生层静态引用安全。

线上最新 Release：**v2026.11.20.2**（2026-11-20 发布，versionCode 20281），资产 `app-release.apk` 26,500,897 B，正式签名。此前 v2026.11.19（20267）与 v2026.11.20（20273~20278）均未单独发版，全部并入 `v2026.11.20` 累计发布；`v2026.11.20.1`（20280）、`v2026.11.20.2`（20281）为其后的同日修订版。

已发布版本链（每个 tag = 一次发版）：

| tag | versionCode | 主要内容 | 发布方式 |
|---|---|---|---|
| v2026.10.10 | 20261 | 原生车机全屏播放器首版、CI 省额度、8 图标 rounded、版本号规范 | tag→CI |
| v2026.10.14 | 20262 | 网络兜底（等网续播 / 智能降音质）、同步上游本地歌词匹配与歌单导入 | tag→CI |
| v2026.10.28 | 20263 | 同步上游 3.2.0/3.3.0、CI 去 iOS、`await` 编译修复、本地构建脚本 | 本地构建 + 手动发布 |
| v2026.10.29 | 20264 | 专辑详情无歌修复、车机黑胶唱片按网易云样式复刻 | 本地构建 + 手动发布 |
| v2026.10.31 | 20265 | 黑胶重做（深黑盘体+窄叶方向光+极简外缘）、停止跟踪 `pkkuwo/` | 本地构建 + 手动发布 |
| v2026.11.20 | 20279 | 累计发版：实时响度 AGC 全链路、车机全屏差集、上游功能合入、R8 裁剪 | 本地构建 + 手动发布 |
| v2026.11.20.1 | 20280 | 修复打开音量均衡开关未主动申请 `RECORD_AUDIO`（同日修订版） | 本地构建 + 手动发布 |
| v2026.11.20.2 | 20281 | 四轮 AGC 修复（歌内突变 / P80 / 开场快测一次给足 / +15 上限）+ 复审三处逻辑缺陷收口（同日修订版二） | 本地构建 + 手动发布 |

**版本链说明**：`3773922`（20266）→ … → `79b6ccc`（车机差集）→ `02c8bed`（音量均衡 +3dB）
→ 20267 版本推进与仓库整理 → `5e6756d`（可调音量增益）等，最终全部随 `v2026.11.20`
一起发版（versionCode 20279）；`v2026.11.20.1` 为该版之后的同日单项修订，`v2026.11.20.2` 为再之后的累计修订（四轮 AGC 修复 + 复审收口，20281）。

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

- [x] ~~发版 20281~~：**已于 2026-11-20 发布**（同日修订版二 tag `v2026.11.20.2`，tag object `a3e2ff4e`，Release id `452385977`，versionCode 20281，资产 md5 `c4d60dbf…`，latest）。因 `v2026.11.20`、`v2026.11.20.1` tag/Release 已占用且按 §7.3 不得改写已发布历史，故新建 `v2026.11.20.2`，上两版保持不动。
- [x] ~~发版 20280~~：**已于 2026-11-20 发布**（同日修订版 tag `v2026.11.20.1`，Release id 452112971，HEAD `2cb77ce`，正文 900 字符带 markdown，资产 md5 `9a19172c…`）。因 `v2026.11.20` tag/Release 已占用且按 §7.3 不得改写已发布历史，故新建同日修订版 tag，上一版保持不动。
- [x] ~~下次发版 20265~~：**已于 2026-10-31 发布**（tag `v2026.10.31`，Release id 429646924，正文带 markdown）。
- [x] ~~下次发版 20273~~：**已于 2026-11-20 发布**（tag `v2026.11.20`，Release id 451755386，HEAD `f5a14df`，versionCode 20279，正文带 markdown）。以下为当时的发版清单，留作历史记录。含此前 20267 的全部 ①全屏页渲染节流（`d02b51d`，§3.5）②两批上游合并（收藏/排行榜/R8/打断修复/蓝牙防外放/下载元数据）③R8 裁剪 ④车机全屏差集 5 项功能（`79b6ccc`）⑤音量均衡恢复增益补偿 +3dB（`02c8bed`）⑥可调音量增益 0~+12dB（`5e6756d`）⑦实时响度自动增益 AGC（`6469f47`）⑧AGC 设置菜单假死修复（`c1b8967`）⑨auto/fixed 策略切换卡顿优化（`5bec594`）⑩实时增益自动轮询（`9ea8b4a`）⑪自动增益方向错乱修复（`df0a18e`）⑫**官方 PEAK_RMS 测量替换 8bit 波形手算**（`c1c2cbf`，测得值准确性根治，MuMu 日志实测验证）。剩：真车机实测过门（含 AGC 逐曲听感与削顶、菜单连点、策略切换手感、轮询数值变化、响/轻歌增益方向） → 打 tag `v2026.11.20` → 用本地 APK（md5 `663f1786…`）发 Release。**发版门（历史表述，语义已过时）：真车机实测悬浮视频流畅 + 来电/蓝牙断开行为 + AGC 自动增益听感（现行语义为「偏轻的歌补增益、正常/偏响的歌保持原样」，非「响歌减增益」）+ 设置菜单不假死 + 策略切换不卡顿 + 轮询刷新数值**。注：真车机逐曲听感仍未实测，MuMu 已覆盖安装、效果链与 AGC 日志实证。
- [x] ~~下次发版 20272~~：已被 20273 覆盖，不再单独发版（2026.11.20 未打 tag）。
- [x] ~~下次发版 20271~~：已被 20272 覆盖，不再单独发版（2026.11.20 未打 tag）。
- [x] ~~下次发版 20270~~：已被 20271 覆盖，不再单独发版（2026.11.19 未打 tag）。
- [x] ~~下次发版 20269~~：已被 20270 覆盖，不再单独发版（2026.11.19 未打 tag）。
- [x] ~~下次发版 20268~~：已被 20269/20270 覆盖，不再单独发版。
- [x] ~~下次发版 20267~~：已被 20268/20269 覆盖，不再单独发版。
- [x] ~~合并后 MuMu 实测（R8 首开风险点）~~：2026-11-16 于车机实例（Android 15）冒烟全部通过——冷启 968ms、Flutter 分栏首页/排行榜/专辑详情正常、原生全屏页黑胶/歌词/控制完整、收藏变实心、帧率 19.5fps、无崩溃。详见 `memory/2026-11-16.md`。
- [ ] 渲染节流仍需**真车机**实测悬浮视频是否恢复流畅（MuMu 19.5fps 已验证节流生效）；未通过则降 tick 至 12Hz（旋转每帧 0.9°）。
- [ ] 若车机实测仍嫌卡：下一档可动歌词切行滚动动画频率（§3.5 末段），或把 tick 降到 12Hz（旋转每帧 0.9°，仍在可接受范围）。
- [x] ~~领先提交精简~~：2026-10-31 做了一次「外科式压缩」15→8（保留全部 5 个 release 里程碑及其 tag；只压缩中间过程提交，tree 逐字节不变）。备份分支 `backup-pre-squash-20261031` 等 3 个旧分支及全部 `pre-*` tag 已于 **2026-11-19 清理**（SHA 归档见 §13）。
- [x] ~~仓库里仍跟踪着两个 29.7 MB 的历史 APK~~：2026-11-16 已 `git rm --cached`（`49066eb`）。历史 blob 仍在，彻底清需 git-filter-repo —— **不要做**（破坏 release tag）。
- [ ] 黑胶观感仍属主观调优项，当前基线为 v6（§3.4）；若要再改，先渲染候选图供用户选，再动代码。
- [ ] 性能回归检查：改动任何周期刷新逻辑后，用 §3.5 的 gfxinfo 差值法复测帧率，目标 ≤25fps。
- [ ] Actions 免费额度常被耗尽（曾出现 "Actions has been disabled"），因此**本地构建是主路径**，CI 为备用。
- [ ] 20263 起不再产 iOS 包；如需 iOS，要恢复 `ios-build.yml` 并重新验证。
- [ ] 上游（`umr-xiaomai`）仍在活跃提交，定期 `git fetch upstream` 看是否有值得并入了的功能（本 fork 的播放器页与上游冲突面较大，merge 时留意 `player_page.dart` / `player_controller.dart`）。
- [x] ~~向上游反馈音量均衡效果偏弱~~：2026-11-19 已提交 `umr-xiaomai/kgka_Music_hl#181`（改进建议，非 bug 报告）。若上游采纳可调 postGain，再考虑提最小 PR。
- [x] ~~仓库整理（备份 tag / 旧分支）~~：2026-11-19 清理完成，详见 §2 与 §13；在线状态与 Actions 配额已核对。

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

---

## 13. 历史备份引用归档（2026-11-19 清理）

以下引用已删除（本地 + origin），SHA 仅作追溯用；删除前已确认这些分支是历史重写前的
旧快照，内容均由 master 上的新提交覆盖，无独有代码。`v*` 发布 tag 全部保留。

**已删 `pre-*` 备份 tag（本地 9 个；origin 仅 `pre-squash-20261010/1011` 两个）**：

| tag | commit SHA |
|---|---|
| pre-merge-lyrics-20261029 | `792e74f` |
| pre-merge-upstream-20261012 | `bd4ca8d` |
| pre-merge-upstream-20261116 | `ce9b2d3` |
| pre-merge-upstream-20261116b | `5757ff1` |
| pre-rewrite-20261029 | `50448b3` |
| pre-squash-20261010 | `a6b391e` |
| pre-squash-20261011 | `760f882` |
| pre-squash-net-trio-20261014 | `fcf3ace` |
| pre-squash-trio-20261012 | `e0630d7` |

**已删旧分支（本地 3 个；origin 仅前两个）**：

| 分支 | SHA | 说明 |
|---|---|---|
| backup-pre-squash-20261031 | `ce66305` | 2026-10-31 压缩前备份，领先 master 10 提交 |
| feat/perf-kuwo | `4536462` | 10 月车机播放器早期实验分支，领先 3 提交 |
| tidy-net | `5cef6a9` | 10 月网络兜底分支，领先 7 提交 |

注：三个分支 `--merged master` 均为 false（历史重写导致 SHA 断链），但 tree diff 中的
「大量删除」是 master 后来新增文件在旧快照上不存在所致，非独有功能丢失。
