# 协同开发手册 / CONTRIBUTING

适用仓库：**仅 `plugin-members`**。兄弟仓库 `theme-nucma`、`plugin-link-submit-next` 各有自己的 `CONTRIBUTING.md`；仓库边界、职责与分支模型见本仓 [COLLABORATION.md](COLLABORATION.md)。

本文是本仓流程与规则的唯一入口，自身完整、不依赖维护者本机的其他文档。与 README 冲突时以本文为准；与代码里已有的既定契约冲突时，先提 issue 讨论，不要单方面改契约。

---

## 0. 三条底线

1. **改前先同步，改后先自检**——基于过期代码改动是本项目冲突的第一来源。
2. **协作者不碰 `main`**——一律开分支提 PR；`main` 是保护分支。
3. **只改源码，不动生成物**——`src/main/resources/console/**`、`static/**`、`widget/src/*.iife.js` 由构建任务产出，手改必冲突。

任何一条做不到，宁可不提交，也不要"先推上去再说"。

---

## 1. 角色与权限

| 角色 | `main` 权限 | 要求 |
|---|---|---|
| 维护者 | 可直接 push | 每次发版必须打 annotated tag，并写 CHANGELOG |
| 协作者 | 禁止 push | 开分支 → 推送 → 提 PR → 维护者 review 后合并；无例外 |

协作者不负责打 tag、不建发行版、不改版本号（除非 PR 明确就是要发版，且维护者已指定版本号）。

---

## 2. 环境与拉取

### 2.1 版本要求

| 项目 | 要求 | 说明 |
|---|---|---|
| JDK | **21** | 用仓库自带 `./gradlew`，**不需要**全局装 Gradle |
| pnpm | 10.22 | 由 Gradle 的 node 插件自动装到项目内，不必全局安装 |
| Halo | ≥ 2.27.0 | `plugin.yaml` 的 `spec.requires` |

（主题仓库需要 Node.js ≥ 22.12，见 theme-nucma 仓手册。）

### 2.2 目录布局（联调时必须并列）

```
<任意父目录>/
├── theme-nucma/
├── plugin-members/
└── plugin-link-submit-next/
```

三者不嵌套。Windows 下路径不要带空格。

### 2.3 拉取

```bash
git clone https://gitee.com/nucma/plugin-members.git
```

### 2.4 远程约定（重要）

| 平台 | 地址形态 | 用途 |
|---|---|---|
| **Gitee（主）** | `gitee` → `https://gitee.com/nucma/plugin-members.git` | 日常协作、分支、PR、发版，**全部在这里** |
| GitHub（镜像） | `origin` | 只读分发，由 Gitee 的 Push 镜像自动同步（约 5 分钟） |

两条铁律：

- **不要推 GitHub。** GitHub 侧由 Gitee 镜像强制覆盖，直推的提交下次同步就被冲掉。
- **不要在 GitHub 提 PR。** 同上，会被镜像覆盖。

镜像只同步**分支与 tag**，不同步 Release 资产——GitHub 上没有 jar 属正常现象。

---

## 3. 标准流程（六步）

### 第 1 步：同步

```bash
git pull --rebase gitee main
```

- 有未提交改动时先 `git stash -u`，拉完立刻 `git stash pop` 并处理冲突。
- **拉取失败不得继续改动**，先报告，不要在冲突状态上叠改动。

### 第 2 步：开分支

```bash
git switch -c feat/members-batch-load
```

命名：`feat/xxx`、`fix/xxx`、`docs/xxx`、`refactor/xxx`。一个 PR 一件事，不混装无关改动。

### 第 3 步：改源码

遵守第 4 节的硬约束。涉及版本敏感 API（Finder、扩展点、权限、`spec.requires`、通知模板）时，**先查官方文档与目标 Halo 版本源码**，不凭记忆猜字段。

### 第 4 步：自检

```bash
./gradlew cleanTest test
```

测试必须全绿；新增功能必须带单测。

### 第 5 步：出包

```bash
./gradlew.bat build -x test
```

`-x test` 只允许在测试已单独通过后用于打包，**不能替代测试**。产物：`build/libs/plugin-members-<ver>.jar`。

控制台前端由 Gradle 关联任务自动构建并打进 jar（`buildFrontend` → `processResources` → `jar`），**不要**手工跑 `pnpm build` 再拷 `src/main/resources/console`——顺序错了会让 jar 里留着上一版控制台，表现为「代码改了、jar 也打了，线上还是旧界面」。

