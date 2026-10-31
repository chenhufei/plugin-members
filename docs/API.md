# Members Plugin API 文档

插件 `PluginMembers`（成员管理）对外提供四类接口：管理资源 API、控制台与匿名业务 API、公开只读 API、MCP 工具。全部请求与响应均为 JSON，导出接口返回文件流。

本文所有路径、字段与状态码均按当前源码核对：`run.halo.members.endpoint`、`run.halo.members.mcp`、`run.halo.members.service`。

## API 分组总览

| 分组 | 基础路径 | 认证 | 说明 |
|------|----------|------|------|
| 管理资源 | `/apis/member.plugin.halo.run/v1alpha1` | 需要 | Halo 扩展资源 CRUD（成员、分组） |
| 控制台与匿名业务 | `/apis/api.member.plugin.halo.run/v1alpha1` | 混合 | 列表查询、批量操作、统计；其中 `membergroups`、`membersubmits/*` 为匿名可用 |
| 公开只读 | `/apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers` | 不需要 | 前台主题与第三方页面使用 |
| 撤回审核 | `/apis/console.api.member.plugin.halo.run/v1alpha1/members` | 需要 | 后台处理成员撤回申请 |

匿名可用能力由插件自带的角色模板 `role-template-member-anonymous-public` 聚合到 `anonymous` 角色，只开放必要资源，不开放任何写管理数据的权限。

## 数据模型

### Member

```json
{
  "apiVersion": "member.plugin.halo.run/v1alpha1",
  "kind": "Member",
  "metadata": { "name": "member-0h6bk4r2q2x" },
  "spec": {
    "displayName": "张三",
    "email": "zhangsan@example.com",
    "school": "清华大学",
    "qq": "12345678",
    "avatar": "https://q1.qlogo.cn/g?b=qq&nk=12345678&s=640",
    "background": "",
    "qqFriendLink": "https://wpa.qq.com/msgrd?v=3&uin=12345678",
    "groupName": "union",
    "status": "PENDING",
    "origin": "FORM",
    "website": "",
    "description": ""
  }
}
```

字段说明：

- `status`：`PENDING`（待审核）、`APPROVED`（已通过）、`REJECTED`（已拒绝）、`WITHDRAW_REQUESTED`（撤回申请中）。缺省值为 `PENDING`。
- `origin`：`FORM`（前台自助提交）、`CONSOLE`（后台创建），历史数据为空。
- `website`、`description`、`priority`：为兼容旧数据保留，已废弃，新流程不再读写。
- `background`：卡片背景图，可为空。

### MemberGroup

```json
{
  "apiVersion": "member.plugin.halo.run/v1alpha1",
  "kind": "MemberGroup",
  "metadata": { "name": "union" },
  "spec": {
    "displayName": "联盟正式成员",
    "description": "公开展示分组"
  }
}
```

展示顺序：分组按 `spec.displayName` 不区分大小写升序，同名时按 `metadata.name` 升序；组内成员按创建时间倒序，其次按 `displayName`、`metadata.name` 升序。没有任何已通过成员的分组不会出现在公开结果中；`groupName` 指向不存在分组的成员会归入虚拟分组 `ungrouped`（显示名「未分组」）。

## 管理资源 API

由 Halo 扩展资源机制提供，需要后台认证与对应权限。

成员：

- `GET /apis/member.plugin.halo.run/v1alpha1/members`
- `POST /apis/member.plugin.halo.run/v1alpha1/members`
- `GET /apis/member.plugin.halo.run/v1alpha1/members/{name}`
- `PUT /apis/member.plugin.halo.run/v1alpha1/members/{name}`
- `PATCH /apis/member.plugin.halo.run/v1alpha1/members/{name}`
- `DELETE /apis/member.plugin.halo.run/v1alpha1/members/{name}`

分组：

- `GET /apis/member.plugin.halo.run/v1alpha1/membergroups`
- `POST /apis/member.plugin.halo.run/v1alpha1/membergroups`
- `GET /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`
- `PUT /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`
- `PATCH /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`
- `DELETE /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`

注意：前台查询带进程内缓存，直接调用 `PUT`/`PATCH` 修改成员后，公开接口最多可能延迟一个缓存周期才生效（见「缓存」一节）。

## 匿名申请 API

以下接口无需登录，供前台申请页与 Widget 使用。

### 获取可申请分组

```http
GET /apis/api.member.plugin.halo.run/v1alpha1/membergroups
```

返回 `MemberGroupVo[]`，已排除「禁止申请的分组」设置中的分组。

### 获取图形验证码

```http
POST /apis/api.member.plugin.halo.run/v1alpha1/membersubmits/-/captcha
```

无请求体。响应头 `Cache-Control: no-store`：

```json
{
  "challengeId": "01J9ZK8Q3M7Y2C4X5R6T8V0W1E",
  "image": "data:image/png;base64,....",
  "expiresInSeconds": 300
}
```

