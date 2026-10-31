# 更新日志

## [1.16.0] - 2026-11-01

### Features
- **学校关联分析独立设置组**：新增「学校关联分析」分组，含总开关、分析维度多选（合作 / 地域机构 /
  共同小组织）与最多连线数。关闭时不产生任何大模型调用；未选中的维度不进入提示词，
  让后台选项真实影响模型判断范围，而不是摆设。
- **关联依据**：每条关联边新增 `category`（关联类型）与 `reason`（判断理由），
  端点同时返回 `meta`（学校数、维度、连线上限）与 `reason`（未出线原因），
  供前端展示「分析了什么、凭什么连这条线」。

### Fixes
- **认领验证方式不生效**：`SettingConfigMemberImpl.merge` 漏设 `claimVerifyMode`，
  后台选择「图形验证码」或「关闭验证」都会被旧配置迁移逻辑当作邮箱验证码。
  现已显式透传，三选一设置真实生效。

## [1.14.6] - 2026-10-31

### Security
- **事件循环上的 DNS 查询**：`RequestUtils` 的转发头解析会把形似十六进制的字符串交给
  `InetAddress.getByName`，`X-Forwarded-For: beef` 因此触发真实域名解析；该链路运行在 Netty
  事件循环上，几十个并发即可拖住全站接口。现在改为纯字面量校验，完全不做网络查询。
- **转发头取最左值**：`X-Forwarded-For` 最左值由客户端填写，用它做限流 key 等于取消限流。
  改为取最右一跳（可信代理亲眼看到的对端地址）。
- **撤回验证码可无限触发发信**：原逻辑只按「邮箱+QQ」限流，换 QQ 号即可绕过，刷新页面还能重复发信。
  现在叠加出口 IP 维度限流，并复用未失效的验证码（不再重发）。
- **撤回原因无长度上限**：原因会写入成员注解并进入通知正文，现限制 200 字。
- **匿名成员列表默认分页过大**：不传 `size` 时一路透传成全量返回，现默认 50、上限 100。
- **限流表清理在事件循环执行**：表满后的全表排序移到 `boundedElastic`，并用标志位合并并发清理。

### Fixed
- **认领链接管理看不到分组**：已生成的认领链接列表缺少分组列；「信息不完善的成员」列表显示的
  是分组标识而非分组显示名。两处均改为「显示名优先、标识兜底、未分组留空」，一次分组查询批量解析。
- **批量导入撞号**：成员名以「QQ/邮箱/名称 + 毫秒时间戳」生成，同一毫秒内的同名记录会互相覆盖，
  后一条直接失败并被静默计入失败数。现在追加随机后缀。
- **CSV 导入带 BOM 时整批丢失**：Excel 导出的 CSV 首行带 BOM，表头匹配失败会把所有列当成未知列。
  导入前统一去除 BOM 与 CRLF。
- **CSV 导出公式注入**：以 `= + - @` 开头的单元格在表格软件中会被当成公式执行，导出时加前缀中和。

### Verification
- `./gradlew cleanTest test`：29 个测试类、132 项测试通过，0 失败 / 0 错误 / 0 跳过。
- `./gradlew compileJava` 通过；`plugin.yaml` 与 `build.gradle` 版本同步为 1.14.6。

## [1.14.5] - 2026-10-31

### Security
- **认领验证码收件地址归属校验**：发码与校验原本都把匿名请求方自填的邮箱当作验证码 key，
  任何人只要知道成员公开的 QQ 号，就能把验证码发到自己信箱、通过校验，随后改写该成员的
  昵称、学校、头像与 QQ 链接。现在只接受「成员登记邮箱」或该 QQ 号自带的 `<QQ号>@qq.com`，
  其余邮箱一律拒绝，并提示联系管理员更正登记邮箱。
