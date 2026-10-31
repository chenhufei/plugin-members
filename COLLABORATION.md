# 协作说明 / Collaboration Guide

本目录是一个独立 git 仓库。`plugin-members` 与 `theme-nucma`、`plugin-link-submit-next` 三者互不嵌套，各自连自己的远端，协作以**仓库为单元**进行。

## 仓库边界与职责

| 仓库 | 类型 | 构建命令 | 产物 | 职责 |
|---|---|---|---|---|
| `theme-nucma` | Halo 主题 | `npm run package` | `dist/theme-nucma-<ver>.zip` | 展示、布局、样式、调用插件公开 Widget / Finder |
| `plugin-members` | Halo 插件 | `./gradlew.bat build -x test` | `*.jar` | 成员业务、API、Widget、Finder、Console |
| `plugin-link-submit-next` | Halo 插件 | `./gradlew.bat build -x test` | `*.jar` | 友链自助提交、审核与辅助能力 |

主题**禁止**重做成员申请 / 友链提交业务；正式友链数据归官方 PluginLinks。详见根目录 `AGENTS.md` / `ARCHITECTURE.md`。

## 分支模型

- `master` / `main` 为**保护分支**：禁止直接 push，仅通过 PR / MR 合并。
- 基于保护分支开功能分支：`feat/xxx`、`fix/xxx`、`docs/xxx`。
- 每日开工先 `git pull --rebase`；收工前 push 自己的分支并提 PR，由对方 review 后合并。
- 合并前确保本地构建 / 检查通过（见下）。

## 构建与验收

- 主题：`npm install` → `npm run check` → `npm run build` → `npm run package`
- 插件：相关单元测试 `./gradlew test`；打包 `./gradlew.bat build -x test`（不得用 `-x test` 代替测试）
- 失败不得推送，更不得声称完成。

## 防冲突铁律（务必遵守）

1. **只改源码，生成物靠构建**：`theme-nucma/templates/assets/**`、`plugin-*/**/static/**`、OpenAPI / `ui/src/api/generated` 等由构建任务产出，**禁止手改或手动合并生成文件**——这是协作冲突与错误的主要来源。
2. **版本号谁动谁升并同步**：
   - `theme-nucma`：`package.json` + `theme.yaml`（`spec.version`）
   - `plugin-members` / `plugin-link-submit-next`：`build.gradle` + `plugin.yaml`（`spec.version`）+ `ui/package.json`
   - 同一处版本号同一时间只由一人修改；会进入安装包的内容变化才升版本（SemVer）。
3. **完整包交付**：会进入安装包的内容修改后必须生成完整包（zip / jar），只编前端资源不算完成。
4. **保护已有改动**：不覆盖 / 回退 / 删除他人未提交改动；冲突时保留原内容并协商。

## 提交规范

- 格式：`type(scope): 简述；版本号`
- 例：`fix(theme): 修复分组筛选失效；3.34.63` / `feat(members): 认领验证码归属校验；1.14.7`
- type：`feat` / `fix` / `docs` / `refactor` / `chore` / `test`

## 协作者接入

### 仓库地址

| 平台 | 本仓库地址 | 用途 |
|---|---|---|
| **Gitee（主）** | `https://gitee.com/xiui-cc/plugin-members.git` | 日常协作，分支与 PR 都在这里 |
| GitHub（镜像） | `https://github.com/chenhufei/plugin-members.git` | 只读分发，由 Gitee 镜像自动同步 |

> **只在 Gitee 提 PR。** GitHub 端由 Gitee 的 Push 镜像强制覆盖，直接推到 GitHub 的提交会在下次同步时被冲掉。

同级的另外两个仓库：

- `https://gitee.com/xiui-cc/theme-nucma.git`
- `https://gitee.com/xiui-cc/plugin-link-submit-next.git`

### 步骤

1. 在 Gitee 上申请本仓库协作者权限（由维护者在「仓库 → 管理 → 仓库成员管理」邀请，角色选**开发者**），或直接 fork。
2. 把三个仓库 clone 到**同一父目录**（三者互不嵌套，但联调时需要并排放置）：

   ```bash
   git clone https://gitee.com/xiui-cc/theme-nucma.git
   git clone https://gitee.com/xiui-cc/plugin-members.git
   git clone https://gitee.com/xiui-cc/plugin-link-submit-next.git
   ```

   Windows 下可用工作区脚本一次拉齐：`.\scripts\clone-all.ps1 -Owner xiui-cc`

3. 环境要求：主题需 Node.js ≥ 22.12；插件需 JDK 21（用仓库自带的 `./gradlew`，无需全局安装 Gradle）。
4. 可选：挂一个 GitHub 远程，方便对照上游

   ```bash
   git remote add github https://github.com/chenhufei/plugin-members.git
   ```

5. 通读本文件与仓库 README。`AGENTS.md` / `ARCHITECTURE.md` 位于维护者的主工作区，不随本仓库分发；其核心约束已在前面的「防冲突铁律」中列出。