`challengeId` 一次性有效，不依赖 Cookie。提交时若答案错误或已被消费，`submit` 返回 `400`。

### 提交成员申请

```http
POST /apis/api.member.plugin.halo.run/v1alpha1/membersubmits/-/submit
Content-Type: application/json
```

```json
{
  "displayName": "张三",
  "email": "zhangsan@example.com",
  "school": "清华大学",
  "qq": "12345678",
  "qqFriendLink": "https://wpa.qq.com/msgrd?v=3&uin=12345678",
  "groupName": "union",
  "challengeId": "01J9ZK8Q3M7Y2C4X5R6T8V0W1E",
  "captchaCode": "AB234"
}
```

校验规则：

- `displayName`：必填，2 到 50 个字符，仅允许中英文、数字、空格、`-`、`_`
- `email`：必填，合法邮箱，且拒绝临时邮箱域名
- `school`：必填，通过学校名称合法性校验
- `qq`：必填，5 到 12 位数字
- `qqFriendLink`、`groupName`：可选
- `challengeId`、`captchaCode`：在「提交需要图形验证码」开启时必填；关闭时可不传，旧集成方式继续可用

成功返回 `200` 与创建后的 `Member` 资源。业务冲突与防护类错误见「错误响应」。

### 撤回申请（自助）

```http
POST /apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/membersubmits/-/send-verification-code
POST /apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/membersubmits/-/withdraw
```

请求体：

```json
{ "email": "zhangsan@example.com", "qq": "12345678", "code": "123456", "reason": "已毕业" }
```

- `send-verification-code` 需要 `email` 与 `qq`；`withdraw` 需要 `email`、`qq`、`code`，`reason` 可选。
- 仅 `APPROVED`、`PENDING`、`WITHDRAW_REQUESTED` 状态的记录可发起撤回；否则返回 `409`。
- 验证码按 `email:qq` 组合投递到邮箱；同一组合 10 分钟内最多 3 次发码、10 次校验，超出返回 `429`。
- 未开启邮件通知时返回 `409`（功能不可用）。
- 成功返回 `{"failed": false, "message": "..."}`。

## 公开只读 API

无需认证，适合主题模板之外的前台脚本使用。

### 获取已通过成员

```http
GET /apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/members?page=1&size=100
```

- `page`：默认 `1`，范围 `1` 到 `1000000`
- `size`：可选，不传则返回全部；范围 `1` 到 `100`

返回 `ListResult<MemberVo>`。**出于隐私考虑，该接口只输出展示所需字段**：

```json
{
  "page": 1,
  "size": 100,
  "total": 1,
  "items": [
    {
      "metadata": { "name": "member-0h6bk4r2q2x", "creationTimestamp": "2026-10-28T04:29:51Z" },
      "spec": {
        "displayName": "张三",
        "school": "清华大学",
        "avatar": "https://q1.qlogo.cn/g?b=qq&nk=12345678&s=640",
        "qqFriendLink": "https://wpa.qq.com/msgrd?v=3&uin=12345678",
        "groupName": "union"
      }
    }
  ]
}
```

不会输出 `email`、`status`、`origin`、`background`、已废弃字段，也不会输出 `metadata` 的注解、标签、finalizers 与版本号。需要完整资料请使用受认证的管理或控制台 API。

### 获取分组列表

```http
GET /apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/membergroups
```

返回 `MemberGroupVo[]`，按上文「展示顺序」排序，不做分组可申请性过滤。

## 控制台成员查询 API

供后台列表使用，支持服务端筛选、排序与分页。

