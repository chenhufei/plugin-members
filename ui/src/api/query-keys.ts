import type { QueryClient } from "@tanstack/vue-query";

/**
 * 查询键集中在这里定义。
 *
 * 键是字符串约定，写错不会报错——只是失效调用静默变成 no-op（成员管理页就踩过：
 * 取数用 `grouped-members`，失效用 `members`，于是新建/删除后列表纹丝不动）。
 * 所有取数与失效都必须引用这里的常量。
 */
export const QK_MEMBERS = "plugin:members:members";
export const QK_GROUPED_MEMBERS = "plugin:members:grouped-members";
export const QK_MEMBER_GROUPS = "plugin:members:member-groups";
export const QK_MEMBER_SUBMITS = "member-submits";
export const QK_WITHDRAW_REQUESTS = "plugin:members:withdraw-requests";

/**
 * 成员数据变更后统一失效成员相关的视图。
 *
 * 失效按前缀匹配（vue-query 默认 exact: false），因此能覆盖带分页与筛选参数的变体。
 * 只有处于活跃状态的查询会真正回源：没打开的页面只是被标记为过期。
 */
export function invalidateMemberViews(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: [QK_MEMBERS] }),
    queryClient.invalidateQueries({ queryKey: [QK_GROUPED_MEMBERS] }),
    queryClient.invalidateQueries({ queryKey: [QK_MEMBER_GROUPS] }),
    queryClient.invalidateQueries({ queryKey: [QK_MEMBER_SUBMITS] }),
    queryClient.invalidateQueries({ queryKey: [QK_WITHDRAW_REQUESTS] }),
  ]);
}

/**
 * 分组本身变更后失效：分组列表 + 成员管理页的分组视图。
 * 新建/改名/删除分组都会改变分组视图的骨架，只失效 QK_MEMBER_GROUPS 是不会刷新的。
 */
export function invalidateMemberGroupViews(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: [QK_MEMBER_GROUPS] }),
    queryClient.invalidateQueries({ queryKey: [QK_GROUPED_MEMBERS] }),
  ]);
}