- **堵住免验证认领通道**：开启邮箱校验但成员无可用邮箱时，原逻辑把验证码键置空并整段跳过校验，
  等于对这类成员完全放开认领；现在直接拒绝，不再存在「无邮箱 = 免验证」。
- **空心头像内存放大**：`AvatarUtils` 改为只解析图片头读取尺寸，不再 `ImageIO.read` 整幅解码。
  一张声明 30000×30000 的小体积 PNG 之前可让匿名认领接口分配数 GB 像素缓冲。
- **验证码可并发复用**：`VerificationCodeService.verifyCode` 的「读取 + 删除」合并为单次原子
  `computeIfPresent`，并改用常量时间比较，避免同一验证码被两个并发请求各用一次。

### Fixed
- 深分页 `(page-1)*size` 在 int 下溢出为负数，`subList` 抛 `IndexOutOfBoundsException`；
  `MemberQueryService` 与 MCP `search_advanced` 两处改用 long 计算并夹取。
- 限流表满（1 万条）后每个请求都要全量排序且只淘汰 1 条；改为一次淘汰到低水位，
  排序开销摊薄到约每 2000 个新客户端一次。
- 4xx 被 `Exception` 兜底成 500：新增 `ResponseStatusException` 处理器，保留真实状态码与原因。
- 认领资料已落库但待审通知发送失败时返回 500，用户会以为提交失败而重复提交；现在仅记录警告并返回成功。

### Verification
- Java 单测 132 项通过（`cleanTest test`，0 失败 / 0 错误 / 0 跳过）；新增 `MemberClaimVerificationEmailTest`
  覆盖「登记邮箱 / QQ 邮箱可通过、第三方邮箱拒绝、无可用邮箱返回空串」，`AvatarUtilsTest` 增加
  「只有 PNG 头、声明 30000×30000」样本，验证不解码像素即拒绝。
- Gradle 校验任务通过：`verifyVersionConsistency`、`verifySecurityContracts`、`verifyUiSourceContracts`、`verifyMcpApiNotPackaged`。

## [1.14.4] - 2026-10-30

### Added
- 成员新增 `spec.joinedAt`（ISO-8601）：管理员可自定义加入时间，留空回退 `metadata.creationTimestamp`。
  成员查询按加入时间升序，同一地区里最早加入的成员排在前面。
- 认领邮件自动回退 QQ 邮箱：成员未登记邮箱时使用 `<QQ号>@qq.com`，发信、验证码键、认领链接分发
  与后台提示统一走 `EmailUtils.resolveEmail`。
- `QqLinkUtils`：QQ 加好友链接统一校验，必须为 `https://qm.qq.com/q/` 开头；认领接口与成员提交共用。

### Changed
- 后台成员创建/编辑表单新增「加入时间」；未完善成员列表返回 `qq`、`hasEmail`、`emailSource`、`targetEmail`。

### Fixed
- 未登记邮箱的成员不再被「请联系管理员补全邮箱」阻断，验证码直接发往 QQ 邮箱。

## [1.14.3] - 2026-10-30

### Added
- 认领接口支持同步资料：`POST /claim` 与 `POST /claim/{token}` 新增可选字段
  `displayName`、`school`、`avatar`（`null` 不改动，空字符串表示清空）。未提交头像的老成员
  在认领时补上默认 QQ 头像 `q1.qlogo.cn`（s=640），前台卡片不再空占位。
- 头像以 data URL 提交（Halo 2.27 无匿名附件上传接口），后端 `AvatarUtils` 做严格校验：
  MIME 白名单 PNG/JPEG/WebP、Base64 可解码、ImageIO 可识别、不超过 512×512 且解码后 ≤96KB，
  拒绝 SVG、URL 和任意字符串。
- `GET /claim/{token}` 返回 `avatar`，供认领页展示现有头像。

### Changed
- 后台侧边栏「成员」菜单图标改用与其它菜单一致的 `team-line` 线性图标，不再是实心 `team-fill`。