**装到本地 Halo 验证时不能直接替换 jar**：把新 jar 丢进 `data/plugins/` 后 pf4j 不会加载（插件仿佛消失、端点 404）。必须走控制台接口覆盖安装：

```bash
# 先停用，再带 jar 调 upgrade（接口内部完成停用 → 替换 → 启用）
POST /apis/api.console.halo.run/v1alpha1/plugins/PluginMembers/upgrade   # multipart，字段 file=@build/libs/plugin-members-<ver>.jar
```

装完核对 `hostname/.plugin.halo.run/plugins/PluginMembers` 的 `spec.version` 是否为新版本、`status.phase` 是否为 `STARTED`。接口细节与认证方式见 Halo 官方《插件开发 · 本地调试》。


### 第 6 步：提交与 PR

提交格式见第 6 节，然后：

```bash
git push gitee HEAD
```

到 Gitee 提 PR 到 `main`。**不要**自行合并，也不要 `--force` 推别人的分支。

---

## 4. 改动中的硬约束

### 4.1 只改源码

以下**只能由构建任务生成**，禁止手改、禁止手工合并：

| 生成物 | 来源 |
|---|---|
| `src/main/resources/console/**` | `ui/` 经 rsbuild 构建 |
| `src/main/resources/static/member-apply-widget.*`、`static/vendor/jsQR.js` | `widget/` 构建 |
| OpenAPI / 生成器产物 | 生成器 |

### 4.2 插件后端（WebFlux）

- 事件循环内不做 DNS、阻塞 IO、`.block()`、全表排序；需要阻塞段时整体 `Mono.fromCallable(...).subscribeOn(boundedElastic)`，并加超时。
- Extension / Reconciler 注意生命周期，任务与监听器要能清理；Reconciler 内的响应式调用统一 `block(5s)` + 降级。
- 公开 API 返回 RFC 7807 错误；匿名角色只开必要能力并加限流；成员敏感字段（email 等）不得从匿名端点外泄。
- **匿名端点不得接受任意 URL**。需要替前端转发外部资源时（如 `plugins/PluginMembers/avatar` 代理 QQ 头像），必须：协议/主机/路径三重白名单、禁止跳转、限制体积与超时、结果做局部缓存；白名单逻辑放 `utils/` 下单独类并配单测，端点只负责取字节与响应头。
- 判重、筛选优先下推索引（`ListOptions` + `equal(...)`），不要全表 `listAll` 后内存过滤；索引重建期查询失败要回退全表。
- 缓存只缓存不可变副本，写入方负责失效；失效要按 key 精确清，不要全 pattern 扫。

### 4.3 前端（ui / widget）

- `ui/` 组件异常不得被 Dialog 吞掉，批量操作走后端单接口。
- **查询键只写在 `ui/src/api/query-keys.ts`**：失效是按前缀匹配字符串的，键写错不会报错，只会让 `invalidateQueries` 静默变成 no-op（列表不刷新，且没有任何日志）。变更成员后统一调 `invalidateMemberViews()`，只涉及分组时调 `invalidateMemberGroupViews()`——分组视图的 queryFn 里还取了分组列表，只失效分组键同样刷不动。
- **接口返回字段以 `ui/src/api/index.ts` 的类型为准**：直接 `axiosInstance.post` 拿 `any` 时，字段名打错（如把 `success` 写成 `succeeded`）编译期发现不了，运行期只表现为「永远是 0」。新接口先补到客户端类型上再调用。
- **控制台权限**：管不到元素的场景（tab、菜单项这类数据）用 `composables/use-ui-permissions.ts` 的 `can()`；它读 `/users/-` 的角色 `ui-permissions`，不要依赖 `stores.currentUser()`——插件上下文里实测取不到，判断会一直落到「放行」。
- **每条写操作都要有失败出口**：`useMutation` 必须有 `onError`，`Dialog.warning({ onConfirm })` 里必须 `try/catch`（对话框在 onConfirm 抛错时只会静默关闭，站长会以为操作成功）；提示文案统一走 `utils/error.ts` 的 `errorMessage(error, fallback)`，它按 RFC 7807 取 `detail`/`message`/`title`，**不要把 axios 的英文 `Request failed with status code 400` 直接贴给用户**。失败时不清空已选项，方便重试。
- **枚举状态要三处同时补齐**：`MemberBadge` 的文案、列表页的 `statusText`/`statusState`、筛选下拉的 `statusOptions`。只补一处的结果是「徽标显示撤回中、列表显示未知、还筛不出来」——状态流转在控制台上断链。
- **写进 `spec.groupName` 的必须是分组资源名**：可以是显示名让用户选，但落库前要换成 `metadata.name`（参照 `MemberBatchServiceImpl#resolveGroupNames`）。写显示名不会报错，只会在前台按分组聚合时归入「未分组」。
- 版本跨大版本的依赖升级（如 vue-router 大版本）需单独 PR 并回归路由。

