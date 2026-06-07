# Halo Members Plugin

> Halo 2 成员管理插件，支持前台申请、后台审核、分组展示、卡片网格布局和批量审批。

[![Halo](https://img.shields.io/badge/Halo-2.23.0+-blue)](https://halo.run)
[![License](https://img.shields.io/badge/License-GPL--3.0-green)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.0.1-brightgreen)](https://github.com/chenhufei/plugin-members/releases)

## 简介

Members Plugin 为 Halo 提供完整的成员申请与展示能力：

- **卡片网格布局**：参照链接管理插件 2.0，分组卡片垂直排列，成员以响应式网格展示
- **审批功能集成**：待审核成员卡片上直接显示「同意」「拒绝」按钮，支持批量选择审批
- **匿名申请成员**：支持频率限制、内容清理和基础安全校验
- **分组管理**：拖拽排序、编辑、删除（含仅删除分组 / 删除分组及成员）
- **CSV 导入导出**：批量导入成员数据，导出为 CSV 文件
- **QQ 信息自动填充**：输入 QQ 号自动获取昵称、头像、邮箱
- **二维码解析**：上传 QQ 加好友二维码自动解析链接
- **通知系统**：管理员通知、申请提交通知、审核结果通知
- **统计分析**：总体、分组、状态、学校和趋势统计

## 环境要求

- Halo >= 2.23.0
- Java >= 21
- Node.js：仅本地前端开发需要。Gradle 构建会自动下载固定版本的 Node.js 和 pnpm

## 安装

1. 从 [Releases](https://github.com/chenhufei/plugin-members/releases) 下载 `plugin-members-1.0.1.jar`
2. 进入 Halo 后台的"插件"页面并上传 jar
3. 启用插件后，在插件设置中配置默认分组、审核模式和通知选项
4. 访问 `/members` 检查前台展示页面

## 使用说明

### 成员管理

进入 Halo 后台 → 内容 → 成员：

- **查看成员**：按分组卡片展示，每个卡片显示成员头像、名称、学校、QQ、审核状态
- **新建成员**：点击分组卡片头部的「新建」按钮
- **编辑成员**：点击成员卡片，弹出编辑弹窗，支持上下翻页
- **审批成员**：待审核成员卡片底部有绿色「同意」和红色「拒绝」按钮
- **批量操作**：点击分组头部 ⋯ → 批量选择 → 全选 → 批量同意 / 批量拒绝 / 移动分组 / 删除

### 分组管理

- **新建分组**：顶部工具栏「新建分组」
- **编辑 / 删除分组**：点击分组头部 ⋯ 更多菜单
- **筛选排序**：工具栏支持按状态、排序方式筛选

## API 快速参考

完整说明见 [docs/API.md](docs/API.md)。

### 管理资源 API

以下资源由 Halo 扩展资源机制提供，需要认证：

- `GET /apis/member.plugin.halo.run/v1alpha1/members`
- `POST /apis/member.plugin.halo.run/v1alpha1/members`
- `GET /apis/member.plugin.halo.run/v1alpha1/members/{name}`
- `PUT /apis/member.plugin.halo.run/v1alpha1/members/{name}`
- `PATCH /apis/member.plugin.halo.run/v1alpha1/members/{name}`
- `DELETE /apis/member.plugin.halo.run/v1alpha1/members/{name}`
- `GET /apis/member.plugin.halo.run/v1alpha1/membergroups`
- `POST /apis/member.plugin.halo.run/v1alpha1/membergroups`
- `GET /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`
- `PUT /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`
- `PATCH /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`
- `DELETE /apis/member.plugin.halo.run/v1alpha1/membergroups/{name}`

### 匿名申请 API

- `GET /apis/anonymous.member.plugin.halo.run/v1alpha1/membergroups`
- `POST /apis/anonymous.member.plugin.halo.run/v1alpha1/membersubmits/-/submit`

### 公开只读 API

适合主题或外部只读展示：

- `GET /apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/members`
- `GET /apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/membergroups`

### 批量与统计 API

用于控制台管理界面：

- `GET /apis/api.member.plugin.halo.run/v1alpha1/members`
- `POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/batch-approve`
- `POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/batch-delete`
- `POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/batch-change-group`
- `POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/batch-change-priority`
- `POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/export-csv`
- `POST /apis/api.member.plugin.halo.run/v1alpha1/members/-/export-json`
- `GET /apis/api.member.plugin.halo.run/v1alpha1/members/-/statistics/overall`
- `GET /apis/api.member.plugin.halo.run/v1alpha1/members/-/statistics/by-group`
- `GET /apis/api.member.plugin.halo.run/v1alpha1/members/-/statistics/by-status`
- `GET /apis/api.member.plugin.halo.run/v1alpha1/members/-/statistics/by-school`
- `GET /apis/api.member.plugin.halo.run/v1alpha1/members/-/statistics/trend?days=30`

## 开发

### 后端与完整构建

```bash
./gradlew build
./gradlew test
./gradlew generateOpenApiDocs
./gradlew haloRun
```

### 本地前端开发

```bash
cd ui
pnpm install
pnpm typecheck
pnpm build
pnpm dev
```

说明：

- `ui/scripts/run-pnpm-package-bin.mjs` 会直接调用 pnpm store 中的真实入口，避免因为拷贝目录导致 `.bin` 脚本残留绝对路径而无法执行
- `./gradlew build` 会先安装前端依赖、执行 `typecheck`，再构建控制台资源

## 主题集成

插件已经自带 `/members` 页面模板，开箱即可使用。如果需要自定义主题接入，有两种方式：

1. 直接链接到 `/members`
2. 客户端调用公开只读 API：
   - `/apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/members`
   - `/apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/membergroups`

## 致谢

本插件的开发深受以下项目启发和帮助，在此表示衷心感谢：

- **[链接管理插件 (plugin-links)](https://www.halo.run/store/apps/app-hfbQg)** — [halo-sigs/plugin-links](https://github.com/halo-sigs/plugin-links)
  本插件的前端布局（卡片网格、分组展示、Badge 组件、Form+Modal 模式）完全参照链接管理插件 2.0 的架构和样式实现。链接管理是 Halo 官方维护的插件，代码规范和 UI 设计都是 Halo 插件开发的标杆。

- **[友链自助提交插件 (link-submit)](https://www.halo.run/store/apps/app-glejqzwk)** — [chengzhongxue/link-submit](https://github.com/chengzhongxue/link-submit)
  由 [困困鱼](https://kunkunyu.com/) 开发。本插件的访客自助申请、审核流程和部分前端交互逻辑参考了友链自助提交插件的设计思路。

- **[柳意梧情](https://github.com/liuyiwuqing)**
  友链自助提交插件的部分代码由柳意梧情提供，本插件在开发过程中也受益于这些基础工作。

## 版本

- 插件版本：`1.0.1`
- Halo 平台基线：`2.23.0`
- Java 基线：`21`

## 问题反馈

- [提交 Issue](https://github.com/chenhufei/plugin-members/issues)
- [功能请求](https://github.com/chenhufei/plugin-members/issues/new?template=feature_request.md)
- [Bug 报告](https://github.com/chenhufei/plugin-members/issues/new?template=bug_report.md)

## 许可证

[GPL-3.0](LICENSE)
