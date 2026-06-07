# Members Plugin 文档

## 📚 文档目录

- [README](../README.md) - 项目介绍和快速开始
- [CONTRIBUTING](../CONTRIBUTING.md) - 贡献指南
- [API 文档](API.md) - API 接口文档

## 快速链接

### 安装指南
- [环境要求](../README.md#环境要求)
- [安装步骤](../README.md#安装使用)
- [开发指南](../README.md#开发指南)

### 配置指南
- [基础设置](../README.md#基础设置)
- [邮件通知](../README.md#邮件通知)
- [安全设置](../README.md#安全设置)

### API 文档
- [管理端点](API.md#成员-api) - 需要认证的 API
- [公开端点](API.md#公开-api) - 无需认证的 API
- [示例代码](API.md#示例代码) - JavaScript/TypeScript/Vue/React 示例

### 主题集成
- [推荐主题](../README.md#推荐主题)
- [自定义集成](../README.md#自定义集成)
- [Thymeleaf 示例](API.md#thymeleaf-集成示例)

## 功能说明

### 成员管理
- 成员申请
- 审核流程
- 成员信息管理
- 头像支持

### 分组管理
- 灵活分组
- 拖拽排序
- 权限控制
- 批量操作

### 通知系统
- 邮件通知
- 模板定制
- 多语言支持
- 通知历史

### 安全防护
- 频率限制
- 内容过滤
- IP 限制
- 数据验证

## 常见问题

### Q: 如何获取 API Token？
A: 在 Halo 后台个人设置中生成 API Token。

### Q: 如何自定义邮件模板？
A: 在插件设置的"邮件通知"中编辑模板。

### Q: 如何批量导入成员？
A: 可以通过 API 批量创建成员。

### Q: 如何限制申请频率？
A: 在插件设置的"安全设置"中配置频率限制。

## 更新日志

详细的版本更新记录请查看 [CHANGELOG.md](../CHANGELOG.md)

## 相关资源

- [Halo 官方文档](https://docs.halo.run/)
- [Halo 插件开发文档](https://docs.halo.run/developer-guide/plugin/introduction)
- [Spring Boot 文档](https://spring.io/projects/spring-boot)
- [Vue.js 文档](https://vuejs.org/)

## 反馈与支持

如有问题或建议，请：
- 提交 [Issue](https://github.com/chenhufei/plugin-members/issues)
- 查看 [Discussions](https://github.com/chenhufei/plugin-members/discussions)