## [1.14.2] - 2026-10-30

### Added
- 发信前置自检 `MailDeliveryGuard`：`分发认领链接`、`认领验证码`、`撤回验证码` 在发信前检查
  插件「发送邮件通知」开关与 Halo 邮件通知器（是否启用、是否填了 SMTP 主机与发信地址），
  任一不满足直接返回 409 并给出配置路径，不再先回「已发送」再让通知在 Halo 投递器里被丢弃。

## [1.14.1] - 2026-10-30

### Fixed
- 认领验证码限流回包不再谎报发送结果：已有有效验证码时原先返回「验证码已发送至 <脱敏邮箱>，请在 5 分钟内查收」，
  而本次请求实际并未发信，同时向仅知道 QQ 号的匿名调用方回显邮箱。现改为「验证码已在 5 分钟内发送过，请稍后再试」。
- 认领与撤回验证码分命名空间：两者此前都用 `email:qq` 作为键，一次撤回验证码会挡住认领发信，
  验证码也能跨流程复用。现分别为 `claim:email:qq` 与 `withdraw:email:qq`。
- `publishClaimLinkNotice` 改用 `EmailUtils.normalize` 取收件地址，与验证码发信、验证码键使用同一套规范化规则，
  避免同一成员因大小写差异在两条链路上落到不同订阅身份。

### Known
- CHANGELOG 缺 1.11.0–1.14.0 条目：`git HEAD` 仍停在 1.4.6，这段区间的改动全部在未提交的工作区里，
  无法按版本如实拆分，留待发布前整理，不在此凭记忆补写。

## [1.10.0] - 2026-10-29

### Added
- 认领链接邮件分发：
  - 新增 `user-member-claim-link` ReasonType 和邮件模板，支持向成员发送认领链接通知邮件。
  - 新增管理员端点 `POST /plugins/PluginMembers/claims/distribute`，批量生成认领链接并自动发送邮件。
  - 邮件收件地址：优先使用成员预留邮箱，若无则使用 `QQ号@qq.com`。
  - 后台「认领链接管理」页面新增「邮件分发」按钮和批量选中分发功能。
- `NotificationReasonPublisher.ClaimLinkNoticeReasonPublisher`：发布认领链接邮件通知。

### Changed
- `MemberClaimEndpoint` 新增 `distributeClaims` 路由和 `DistributeClaimsRequest` 记录类型。
- `NotificationReasonPublisher` 新增 `publishClaimLinkNotice` 公开方法和 `ClaimLinkNoticeReasonPublisher` 内部组件。

## [1.9.0] - 2026-10-29

### Added
- QQ 链接自助补全与 Token 认领：
  - `ClaimTokenService`：为成员生成一次性安全 token（SecureRandom + Base64 URL，默认 7 天有效），存储在 `metadata.annotations`。
  - `MemberClaimEndpoint`：三个公开端点
    - `POST /claim`：成员输入 QQ 号 + qqFriendLink + 验证码自助补全
    - `GET /claim/{token}`：获取 token 对应的成员信息（用于预填表单）
    - `POST /claim/{token}`：提交 qqFriendLink 并作废 token
  - 所有公开端点集成验证码与限流（每 IP 每分钟 5 次）。
- `generate_claim_tokens` MCP 工具：为有 QQ 号但缺少 qqFriendLink 的成员批量生成认领链接，支持按名称列表或自动扫描，返回 token 与完整认领 URL。

### Changed
- MCP 工具集从 15 个调整为 16 个，新增 `generate_claim_tokens` 工具需管理权限。
- `MemberMcpToolProvider` 构造器新增 `ClaimTokenService` 依赖。

### Verification
- `./gradlew.bat test` 全量通过；新增 `generate_claim_tokens` 的名称列表生成、缺少参数拒绝、自动扫描过滤测试。
- 编译通过，jar 产物 `plugin-members-1.9.0.jar` 已生成。

