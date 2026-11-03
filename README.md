# 成员管理

> 为 Halo 提供成员分组、公示与访客自助申请，包含审核、撤回、通知、限流和主题集成能力。

成员管理适合高校联盟、社团、协会、校友组织和项目团队。它将成员资料、分组、公开展示和申请审核放在同一套 Halo 插件中，同时向主题公开 Finder 与申请 Widget，主题只需负责页面展示。

## 核心能力

### 成员与分组

- 在 Halo 控制台创建、编辑、筛选和批量管理成员。
- 使用成员分组组织公开名册；主题可按分组展示已通过成员。
- 支持头像、学校、QQ、联系邮箱、好友链接和成员背景等资料字段。
- 提供待审核、已通过、已拒绝、下架和撤回申请等状态与记录。

### 访客自助申请

- 内置 `MemberApplyWidget`，启用插件后会注入申请资源；主题或页面可调用 `MemberApplyWidget.open()` 打开表单。
- `/members` 页面可显示浮动申请入口，其他页面可按需要添加自己的按钮。
- 申请者可以选择允许申请的分组，自行填写昵称与学校，邮箱由 QQ 号自动拼接，也可以上传二维码解析 QQ 好友链接。
- 可选图形验证码（免 Cookie 的一次性 `challengeId`）用于拦截批量提交，关闭后既有集成方式继续可用。
- 支持默认分组、禁止申请分组、自动通过申请和自动处理撤回申请。

### 审核与通知

- 管理端支持通过、拒绝、重新通过、下架和撤回处理；拒绝或下架可以填写原因。
- 提交记录可按状态、分组、关键词和时间排序筛选，状态含「待审核 / 已通过 / 已拒绝·下架 / 撤回中」四类。
- 勾选多条记录可批量通过、批量删除；批量操作失败时会提示后端给出的原因并保留已选项，不会静默清空。
- 可选邮件通知覆盖新申请、审核通过、拒绝、下架、撤回验证码与撤回结果。
- 通知只在申请或审核状态发生变化时发出，不会因普通成员资料编辑而重复发送审核邮件。

### 成员认领

- 管理员为成员生成一次性认领链接（带 token 与有效期），成员打开后自助补全 QQ 加好友链接，无需管理员逐个代填。
- 认领验证方式可选图形验证码或邮箱验证码（发送到成员登记的 QQ 邮箱）；链接可列出与吊销。
- 认领与前台「补全 QQ 链接」共用同一套校验：`https://qm.qq.com/q/<token>` 或旧版 `cgi-bin/qm/qr?k=` 均可，其他域名一律拒绝。
- 认领链接列表支持勾选后批量作废，逐条下发删除并汇总结果，一条失败不影响其余链接。

### 关系分析

- 学校关联分析可按学校聚合成员并给出强弱结论，供主题的关系网连线使用；阈值与「是否展示依据」由主题侧配置。
- 分析需要 AI Foundation 能力；未安装或后台关闭时返回稳定错误码，不影响成员页其他功能。

### 安全与维护

- 匿名申请接口进行输入校验和按 IP 限流。
- 可选 IP / User-Agent 黑名单与空 User-Agent 拦截。
- 可选定时清理已拒绝或长期待审核的申请记录，并保留上次执行结果。

## 主题集成

插件公开 `memberFinder`，主题可在模板中调用：

```text
memberFinder.listApprovedMembers()
memberFinder.listAllGroups()
```

插件会在全站注入 Widget 的脚本和样式，浮动申请按钮仅在 `/members` 路由添加。自定义主题可以直接调用：

```html
<button type="button" onclick="MemberApplyWidget.open()">申请加入</button>
```

NUCMA 主题已适配成员公示、首页成员区块和申请入口。其他主题也可以仅消费 Finder 或 Widget，不需要复制插件表单逻辑。

插件自带 `/members` 默认页：主题没有 `templates/members.html` 时会自动回落并套用主题版式。成员页渲染时会写入 `ModelConst.TEMPLATE_ID`（`plugin:PluginMembers:members`），供 Head 处理器、SEO 与渲染扩展识别页面。完整的 Finder 契约、模型属性、模板覆盖规则与自检清单见 [docs/THEME-INTEGRATION.md](docs/THEME-INTEGRATION.md)。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Halo | `>= 2.27.0` |
| Java | 21，源码构建需要 |

## 安装与配置