```http
GET /apis/api.member.plugin.halo.run/v1alpha1/members
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `page` | integer | 页码，默认 `1`，小于 1 时归一化为 `1` |
| `size` | integer | 每页数量，默认 `20`，最大 `10000` |
| `keyword` | string | 匹配成员名称、邮箱、学校或 QQ |
| `status` | string | `PENDING` / `APPROVED` / `REJECTED` / `WITHDRAW_REQUESTED` |
| `groupName` | string | 按分组名称筛选 |
| `sort` | string | `createdTime-desc`（默认）、`createdTime-asc`、`name-asc`、`name-desc` |

返回 `ListResult<MemberVo>`，包含完整字段（受认证保护）。

## 批量操作 API

需要成员管理权限。请求体中的 `memberNames` 为成员 `metadata.name` 数组。

### 批量审核

```http
POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/batch-approve
{ "memberNames": ["member-a", "member-b"], "approved": true }
```

### 批量删除

```http
POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/batch-delete
{ "memberNames": ["member-a", "member-b"] }
```

### 批量调整分组

```http
POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/batch-change-group
{ "memberNames": ["member-a", "member-b"], "groupName": "union" }
```

### 导出

```http
POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/export-csv
POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/export-json
{ "memberNames": ["member-a"] }
```

`memberNames` 为空或缺省时导出全部成员。批量接口成功与否都返回 `BatchOperationResult`，逐条失败原因在 `failedMembers` 中：

```json
{
  "total": 2,
  "success": 2,
  "failed": 0,
  "failedMembers": [],
  "message": "操作成功"
}
```

批量审核、批量删除与批量改组会同时清除前台查询缓存。

## 统计 API

```http
GET /apis/api.member.plugin.halo.run/v1alpha1/members/-/statistics/overall
```

```json
{
  "totalMembers": 10,
  "approvedMembers": 8,
  "pendingMembers": 1,
  "rejectedMembers": 1,
  "totalGroups": 3,
  "approvalRate": 80.0,
  "mostPopularGroup": "union",
  "mostPopularSchool": "清华大学"
}
```

其他统计接口返回 `Map<String, Integer>`：

- `GET .../members/-/statistics/by-group`
- `GET .../members/-/statistics/by-status`
- `GET .../members/-/statistics/by-school`（按数量取前 10）
- `GET .../members/-/statistics/trend?days=30`：`days` 缺省 `30`，允许 `1` 到 `365`，非法值返回 `400`

## 撤回审核 API

后台处理成员的自助撤回申请，需要成员管理权限。

- `GET /apis/console.api.member.plugin.halo.run/v1alpha1/members/-/withdraw-requests`
- `POST /apis/console.api.member.plugin.halo.run/v1alpha1/members/-/withdraw-approve/{memberName}`
- `POST /apis/console.api.member.plugin.halo.run/v1alpha1/members/-/withdraw-reject/{memberName}`

通过审核后成员状态变为 `REJECTED`（下架），并保留撤回前的状态用于驳回时还原。

## MCP 工具

安装并启用 Halo MCP Server 插件后，本插件注册 27 个工具（协议名带 `PluginMembers__` 前缀）。未安装 MCP Server 时相关类不会被加载，插件功能不受影响。

> MCP Server 1.3.x 的 API 只提供 tools，没有 resources / prompts 能力，因此「一次性读取的只读快照」由 `get_member_overview` 承担：它用 `sections` 裁剪、一次返回花名册全貌，等价于 `members://overview` 这类资源的替代物。

| 工具 | 分类 | 参数 | 说明 |
|------|------|------|------|
| `list_members` | 成员查询 | `page`、`size`(1-100)、`status`、`groupName`、`keyword`、`sort`、`fields` | 分页筛选成员；`fields` 可裁剪每条返回的字段以省 token |
| `get_member` | 成员查询 | `name`（必填） | 读取单个成员，含审核备注 |
| `search_members_advanced` | 成员查询 | `page`、`size`、`keyword`、`status`、`groupName`、`createdAfter`、`createdBefore`、`fuzzyMatch`、`sort` | 组合条件搜索，支持模糊与时间区间 |
| `get_member_statistics` | 成员查询 | 无 | 总量、通过率与分布 |
| `get_member_overview` | 成员查询 | `sections`（`core`/`groups`/`schools`/`queues`/`health`/`trend`）、`schoolLimit`(1-40)、`trendDays`(1-90) | 一次返回花名册快照：总量、分组与学校分布、待办队列、数据质量摘要、申请趋势；只查被请求分区所依赖的数据 |
| `generate_member_report` | 成员查询 | `type`、`groupName`、`trendDays`、`format` | 生成统计报告（摘要/CSV/JSON） |
| `member_duplicate_check` | 成员查询 | `field`、`minCount` | 按邮箱、QQ 或学校列出重复成员簇 |
| `audit_member_data` | 成员查询 | `checks`、`limit`(1-100) | 六项数据质量巡检：重复、联系方式、学校、分组引用、头像、状态 |
| `export_member_data` | 成员查询 | `format`、`names`、`status`、`groupName` | 导出成员 CSV/JSON |
| `validate_qq_link` | 成员查询 | `link`（必填） | 校验并归一化 QQ 加好友链接，落库前先判 |
| `review_member_submissions` | 成员审核 | `names`（必填）、`approved`（必填） | 批量通过/驳回 |
| `review_member_withdrawals` | 成员审核 | `names`（必填）、`approved`（必填） | 批量处理撤回申请 |
| `list_member_withdrawals` | 成员审核 | 无 | 列出待处理撤回申请及撤回理由、撤回前状态 |
| `update_member` | 成员审核 | `name`（必填）与至少一个资料字段（含 `qqFriendLink`、`joinedAt`、`background`） | 修改成员资料 |
| `batch_import_members` | 成员审核 | `format`（必填）、`data`（必填）、`defaultGroup` | 导入成员，新成员待审核 |
| `batch_update_members` | 成员审核 | `names`（必填）、`school`/`groupName`/`clearGroup`/`qqFriendLink`、`dryRun`(默认 true) | 批量补丁式修改，默认只预览 |
| `assign_member_group` | 成员审核 | `names`（必填）、`groupName`（必填） | 批量调整分组 |
| `auto_approve_pending` | 成员审核 | `dryRun`(默认 true)、`limit`(1-100)、`school`、`groupName`、`createdBefore` | 按条件筛选待审核成员并通过，最早优先 |
| `list_member_groups` | 成员分组 | 无 | 列出全部分组 |
| `create_member_group` | 成员分组 | `displayName`（必填）、`description`、`name` | 新建分组，显示名不可重复 |
| `update_member_group` | 成员分组 | `name`（必填）、`displayName`、`description` | 分组改名会级联更新成员的分组字段 |
| `delete_member_group` | 成员分组 | `name`（必填） | 仅允许删除空分组，非空返回 `GROUP_NOT_EMPTY` |
| `auto_assign_group` | 成员分组 | `rules`（必填）、`onlyUngrouped`(默认 true)、`status`(默认 APPROVED)、`dryRun`(默认 true)、`limit`(1-200) | 按学校关键词规则批量改组，目标分组须已存在 |
| `generate_claim_tokens` | 成员认领 | `names`、`groupName`、`expiresInDays`、`regenerate` | 生成认领令牌与认领链接 |
| `list_claim_tokens` | 成员认领 | 无 | 列出有效令牌与过期时间 |
| `revoke_claim_token` | 成员认领 | `name`（必填） | 吊销单个成员的认领令牌 |
| `analyze_school_affinity` | 成员关系 | `dimension`、`limit`、`school` | 学校关联分析，未装 AI Foundation 或后台关闭时返回稳定错误码 |