## [1.8.0] - 2026-10-29

### Added
- `auto_approve_pending`：按学校、分组与申请时间筛选待审核成员（最早优先），默认 dry-run 只返回候选清单，显式 `dryRun=false` 时才调用既有批量审核服务。
- `auto_assign_group`：按“学校关键词 → 已有分组”规则批量改组，首个命中规则生效，目标分组不存在时返回 `INVALID_ARGUMENT` 并提示可用分组；默认 dry-run，`onlyUngrouped` 默认只处理未分组成员，已在目标分组的成员跳过。

### Changed
- MCP 工具集从 13 个调整为 15 个，两个新工具仅编排既有 `MemberQueryService` 与 `MemberBatchService`，不新增写入路径。
- `docs/API.md` 的 MCP 章节补齐全部 15 个工具的参数、分类与 dry-run 约定。

### Verification
- `./gradlew.bat test` 全量 95 项通过；新增自动通过的 dry-run 预览与学校筛选执行、自动分组的未知分组拒绝、dry-run 幂等跳过与批量改组测试。
- 本地 Halo 2.27.1 + MCP Server 1.3.1 上验证 `tools/list` 注册 15 个工具；`auto_approve_pending` dry-run 命中 2 人、`dryRun=false` 实际通过 1 人；`auto_assign_group` 未知分组返回 `INVALID_ARGUMENT`，规则改组 1 人后 `get_member` 分组正确并已还原。

## [1.7.0] - 2026-10-29

### Removed
- 移除自动获取 QQ 昵称功能。匿名可用的 QQ 昵称接口已全部停用（uapis.cn 已于 2026-10-01 下线、腾讯 `cgi_get_portrait.fcg` 强制登录），保留“获取信息”按钮只会带来失败和误解。
  - 删除匿名端点 `GET /apis/api.member.plugin.halo.run/v1alpha1/qq-info`，并从匿名角色模板移除 `qq-info` 授权。
  - 删除设置中的“QQ 信息”分组（资料接口地址、访问令牌、昵称字段路径）及对应配置合并逻辑。
  - 删除成员申请 Widget 的“获取信息”按钮与昵称提示；账号名称、学校由申请人自行填写，邮箱仍由 QQ 号拼接。
  - 删除控制台成员表单的“获取QQ信息”按钮。
  - 清理前端的 `QqInfo` 类型、`fetchQqInfo` 请求、QQ 资料解析辅助方法与相关测试、文档。

### Changed
- 申请分组的自动选择提示移至“申请分组”下拉框下方，不再依赖已删除的 QQ 昵称提示区。

### Verification
- `./gradlew.bat test` 通过；控制台类型检查与构建通过；本地 Halo 2.27.1 安装后确认 `qq-info` 返回 404，申请弹窗仅保留手填字段。

## [1.6.2] - 2026-10-29

### Fixed
- QQ 资料查询不再调用已失效的数据源：`uapis.cn` 的 QQ 用户信息接口已于 2026-10-01 下线（HTTP 410），腾讯 `cgi_get_portrait.fcg` 现强制要求登录。此前两条链路都注定失败，申请人只能看到"未获取到昵称"。
- `qq-info` 现在始终返回可用的腾讯头像；昵称与邮箱只在管理员配置的资料接口成功返回时填充，不再声称"自动获取昵称"。

### Added
- “QQ 信息”设置新增：资料接口地址（支持 `{qq}` 占位符）、访问令牌（可选，Bearer 方式发送）、昵称字段路径（可选，如 `data.nickname`，留空自动识别常见字段）。
- `qq-info` 响应 `source` 明确区分 `configured-api`、`provider-empty`、`provider-error`、`no-provider`，Widget 与控制台据此给出准确提示。

### Removed
- 移除 Widget 中从未被调用、还引用不存在按钮的 `fillQqNickname` 死代码。

