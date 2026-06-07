# 更新日志

## [1.0.5] - 2026-05-12

### Changed
- 统一插件版本、README 和插件描述文件到 `1.0.5`
- 提升运行基线到 Halo `2.23.0` 和 Java `21`
- 修正文档中的公开 API 路径，区分匿名申请接口与只读展示接口
- 刷新测试代码以匹配当前 `Member` / `MemberGroup` 模型
- 增加前端脚本运行器，避免拷贝目录后 `.bin` 入口残留绝对路径

### Build
- Gradle 构建现在会自动下载固定版本的 Node.js 和 pnpm
- `check` 任务会额外执行前端 `typecheck`

## [1.0.0] - 2025-01-XX

### 新增功能
- ✨ 添加公开 API 端点 `MemberPublicEndpoint`
  - `/apis/anonymous.member.plugin.halo.run/v1alpha1/members` - 获取所有已审核通过的成员
  - `/apis/anonymous.member.plugin.halo.run/v1alpha1/membergroups` - 获取所有分组
- 🔓 前台主题可以无需登录访问成员数据

### API 说明

#### 获取成员列表
```
GET /apis/anonymous.member.plugin.halo.run/v1alpha1/members
```

**响应示例：**
```json
{
  "page": 1,
  "size": 0,
  "total": 2,
  "items": [
    {
      "metadata": {
        "name": "group-1"
      },
      "spec": {
        "displayName": "默认分组",
        "priority": 0
      },
      "members": [
        {
          "metadata": {
            "name": "member-1"
          },
          "spec": {
            "displayName": "张三",
            "avatar": "https://...",
            "school": "清华大学",
            "bio": "前端工程师"
          }
        }
      ]
    }
  ]
}
```

#### 获取分组列表
```
GET /apis/anonymous.member.plugin.halo.run/v1alpha1/membergroups
```

**响应示例：**
```json
[
  {
    "metadata": {
      "name": "group-1"
    },
    "spec": {
      "displayName": "默认分组",
      "priority": 0
    },
    "members": []
  }
]
```

### 技术细节

- 新增 `MemberPublicEndpoint` 类，提供公开 API
- 使用 `anonymous.member.plugin.halo.run/v1alpha1` GroupVersion
- 不需要任何权限验证，前台访客可直接访问
- 返回已审核通过的成员数据

### 兼容性

- 要求 Halo >= 2.22.5
- 与现有 API 完全兼容，不影响原有功能
- 主题可以同时使用新旧 API

### 升级说明

1. 卸载旧版本插件
2. 安装新版本插件（1.0.0）
3. 启用插件
4. 前台主题会自动使用新的公开 API

### 主题集成

主题可以通过以下方式获取成员数据：

```javascript
// 方式1：使用新的公开 API（推荐）
fetch('/apis/anonymous.member.plugin.halo.run/v1alpha1/members')
  .then(res => res.json())
  .then(data => {
    // data.items 是分组数组
    // 每个分组的 members 字段包含成员列表
    const allMembers = [];
    data.items.forEach(group => {
      allMembers.push(...group.members);
    });
    console.log('所有成员:', allMembers);
  });

// 方式2：使用 Thymeleaf 服务端渲染（原有方式）
// 在模板中使用 ${groups} 变量
```

### 注意事项

- 公开 API 只返回已审核通过的成员
- 未审核或已拒绝的成员不会出现在 API 响应中
- API 响应包含完整的成员信息（头像、学校、简介等）