`batch_update_members`、`auto_*` 默认只预览（`dryRun=true`），返回计划处理的成员清单；显式传入 `dryRun=false` 才会写入。`auto_assign_group` 的 `rules` 为“学校关键词 → 已有分组名”的对象，首个命中的规则生效，已在目标分组的成员会被跳过。

成员本身不提供删除或合并工具；分组删除也限定为空分组，避免成员失去归属。

工具执行前会校验当前 MCP 密钥所属用户的角色权限：查询类需要成员查看权限，审核类需要成员管理权限；权限不足返回稳定错误码而不是抛出堆栈。

## 错误响应

匿名与公开接口的错误统一使用 RFC 7807 `application/problem+json`：

```json
{
  "type": "https://halo.run/probs/duplicate-member-application",
  "title": "成员申请重复",
  "status": 409,
  "detail": "该 QQ 号已提交过申请"
}
```

成员申请相关状态码约定：

| 状态 | type | 触发条件 |
|------|------|----------|
| `400` | `invalid-member-application` | 请求体无法解析或字段校验失败 |
| `400` | `invalid-member-application-captcha` | 验证码缺失、错误或已被消费 |
| `403` | `member-application-forbidden` | 命中 IP / User-Agent 黑名单或空 UA 策略 |
| `404` | `member-not-found` | 撤回时未找到匹配的成员记录 |
| `409` | `duplicate-member-application` | 同一 QQ / 邮箱已存在有效申请 |
| `409` | `member-application-capacity-reached` | 待审核申请数量达到容量上限 |
| `409` | `withdraw-not-allowed` / `withdraw-unavailable` | 成员状态不允许撤回，或邮件通知未开启 |
| `429` | `request-not-permitted` | 触发提交限流或验证码频率限制 |
| `503` | `member-application-unavailable` | 验证码服务暂时不可用 |

公开只读的 `members`（分页参数）参数错误返回 `{"message": "..."}` 形式的 `400`；申请、验证码和撤回等写操作均为 problem+json。

其他常见状态码：`401` 未认证、`403` 无权限、`404` 资源不存在、`409` 资源冲突、`500` 服务端异常。

## 缓存

前台成员查询（`memberFinder` 与公开只读 API）使用 5 分钟 TTL 的进程内缓存。缓存在以下时机失效：

- 成员或分组资源发生新增、更新、删除后，由 `MemberReconciler` 与 `MemberGroupReconciler` 异步清除（通常为秒级延迟）
- 批量审核、批量删除、批量改组等同步业务操作
- 撤回流程中的状态变更

绕过业务接口直接写入扩展资源时，若调和器尚未处理，公开结果最长会保持旧值一个缓存周期。

## 主题集成

主题侧的 Finder、Widget、默认页与模板覆盖约定见 [docs/THEME-INTEGRATION.md](THEME-INTEGRATION.md)。

## 更新

- 文档版本：`1.8.0`
- 核对基线：Halo `2.27.1`、PluginMembers `1.8.0`、Halo MCP Server `1.3.1`
- 更新时间：`2026-10-29`