### 4.4 行尾

`.gitattributes` 已统一 `* text=auto eol=lf`（`*.bat` / `*.cmd` 为 CRLF）。不要把编辑器产生的整文件 CRLF 转换提交进去；误转用 `sed -i 's/\r$//'` 还原，再 `git diff --stat` 确认没有整文件 diff。

---

## 5. 验证

| 场景 | 命令 |
|---|---|
| 全量测试 | `./gradlew cleanTest test`（**没有** `:ui` 子项目，不要加 `-x :ui:...`，会直接失败） |
| UI 类型检查 | 包含在构建流程内 |
| 打包 | `./gradlew.bat build -x test` |

### 5.1 集成验证矩阵（至少覆盖）

1. 未安装 theme-nucma → 插件默认页仍可独立渲染。
2. 插件停用 → 主题侧成员区块降级，不报错。
3. 匿名 / 登录 / 管理员 → 只能访问各自授权范围；匿名端点限流生效。
4. **只读账号**（仅 `role-template-member-view`）实登一次控制台：成员页、申请记录页不出现管理入口，撤回记录 tab 不出现、带 `?tab=withdrawals` 进入能回落到提交记录，全程没有 403 文案。
5. 邮件通知：仅状态变化时发送，普通资料编辑不重复发。
6. MCP 工具：查询需查看权限、写入需管理权限，`dryRun` 默认 true。

> 造只读账号：`POST /apis/api.console.halo.run/v1alpha1/users`（**扁平**载荷 `{name, displayName, email, password, roles}`，`spec` 包裹会被拒），再 `POST .../users/{name}/permissions` 设 `{roles:["role-template-member-view"]}`。登录有频率限制，联调时把 SESSION 存下来复用，别反复登录。

---

## 6. 提交与 PR 规范

### 6.1 提交信息

格式：`type(scope): 简述；版本号`

```
fix(members): 修复并发审核互相覆盖；0.0.2
feat(members): 批量导入支持去重
```

`type`：`feat` / `fix` / `docs` / `refactor` / `chore` / `test`。
纯文档改动不必带版本号。

### 6.2 PR 描述模板

```markdown
## 改了什么
（一两句，指向具体类 / 模块 / 端点）

## 为什么
（对应 issue 或现象；复现步骤）

## 验证
- [ ] ./gradlew cleanTest test 通过（新增逻辑带单测）
- [ ] 已出完整 jar
- [ ] 匿名 / 登录 / 管理员三态权限验证
- [ ] 通知、缓存、限流等副作用已考虑

## 文档
README / CHANGELOG / docs 是否需要同批更新（需要则已在本 PR 内）
```

### 6.3 review 关注点（双方都看）

- 业务校验、限流、存储有没有漏到主题里。
- 生成物有没有被手改。
- 公开端点是否遵循 RFC 7807、是否限流、是否泄露敏感字段。
- 响应式链上有没有新增 `.block()`、有没有超时。
- 版本文件是否一致、`spec.requires` 是否诚实。
- 是否顺手改了无关文件（尤其是整文件 CRLF）。

### 6.4 合并后

删除远程分支；不要在共享分支上 `--force` 推送；不要 `rebase` 已推送的公共分支。

---

## 7. 版本与发版

### 7.1 三档级别（先判级，再选号）

| 级别 | 判定 | 典型改动 | 选号 |
|---|---|---|---|
| **大** | 体系级变化，破坏已有契约 | 删除 / 更名公开 API 与 Finder 字段、CRD 结构变更、抬高 Halo 最低版本、通知模板契约变更 | 升 `MINOR`，`PATCH` 归零（0.0.2 → **0.1.0**） |
| **中** | 新增能力或成批修复，不破坏契约 | 新端点、新设置组、新 MCP 工具、同批修 3 个以上问题 | 升 `PATCH`（0.1.0 → **0.1.1**） |
| **小** | 单点修复与打磨 | 单条 bug 修复、文案、依赖小版本跟进 | 升 `PATCH`（0.1.1 → **0.1.2**） |

只有会进入安装包的内容变化才升版本；README、规则文档、测试代码改动不强制升版本。

