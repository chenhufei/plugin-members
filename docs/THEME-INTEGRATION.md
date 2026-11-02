# 主题接入指南

本文说明主题如何接入成员管理插件（`PluginMembers`），全部接口按 Halo `2.27.1` + PluginMembers `0.0.2` 核对。

职责边界：**插件负责成员数据、申请与审核业务；主题只负责展示、布局与样式。** 主题不要在自己的模板里重写申请/审核表单逻辑，直接调用插件公开的 Finder、Widget 与匿名 API。

## 1. 先做插件可用性守卫

主题里所有依赖插件的地方都要先判断插件是否启用，否则插件停用后页面会 500：

```html
<section th:if="${pluginFinder.available('PluginMembers')}">
  ...
</section>
```

守卫写在外层元素的 `th:if` 上即可，Thymeleaf 不会求值被跳过的子节点，因此内层的 `th:with="${memberFinder.listApprovedMembers()}"` 是安全的。插件停用期间 `/members` 路由本身也会消失（由插件控制器注册），主题应准备 404 兜底而不是自行转发。

## 2. 模板内使用 `memberFinder`

模板中的 `memberFinder` 是插件提供的阻塞式包装（`run.halo.members.finders.MemberPublicQueryService`），返回已经 `block()` 的结果，可以直接在 Thymeleaf 表达式里调用：

| 调用 | 返回 | 说明 |
|------|------|------|
| `memberFinder.listApprovedMembers()` | `ListResult<MemberGroupVo>` | 已通过成员，按分组聚合 |
| `memberFinder.listAllGroups()` | `List<MemberGroupVo>` | 全部分组（不聚合成员） |

```html
<th:block th:with="result = ${memberFinder.listApprovedMembers()}">
  <div th:if="${result != null and !#lists.isEmpty(result.items)}">
    <section th:each="group : ${result.items}">
      <h2 th:text="${group.spec.displayName}">分组</h2>
      <div class="member-grid">
        <a th:each="member : ${group.members}"
           th:href="${member.spec.qqFriendLink}"
           target="_blank" rel="noopener noreferrer">
          <img th:if="${!#strings.isEmpty(member.spec.avatar)}"
               th:src="${member.spec.avatar}" th:alt="${member.spec.displayName}"
               loading="lazy" width="48" height="48" />
          <span th:text="${member.spec.displayName}">名称</span>
          <span th:if="${!#strings.isEmpty(member.spec.school)}"
                th:text="${member.spec.school}">学校</span>
        </a>
      </div>
    </section>
  </div>
</th:block>
```

要点：

- 只包含 `status = APPROVED` 的成员；待审核与拒绝的成员不会出现在这里。
- 分组按 `spec.displayName` 升序（同名按资源名），组内成员按创建时间倒序。
- 没有任何已通过成员的分组不会返回；`groupName` 无效的成员归入虚拟分组 `ungrouped`（显示名「未分组」）。
- 模板里能拿到完整 `spec`（含 `email`、`qq`、`status`）。**不要把邮箱、QQ、`status`、审核备注等隐私或内部字段渲染到公开页面**，也不要写进前端脚本。
- 结果有 5 分钟缓存，后台审核通过后前台最多延迟一个周期。

如果主题需要在服务端之外用 JS 取数，请改用公开只读 API（字段已脱敏）：

```http
GET /apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/members
GET /apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/membergroups
```

## 3. `/members` 页面与模板覆盖

插件自带一个默认成员页：

- 路由 `/members`（支持 `?group=<分组名>` 过滤）由插件控制器注册；
- 渲染时优先使用**主题的** `templates/members.html`；
- 主题没有该模板时，回落到插件内置的 `templates/members.html`；
- 两者都遵循 Halo 2.26+ 的版式约定，即套主题的 `layout :: html (head, content)` 片段，所以插件默认页会自动带上主题的页头、页脚与配色。

主题想自定义成员页，只要在自己的 `templates/` 下新增 `members.html`，无需注册路由。控制器注入的模型属性：

