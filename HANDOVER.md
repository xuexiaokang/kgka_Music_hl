# KA-music 交接文档

> 生成时间：2026-10-01
> 目的：方便其他工具/人接力本项目。本文件只描述**事实与规则**，不含任何敏感凭据。

---

## 1. 项目是什么

- **名称**：KA-music（kgka_Music_hl）
- **性质**：Flutter 音乐播放 App，fork 自 `umr-xiaomai/kgka_Music_hl`
- **本机克隆**：`E:\QClaw\KA-music`
- **GitHub fork**：`https://github.com/xuexiaokang/kgka_Music_hl.git`
- **GitHub 上游**：`https://github.com/umr-xiaomai/kgka_Music_hl.git`
- **上游默认分支**：`master`（不是 main）

---

## 2. 仓库当前状态（2026-10-01）

### 2.1 主分支
```
master = 50e5091 chore: 版本号改用日期格式 2026.09.18（fork 发布策略）
```
- 相对 `upstream/master`：**ahead 6 / behind 0**
- 相对 `origin/master`：**0 / 0 同步**
- 工作区干净，无未提交变更

### 2.2 历史已做「强制归零」
2026-09-30 把 master 重置到 `upstream/master (086052c)`，再 cherry-pick 6 个有效私有提交，清掉 6 个已被上游收编/废弃/抵消的历史提交。**功能代码与上游逐字节一致**，仅剩 5 个 fork 私有文件差异。

### 2.3 6 个有效私有提交（按时间正序）
| SHA | 内容 |
|---|---|
| `631f2e5` | ci: 新增 Android APK 构建 workflow |
| `ae5bad4` | chore: 将 buildKey.keystore 移出 git 跟踪 |
| `15c23d2` | chore: gitignore 忽略 build-artifacts |
| `3c61178` | ci: release 构建使用固定签名 |
| `2a58280` | ci: 修复 workflow YAML 语法 |
| `a8ecf41` | chore: 版本号改用日期格式 2026.09.18 |

### 2.4 相对上游的 5 个文件差异
```
.github/workflows/android-build.yml   (+107)   新增 CI workflow
.gitignore                            (+3)     忽略 build-artifacts、*.keystore
buildKey.keystore                     (删除)   签名私钥出库
lib/config/app_config.dart            (4 行)   版本号
pubspec.yaml                          (2 行)   版本号
```

### 2.5 备份分支
| 分支 | SHA | 说明 |
|---|---|---|
| `backup-dd48ef3-pre-reset` | `dd48ef3` | 强制归零前的完整历史（**已推到 origin**，远端保险） |
| `backup-b559a16-pre-cleanup` | `b559a16` | 仓库整理前的快照（本地） |

---

## 3. 远程与凭据

### 3.1 remote 配置
```
origin    https://github.com/xuexiaokang/kgka_Music_hl.git   (fetch)
origin    https://github.com/xuexiaokang/kgka_Music_hl.git   (push)
upstream  https://github.com/umr-xiaomai/kgka_Music_hl.git   (fetch)
upstream  no_push                                            (push)
```

⚠️ **upstream 的 push 已被物理禁用**（`git remote set-url --push upstream no_push`）。`git push upstream ...` 会直接报 `fatal: 'no_push' does not appear to be a git repository`。`git fetch upstream` 不受影响。

### 3.2 推送凭据（关键）
- 全局 gitconfig **没有** `credential.helper`，裸 `git push` 会失败
- **必须**用：`git -c credential.helper=store push ...`
- 有效 token 在 `~/.git-credentials.bak`（fine-grained PAT，`github_pat_` 开头，97 位）
- `~/.git-credentials` 里的 `ghp_` token **已失效，勿用**
- 仓库内 `build/cred_bak.txt`、`build/tok.txt` 等是历史抓取留下的 token 明文文件，**不应提交、不应再读取**

### 3.3 上游权限
- 本账号对 `umr-xiaomai` **无写权限**，`git push upstream master` 会 403
- 向上游贡献只能走 PR，且必须从 `upstream/master` 最新提交新建干净分支、只移植功能代码

---

## 4. 项目规则（铁律）

### 4.1 操作必记日志
- 所有实质工作（代码、CI、PR、release、排查、脚本）完成后，必须在本机项目工作区的 `memory/YYYY-MM-DD.md`（每日日志，append-only）追加记录
- 跨项目偏好写入 `~/.workbuddy/MEMORY.md`
- 日志内容：做了什么、关键命令/文件路径、结论、踩坑教训、待办