### 7.2 版本文件清单（任一处不一致不得发版）

> **版本号只在发版提交里动（硬规则）**：本仓版本文件在**日常功能 / 修复提交中一律不改**。版本号由维护者在发版时统一升（见 7.3 第 1 步）；协作者发现改动需要升号，在 PR 描述或 issue 里提即可，**不要动手改版本文件**——包含版本号改动的 PR 会被要求先摘掉再合。日常对齐以 tag 为锚点：开工先 `git pull --ff-only`，发版后两边对齐同一个 tag 即版本一致。

| 需同步的文件 | 校验方 |
|---|---|
| `build.gradle`、`src/main/resources/plugin.yaml`、`ui/package.json` | Gradle 构建校验三方一致，不一致直接失败 |

`plugin.yaml` 的 `spec.requires` 必须是**实际验证过**的最低兼容版本，不能按编译 SDK 推断。

### 7.3 发版七步（维护者）

1. 汇总改动 → 按 7.1 判级选号 → 同步 7.2 全部版本文件。
2. `./gradlew cleanTest test` → `./gradlew.bat build -x test`。
3. 本地 Halo 实例安装验证（安装、启用、配置、主要流程、停用、卸载）。
4. 追加 `CHANGELOG.md` 一条（版本号与 tag 一致，写清功能变化、修复、影响面）。
5. 提交并打 **annotated** tag：

   ```bash
   git add -A
   git commit -m "feat(members): 改动要点；x.y.z"
   git tag -a x.y.z -m "x.y.z"
   git push gitee main --follow-tags
   # 若被分支保护拒绝（protected branch）：不得解除保护，
   # 改推 git push gitee main:xiui → 开 PR xiui → main → 合并要三步（少一步 405）：
   #   POST .../pulls/{n}/review → POST .../pulls/{n}/test → PUT .../pulls/{n}/merge
   # 合并后 git fetch + git merge --ff-only origin/main 对齐
   ```

6. Gitee 建发行版，挂原始 jar，发布说明按「级别 + 影响面 + 验证结果 + 未验证项」写。
7. 校验资产：`curl -L` 下载一次比对字节数。**发行版 `assets[].size` 恒为 0，不能作为校验依据。**

### 7.4 铁律

- **tag 名直接用版本号，不加 `v` 前缀。**
- **必须 `-a`**：`--follow-tags` 只推 annotated tag，轻量 tag 不会被带走。
- **不跳号、不攒批**：一次发版一个 tag，中间版本缺失会让回退定位失效。
- **不覆盖已发布 tag**：已推送的 tag 不删不重打，修复只能发新版本。
- **回退用 `git revert`**，禁止 `reset --hard` 后强推。
- 版本号一旦发出即为对外契约，只增不改；有问题按级别发新版本。

### 7.5 当前版本

| 版本 | 下一版 |
|---|---|
| **0.0.2** | 中/小级 → 0.0.3，大级 → 0.1.0 |

覆盖安装：插件在「插件 → 安装」上传 jar，重复上传即为覆盖，设置与数据保留。

---

## 8. 文档同步（不做"下次一起改"）

| 文件 | 何时必须更新 |
|---|---|
| `README.md` | 新增端点、设置分组、MCP 工具、环境要求、仓库地址变化 |
| `CHANGELOG.md` | 每次发版追加一条，版本号与 tag 一致 |
| `docs/API.md` | 公开 / 管理 API 契约变化 |
| `docs/THEME-INTEGRATION.md` | 主题集成变量、Finder、Widget 契约变化 |
| `COLLABORATION.md` | 分支模型、发版流程、远端与权限变化 |
| `CONTRIBUTING.md`（本文） | 流程或硬约束变化 |

---

## 9. 完成定义（DoD）

一个改动只有全部满足才算"做完"：

- [ ] 自检通过（`./gradlew cleanTest test`），失败即未完成
- [ ] 新增逻辑带单测；公开端点考虑了权限、限流与敏感字段
- [ ] 响应式链无新增 `.block()`、阻塞段有超时与降级
- [ ] 安装包内容有变化时：版本号按 7.1 判级、7.2 全部文件同步、出了完整 jar
- [ ] 插件缺失 / 停用 / 版本不满足三态下主题侧不报错
- [ ] `CHANGELOG.md` 已追加，相关 `README` / `docs` 同批更新
- [ ] 提交信息合规，PR 描述含验证结果
- [ ] 发版后：annotated tag 已推 Gitee、发行版已挂资产并做过字节校验