1. 下载 `plugin-members-<version>.jar`。
2. 在 Halo 控制台进入“插件管理”，上传 JAR 并启用“成员管理”。
3. 先创建至少一个成员分组，再设置默认分组或允许申请的分组范围。
4. 在“申请审核”中选择人工审核或自动通过。
5. 需要邮件时，在“邮件通知”中开启发送并填写管理员邮箱；Halo 本身也需要配置可用的邮件通知能力。
6. 在主题或自定义页面中展示成员列表和申请入口。

## 设置说明

| 设置组 | 用途 |
| --- | --- |
| 申请审核 | 自动审核、默认分组、禁止申请分组和撤回处理 |
| 成员认领 | 认领链接有效期、自动通过、验证方式（图形验证码 / 邮箱验证码） |
| 安全防护 | 匿名提交限流、IP / User-Agent 黑名单与输入保护 |
| 消息通知 | 管理员提醒、申请人与审核结果通知 |
| 关系网分析 | 是否调用 AI 分析、分析维度、最多连线数；「画不画、阈值多少、是否显示依据」在主题的「成员页与关系网」组，两处都开才会出现连线 |

## 定时任务

控制台的「定时任务」用于清理过期申请记录（已拒绝 / 长期待审核），默认关闭，且**永远不会删除已通过的成员**。

执行周期可填 `@daily`、`@weekly` 等预设，也可填 6 段 Spring Cron（秒 分 时 日 月 周），例如 `0 0 3 * * *`。保存前会校验格式，不会再把写错的表达式静默存成一个永远不跑的任务；只有 `manage` 权限的账号能改这些字段。

## MCP 工具

插件提供 27 个 MCP 工具，供支持 MCP 的 AI 助手在授权下查询与维护成员数据，权威清单见 [API 文档 · MCP 工具](docs/API.md#mcp-工具)。

- 分级权限：查询类需「成员查看」，写入类需「成员管理」；成员本身不提供删除或合并，破坏性操作保持克制。
- 覆盖分组治理、撤回与认领令牌查询、数据质量巡检（`audit_member_data`）、批量更新（默认 `dryRun=true`）、学校关联分析与概览快照（`get_member_overview`）。
- MCP Server 1.3.x 只支持 tools、不支持 resources，因此用一次性快照替代 `members://` 资源读取。

## 权限与公开接口

插件提供“成员查看”和“成员管理”角色模板。匿名访问仅开放申请表单所需的分组查询和提交端点；管理和审核端点需要 Halo 权限。

请不要将管理员邮箱或内部成员资料写入主题模板、前端脚本或公开页面。

## 第三方服务

| 服务 | 用途 | 触发条件 |
| --- | --- | --- |
| 插件包内 `jsQR` | 二维码解析库 | 访客选择上传二维码时，无需访问外部 CDN |

二维码解析为可选辅助功能，不可用时申请者仍可手动填写 QQ 好友链接。昵称、学校等资料由申请者自行填写。

## 从源码构建

```bash
./gradlew.bat test
./gradlew.bat build -x test
```

完整插件包输出到：

```text
build/libs/plugin-members-<version>.jar
```

前端 Console 与公开 Widget 会由 Gradle 任务一并构建和打入 JAR，不需要手动复制生成目录。

## 参与开发

三仓（主题 + 本插件 + 友链插件）共用一套协同流程，**动手前先读**
<https://gitee.com/xiui-cc/theme-nucma/blob/main/CONTRIBUTING.md>：

- 改前先同步；协作者不推 `main`，一律开分支提 PR。
- 只改源码，`console/**`、`static/**` 由 Gradle 任务生成。
- 自检 `./gradlew cleanTest test`，打包 `./gradlew.bat build -x test`，出了 jar 才算完成。
- 版本三处一致：`build.gradle` + `plugin.yaml` + `ui/package.json`；会进包才升版本。
- 只推 Gitee，GitHub 由镜像自动同步。

## 来源与致谢

- 原始项目与首版实现：[chenhufei/plugin-members](https://github.com/chenhufei/plugin-members)，原作者为 [chenhufei](https://github.com/chenhufei)。
- 当前维护：chenhufei / NUCMA。成员审核、撤回、通知、定时清理和主题集成均在此仓库持续维护。
- 插件基于 Halo 的官方插件 API、Finder、通知和权限机制实现；Halo 是其运行平台，不是本项目的代码上游。

## 反馈与许可

- 仓库（主）：<https://gitee.com/xiui-cc/plugin-members>
- 镜像：<https://github.com/chenhufei/plugin-members>
- 问题反馈：<https://gitee.com/xiui-cc/plugin-members/issues>
- API 文档：[docs/API.md](docs/API.md)
- 许可证：GPL-3.0