### Verification
- 新增 `qq-info` 未配置接口、URL 占位符与点号路径解析测试，`./gradlew.bat test` 通过。

## [1.6.1] - 2026-10-29

### Removed
- 删除 3 个只有表面能力、没有真实作用的 MCP 工具：
  - `favorite_member`、`member_watchlist`：只写一个无人读取的注解，控制台、主题与通知都不消费，调用方看到"成功"但系统没有任何变化。
  - `member_relationship_map`：把分组成员列表包装成"关系图"，输出与 `list_member_groups` / `get_member_statistics` 重复，且固定只扫描前 1000 名成员。
- `get_member` 与列表结果同步移除 `favorite`、`watchlist` 字段。

### Added
- `review_member_withdrawals`：批量批准或拒绝 `WITHDRAW_REQUESTED` 成员的撤回申请，逐条返回 `REVIEWED` / `NOT_FOUND` / `ALREADY_REVIEWED`。批准后成员下架，拒绝后恢复原状态，并沿用后台的邮件通知。
- `update_member`：修改成员昵称、邮箱、学校、QQ 或分组，只覆盖传入字段，已通过审核的成员会立即反映到公开成员页。

### Changed
- 撤回审核逻辑从 `MemberWithdrawAdminEndpoint` 抽取为 `MemberWithdrawReviewService`，控制台端点与 MCP 工具共用同一套状态迁移、注解写入与通知流程，避免两套实现分叉。
- MCP 工具集从 14 个调整为 13 个，全部对应真实的服务能力。

### Verification
- `./gradlew.bat test` 全量 87 项通过（新增 `update_member` 字段覆盖、空参数校验与撤回审核逐条结果测试）。

## [1.6.0] - 2026-10-29

### Added
- MCP 工具从 6 个扩充到 14 个，对齐链接管理插件的覆盖面（全部沿用调用方密钥的角色分级）：
  - 查询：`search_members_advanced`（关键字模糊/精确、状态、分组、创建时间区间、排序与分页）、`generate_member_report`（总量/分组/状态/学校分布，markdown 摘要或 CSV/JSON 明细，可选趋势天数）、`member_duplicate_check`（按邮箱/QQ/学校聚类重复成员）、`member_relationship_map`（分组—成员关系图谱）。
  - 数据：`export_member_data`（按名单、状态或分组导出 CSV/JSON）。
  - 写入：`batch_import_members`（JSON/CSV 批量导入，导入成员为待审核状态）、`favorite_member`、`member_watchlist`（收藏/关注标记持久化到注解）。
- `get_member` 与列表结果新增 `favorite`、`watchlist` 字段，便于调用方判断标记状态。
- 主题 `/members` 增加高级筛选：按学校下拉、加入日期区间筛选，与关键字搜索组合生效，并同步分组计数与空状态提示。

### Changed
- `list_members` 的排序枚举修正为服务端实际支持的 `name-asc` / `name-desc`（此前误写为 `displayName-*`），关键字说明修正为同时匹配昵称、邮箱、学校与 QQ。
- `MemberQueryService.MAX_SIZE` 改为公开常量，供 MCP 工具在全量集合上做内存筛选。

### Removed
- 未保留无法真实实现的候选工具：`send_member_notification`（插件无消息通道）、`member_activity_log` / `member_timeline`（无审计数据源）、`member_analytics_dashboard`（无持久化分析）、`batch_update_member_status`（服务层无批量状态能力）、`member_merge`（不可逆且无去重依据）。

### Verification
- `./gradlew.bat test --tests "run.halo.members.mcp.MemberMcpToolProviderTest"` 通过，覆盖工具名/Schema 约束、匿名拒绝、权限分级、日期区间筛选、模糊匹配、非法日期、重复聚类与报告格式。

## [1.5.0] - 2026-10-28

