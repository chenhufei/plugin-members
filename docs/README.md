# Members Plugin 文档

- [README](../README.md) —— 介绍、安装与设置项
- [API 文档](API.md) —— 接口分组、数据模型、匿名/公开契约、状态码与 MCP 工具
- [主题接入指南](THEME-INTEGRATION.md) —— Finder 契约、`/members` 模板覆盖、Widget 调用与自检清单
- [CHANGELOG](../CHANGELOG.md) —— 版本记录
- [CONTRIBUTING](../CONTRIBUTING.md) —— 贡献与构建约定

## 按任务查找

| 我要做什么 | 看哪里 |
| --- | --- |
| 前台展示已通过成员 | [主题接入指南 §2](THEME-INTEGRATION.md#2-模板内使用-memberfinder) |
| 自定义 `/members` 页面 | [主题接入指南 §3](THEME-INTEGRATION.md#3-members-页面与模板覆盖) |
| 添加申请入口按钮 | [主题接入指南 §4](THEME-INTEGRATION.md#4-申请入口-widget) |
| 用脚本提交申请 / 处理错误码 | [API 文档 · 匿名申请 API](API.md#匿名申请-api)、[错误响应](API.md#错误响应) |
| 在后台列表做筛选、批量、导出 | [API 文档 · 控制台成员查询 API](API.md#控制台成员查询-api)、[批量操作 API](API.md#批量操作-api) |
| 配置验证码、限流、黑名单 | [README · 安全设置](../README.md#安全与维护) |
| 让 AI 助手查询或审核申请 | [API 文档 · MCP 工具](API.md#mcp-工具) |

## 常见问题

**Q：匿名接口需要登录吗？**
A：不需要。插件自带角色模板 `role-template-member-anonymous-public`，只聚合了申请分组、QQ 资料、验证码、提交、撤回与公开读取所需权限，不开放任何管理写权限。

**Q：公开只读接口为什么没有邮箱和 QQ？**
A：`/apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/members` 只输出展示字段，邮箱、QQ 原值、审核状态与内部注解需要走受认证的管理或控制台接口。

**Q：后台审核通过了，前台为什么还没更新？**
A：前台查询有 5 分钟缓存，成员或分组变更后由调和器异步清除，通常秒级生效，最坏情况延迟一个缓存周期。

**Q：能关掉图形验证码吗？**
A：可以。在插件设置「安全防护」中关闭「提交需要图形验证码」，旧的免验证码提交方式继续可用。

**Q：撤回申请需要什么条件？**
A：需要开启邮件通知（撤回依赖邮箱验证码），且记录状态为 `PENDING`、`APPROVED` 或 `WITHDRAW_REQUESTED`；否则接口返回 `409`。

**Q：通知模板怎么改？**
A：插件在 `extensions/notification-templates.yaml` 注册通知模板，正文定制请使用 Halo 后台的通知模板能力或 `plugin-mail-template` 插件，插件设置里只提供开关与收件人配置。

**Q：支持批量导入成员吗？**
A：批量接口只提供审核、删除、改组与导出（CSV/JSON）。批量导入请逐个调用成员创建 API，或在控制台粘贴资料创建。

## 相关资源

- [Halo 官方文档](https://docs.halo.run/)
- [Halo 插件开发指南](https://docs.halo.run/developer-guide/plugin/introduction)
- [Halo 主题开发指南](https://docs.halo.run/developer-guide/theme/introduction)

## 反馈与支持

- 提交 [Issue](https://github.com/chenhufei/plugin-members/issues)
- 查看 [仓库](https://github.com/chenhufei/plugin-members)