### 4.2 fork 私有改动不得提上游 PR
以下内容**只允许存在于 fork**，不得走上游 PR：
- CI workflow（`.github/workflows/android-build.yml`）
- 签名私钥出库（`buildKey.keystore`）
- `.gitignore` 项目规则
- 版本号日期化

### 4.3 同步上游的方式
用 `git merge upstream/master`（保留历史，不用 rebase）。冲突源（本地圆盘设计）已消除，今后 merge 基本不会冲突。

### 4.4 提上游 PR 的既有策略
从 `upstream/master` 最新 commit 新建干净分支，只移植对应功能代码，不带 fork 私有内容。

---

## 5. 工具与环境

### 5.1 工作目录
| 用途 | 路径 |
|---|---|
| 代码仓库 | `E:\QClaw\KA-music` |
| 会话工作区（日志/记忆/截图） | `C:\Users\XXK\WorkBuddy\代可行` |
| 记忆日志 | `C:\Users\XXK\WorkBuddy\代可行\memory/YYYY-MM-DD.md` |
| 长期记忆 | `C:\Users\XXK\WorkBuddy\代可行\MEMORY.md` |

### 5.2 本机无 dart/flutter 环境
- 编译验证**全部靠 CI**（`.github/workflows/android-build.yml`）
- 括号平衡校验用 `build/check_parens.py`
- 临时脚本放 `build/`（已 gitignore）

### 5.3 Android 构建（若本机要编）
- 必须用 **JDK17**：`C:\Android\jdk17\jdk-17.0.20.1+1`
- 系统默认 gradle JVM 是 JDK25，会报 `JdkImageTransform` 错误
- 规范命令：
  ```
  gradle -Dorg.gradle.java.home="C:/Android/jdk17/jdk-17.0.20.1+1" :app:assembleDebug --rerun-tasks -x lint
  ```
- JDK 下载走清华镜像 TUNA（官方 api.adoptium.net 大文件常 exit56 中断）

### 5.4 CI 产物
- 构建产物在 `build-artifacts/`（已 gitignore）
- 历史 APK：`kgmusic_android_2026.09.20_review-fix.apk`（29.6MB，review 修复版）
- GitHub Actions artifact 下载是 302 跳 Azure Blob SAS，需两步法（NoRedirect 取 Location，再不带 Authorization 下载）→ 参考 `build/dl_follow.py`

---

## 6. 已完成的历史工作（摘要）

| 时间 | 事项 |
|---|---|
| 2026-09-19 | 修复车机歌词 BUG（PR #119），已被上游合并 |
| 2026-09-20 | 按上游 review 修复 #111（横屏矮分辨率）/ #118（广播层：内存泄漏 + 暂停元数据不刷新），交付 APK |
| 2026-09-21 | 复审闭环后重提 #111 / #118 PR，均 `mergeable_state=clean` |
| 2026-09-24 | 上游已合并 #111/#118/#121/#123；整理仓库：放弃本地圆盘设计，以 merge 合入上游 |
| 2026-09-30 | 执行「强制归零」：master 重置到上游基线 + cherry-pick 6 个私有提交，ahead 12→6；禁用 upstream push |

### 关键技术贡献（广播层根治）
- `positionStream` 回调不再 `notifyListeners()`，position 改由独立 `ValueNotifier`（`positionNotifier`）推送
- 消除 200ms 全量 rebuild 导致的卡顿
- 上游 review 要求补 `positionNotifier.dispose()` 及 `_Progress` 用 `Listenable.merge([player, positionNotifier])` 联合监听

---

## 7. 待办 / 未决

- [ ] 备份分支 `backup-b559a16-pre-cleanup`（本地）是否清理 —— 建议保留到下次发版验证通过
- [ ] `build/` 目录下大量历史脚本与 token 明文文件（`cred_bak.txt`、`tok.txt` 等）—— 已 gitignore，但本地留存；如需清理请明确指示
- [ ] 下次发版时更新 `app_config.dart` / `pubspec.yaml` 的版本号

---

## 8. 常用命令速查

```bash
# 查看状态
cd /e/QClaw/KA-music
git status -sb
git rev-list --left-right --count upstream/master...master   # ahead / behind

# 同步上游（保留历史）
git fetch upstream
git merge upstream/master

# 推送（必须带 credential.helper=store）
git -c credential.helper=store push --force-with-lease origin master

# 拦截测试（应失败）
git push upstream master
```