### Added
- 成员申请图形验证码接口 `membersubmits/-/captcha`：返回一次性 `challengeId` 与 base64 图片，不依赖 Cookie；提交时校验并消费，可在设置中关闭以保持既有集成方式可用。
- 6 个 MCP 工具（`list_members`、`get_member`、`list_member_groups`、`get_member_statistics`、`review_member_submissions`、`assign_member_group`），按调用方密钥的角色权限分级；未安装 Halo MCP Server 时相关类不加载，插件功能不受影响。
- 插件内置 `/members` 默认成员页：遵循 Halo 2.26+ 的 `layout :: html (head, content)` 版式约定；主题自带 `templates/members.html` 时优先使用主题模板，否则回落到插件默认页并套用主题版式。
- 主题接入文档 `docs/THEME-INTEGRATION.md`，覆盖 Finder 契约、模型属性、模板覆盖规则、Widget 调用方式、匿名权限边界与自检清单。

### Changed
- 运行基线提升到 Halo `2.27.0`（`spec.requires`），并适配 2.27 的模板解析与版式检查行为。
- 公开只读成员接口 `/apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/members` 输出脱敏字段，仅保留 `displayName`、`school`、`avatar`、`qqFriendLink`、`groupName` 与 `metadata.name`、`creationTimestamp`，不再泄露邮箱、QQ 原值、审核状态、来源、内部注解与版本号。
- 统计趋势接口 `days` 参数非法或越界（允许 1-365）时返回 `400` 的 RFC 7807 响应，不再抛出 500。
- 匿名提交接口统一错误语义：验证码无效 `400`、重复申请 `409`、待审核容量已满 `409`、安全策略拒绝 `403`、限流 `429`、验证码服务不可用 `503`，全部为 `application/problem+json`。

### Fixed
- 修正委托管理员对批量操作、统计与撤回子资源的 RBAC 授权，避免子资源路径被误判为无权限或过度放权。
- 补齐管理员成员提交通知 Reason 缺失的 `author`，保证通知模板与订阅表达式正常匹配。

### Verification
- Java 单元测试 76 项通过（新增控制器默认页守卫、公开字段脱敏、匿名提交状态码契约、统计参数校验测试）。
- Halo `2.27.1` 本地实例验证：MCP 工具注册与分级、验证码提交链路、`/members` 在主题模板与插件默认页两种路径下的渲染、插件停用后前台页面无 500。

## [1.4.7] - 2026-10-12

### Added
- 为成员记录来源（前台自助申请/后台创建），并在申请记录中标注来源；旧数据按历史记录展示。
- 增加可配置的待审核申请容量，达到上限时公开提交接口返回 `409`，并通过状态索引进行计数。

### Verification
- 未单独发布，相关能力在 `1.5.0` 中一并交付并重新验证。

## [1.4.6] - 2026-09-22

### Changed
- 移除后台成员排序、编辑表单、批量接口和导出流程对旧 priority 字段的依赖，统一使用创建时间和名称排序。
- QQ 信息获取失败或未返回昵称/邮箱时不再伪造邮箱，头像兜底与资料状态分开返回。
- 申请输入保留纯文本语义，避免服务端预先 HTML 编码造成双重转义。

### Verification
- 通过 Java 测试、前端构建、TypeScript 类型检查和安全契约检查。

## [1.4.5] - 2026-08-20

### Changed
- 收紧成员查看、管理、匿名申请和撤回接口的权限边界，补齐只读角色所需的查询权限。
- 后台浮动申请入口改用数据属性和独立样式，移除内联事件与内联样式。
- 成员前台页链接使用新标签安全打开，并重新生成 Console、Widget 和二维码资源。
- 保持审核、下架、撤回原因通知的幂等标记，避免普通资料编辑重复发送审核邮件。

### Verification
- 通过 Java 测试、前端构建、TypeScript 类型检查、安全契约和 UI 源码契约。
- 完整安装包为 `plugin-members-1.4.5.jar`。

## [1.4.4] - 2026-08-20

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