| 属性 | 类型 | 说明 |
|------|------|------|
| `groups` | `List<MemberGroupVo>` | 已按 `?group=` 过滤后的分组 |
| `allGroups` | `List<MemberGroupVo>` | 全部分组，用于渲染筛选导航 |
| `currentGroup` | `String` | 当前筛选的分组名，可能为 `null` |
| `pluginName` | `String` | 固定为 `PluginMembers`，用于拼接静态资源路径 |

模板骨架（Halo 2.26+ 契约）：

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="https://www.thymeleaf.org"
      th:replace="~{layout :: html(head = ~{::head}, content = ~{::content})}">
<th:block th:fragment="head">
  <title th:text="|成员 - ${site.title}|">成员</title>
</th:block>
<th:block th:fragment="content">
  <main>
    <h1>成员</h1>
    <nav>
      <a th:href="@{/members}" th:classappend="${currentGroup == null} ? 'is-active'">全部</a>
      <a th:each="g : ${allGroups}"
         th:href="@{/members(group=${g.metadata.name})}"
         th:classappend="${g.metadata.name == currentGroup} ? 'is-active'"
         th:text="${g.spec.displayName}"></a>
    </nav>
    <!-- 用 groups 渲染成员卡片 -->
  </main>
</th:block>
</html>
```

注意：主题的 `templates/layout.html` 必须提供 `html (head, content)` 片段，否则 Halo 会在主题检查时报错，插件默认页也无从套用版式。

## 4. 申请入口 Widget

插件通过 Halo 的 `TemplateFooterProcessor` 注入 `MemberApplyWidget`：

- 脚本与样式**全站注入**，任意页面都可以调用：

  ```js
  MemberApplyWidget.open();
  MemberApplyWidget.close();
  ```

- 悬浮申请按钮**只在 `/members` 路由注入**，避免全站出现浮动按钮。
- 主题想在自己的页面放置申请按钮，需要自行绑定：

  ```html
  <button type="button" onclick="MemberApplyWidget.open()">申请加入</button>
  ```

  NUCMA 主题额外实现了 `data-widget-open` 约定（`src/js/main.js`），在该主题下可以直接写：

  ```html
  <button type="button" data-widget-open="MemberApplyWidget">申请加入</button>
  ```

  这是主题侧约定，不是 Halo 或插件提供的能力，换用其他主题时不要依赖它。

- 资源地址（带版本参数，可直接引用）：
  - `/plugins/PluginMembers/assets/static/member-apply-widget.iife.js`
  - `/plugins/PluginMembers/assets/static/member-apply-widget.css`

表单本体（含图形验证码、限流与错误提示）由插件实现，主题不需要重复实现。验证码流程：`POST membersubmits/-/captcha` 取 `challengeId` 与图片，提交时连同用户输入一起 `POST membersubmits/-/submit`，全程不依赖 Cookie。

自助撤回入口同理，走 `plugins/PluginMembers/membersubmits/-/send-verification-code` 与 `/-/withdraw`，主题只需提供触发按钮。

## 5. 匿名接口的权限

插件自带角色模板 `role-template-member-anonymous-public`，聚合到 Halo 的 `anonymous` 角色，只开放：

- `api.member.plugin.halo.run/v1alpha1` 的 `membergroups`（读）
- `membersubmits/submit`、`membersubmits/captcha`（创建）
- `api.plugin.halo.run/v1alpha1` 的 `plugins/PluginMembers/members`、`plugins/PluginMembers/membersubmits`

主题不需要、也不应该为前台脚本申请任何管理权限。若主题内嵌自定义提交脚本，请按 [API.md](API.md) 的错误表处理 `400` / `403` / `409` / `429` / `503`，不要只判断 `200`。

## 6. 自检清单

主题接入成员管理后，建议逐项确认：

- [ ] 停用插件后，首页、`/members`、关于页均不出现 500。
- [ ] 主题存在 `templates/members.html` 时走主题模板；删除后回落插件默认页且仍套用主题版式。
- [ ] `?group=` 过滤、筛选高亮、空分组提示都正常。
- [ ] 公开页面未输出邮箱、QQ 号原值、审核状态或备注。
- [ ] 申请按钮可用、验证码可刷新、提交失败有可读提示。
- [ ] 亮暗模式与移动端宽度（390px）下卡片不破版。
- [ ] 控制台无与 `memberFinder` / `MemberApplyWidget` 相关的报错。
