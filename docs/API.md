# Members Plugin API 文档

## 概览

插件当前包含四类接口：

- 管理资源 API：`/apis/member.plugin.halo.run/v1alpha1`
- 匿名申请 API：`/apis/anonymous.member.plugin.halo.run/v1alpha1`
- 公开只读 API：`/apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers`
- 批量与统计 API：`/apis/api.member.plugin.halo.run/v1alpha1`

所有请求和响应均为 JSON，下载接口除外。

## 数据模型

### Member

`spec` 字段如下：

```json
{
  "displayName": "张三",
  "email": "zhangsan@example.com",
  "school": "清华大学",
  "qq": "12345678",
  "avatar": "https://q1.qlogo.cn/g?b=qq&nk=12345678&s=640",
  "qqFriendLink": "https://qm.qq.com/...",
  "priority": 0,
  "groupName": "default-group",
  "status": "PENDING",
  "website": "",
  "description": ""
}
```

说明：

- `status` 取值为 `PENDING`、`APPROVED`、`REJECTED`
- `website` 和 `description` 为兼容旧数据保留字段，已废弃

### MemberGroup

```json
{
  "displayName": "默认分组",
  "priority": 0,
  "description": "公开展示分组"
}
```

## 管理资源 API

这部分由 Halo 扩展资源机制提供，通常需要后台认证。

### 成员资源

- `GET /apis/member.plugin.halo.run/v1alpha1/members`
- `POST /apis/member.plugin.halo.run/v1alpha1/members`
- `GET /apis/member.plugin.halo.run/v1alpha1/members/{name}`
- `PUT /apis/member.plugin.halo.run/v1alpha1/members/{name}`
- `PATCH /apis/member.plugin.halo.run/v1alpha1/members/{name}`
- `DELETE /apis/member.plugin.halo.run/v1alpha1/members/{name}`

### 分组资源

- `GET /apis/member.plugin.halo.run/v1alpha1/membergroups`
- `POST /apis/member.plugin.halo.run/v1alpha1/membergroups`
- `GET /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`
- `PUT /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`
- `PATCH /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`
- `DELETE /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`

## 匿名申请 API

### 获取可申请分组

```http
GET /apis/anonymous.member.plugin.halo.run/v1alpha1/membergroups
```

返回值为 `MemberGroupVo[]`，会过滤掉配置中禁止申请的分组。

### 提交成员申请

```http
POST /apis/anonymous.member.plugin.halo.run/v1alpha1/membersubmits/-/submit
Content-Type: application/json
```

请求体：

```json
{
  "displayName": "张三",
  "email": "zhangsan@example.com",
  "school": "清华大学",
  "qq": "12345678",
  "qqFriendLink": "https://qm.qq.com/...",
  "groupName": "default-group"
}
```

校验规则：

- `displayName`：2 到 50 个字符，只允许中英文、数字、空格、`-`、`_`
- `email`：合法邮箱，且不允许临时邮箱
- `school`：必填，经过学校名称合法性校验
- `qq`：5 到 12 位数字

可能返回：

- `200`：提交成功，返回创建后的 `Member`
- `400`：参数不合法
- `429`：触发频率限制

## 公开只读 API

这部分接口不需要认证，适合主题或前台页面读取。

### 获取已审核成员

```http
GET /apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/members
```

返回 `ListResult<MemberVo>`：

```json
{
  "page": 1,
  "size": 1000,
  "total": 2,
  "items": [
    {
      "metadata": {
        "name": "member-abc123"
      },
      "spec": {
        "displayName": "张三",
        "school": "清华大学",
        "status": "APPROVED"
      }
    }
  ]
}
```

### 获取分组列表

```http
GET /apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/membergroups
```

返回 `MemberGroupVo[]`，按 `priority` 倒序排序。

## 控制台成员查询 API

以下接口主要供控制台列表调用，支持服务端筛选、排序和分页。

```http
GET /apis/api.member.plugin.halo.run/v1alpha1/members
```

查询参数：

| 参数 | 类型 | 说明 |
|------|------|------|
| `page` | integer | 页码，默认 `1` |
| `size` | integer | 每页数量，默认 `20`，最大 `500` |
| `keyword` | string | 匹配名称、邮箱、学校或 QQ |
| `status` | string | `PENDING`、`APPROVED` 或 `REJECTED` |
| `groupName` | string | 按分组名称筛选 |
| `sort` | string | `priority-asc`、`priority-desc`、`createdTime-asc`、`createdTime-desc`、`name-asc`、`name-desc`、`status-priority` |

返回 `ListResult<MemberVo>`。

## 批量操作 API

以下接口主要供控制台调用。

### 批量审核

```http
POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/batch-approve
```

```json
{
  "memberNames": ["member-a", "member-b"],
  "approved": true
}
```

### 批量删除

```http
POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/batch-delete
```

```json
{
  "memberNames": ["member-a", "member-b"]
}
```

### 批量调整分组

```http
POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/batch-change-group
```

```json
{
  "memberNames": ["member-a", "member-b"],
  "groupName": "default-group"
}
```

### 批量调整优先级

```http
POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/batch-change-priority
```

```json
{
  "memberNames": ["member-a", "member-b"],
  "priority": 10
}
```

### 导出成员

- `POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/export-csv`
- `POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/export-json`

请求体：

```json
{
  "memberNames": ["member-a", "member-b"]
}
```

当 `memberNames` 为空或省略时，服务端实现会导出全部成员。

### 批量接口响应

批量接口统一返回 `BatchOperationResult`：

```json
{
  "total": 2,
  "success": 2,
  "failed": 0,
  "failedMembers": [],
  "message": "操作成功"
}
```

## 统计 API

### 总体统计

```http
GET /apis/api.member.plugin.halo.run/v1alpha1/members/-/statistics/overall
```

返回：

```json
{
  "totalMembers": 10,
  "approvedMembers": 8,
  "pendingMembers": 1,
  "rejectedMembers": 1,
  "totalGroups": 3,
  "approvalRate": 80.0,
  "mostPopularGroup": "default-group",
  "mostPopularSchool": "清华大学"
}
```

### 其他统计

- `GET /apis/api.member.plugin.halo.run/v1alpha1/members/-/statistics/by-group`
- `GET /apis/api.member.plugin.halo.run/v1alpha1/members/-/statistics/by-status`
- `GET /apis/api.member.plugin.halo.run/v1alpha1/members/-/statistics/by-school`
- `GET /apis/api.member.plugin.halo.run/v1alpha1/members/-/statistics/trend?days=30`

返回值均为 `Map<String, Integer>`。

## 错误处理

常见状态码：

- `400`：参数错误
- `401`：未认证
- `403`：无权限
- `404`：资源不存在
- `409`：资源冲突
- `429`：请求过于频繁
- `500`：服务端异常

## 更新

- 文档版本：`1.0.5`
- 更新时间：`2026-05-12`
