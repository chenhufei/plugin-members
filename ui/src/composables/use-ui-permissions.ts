import { axiosInstance } from "@halo-dev/api-client";
import { useQuery } from "@tanstack/vue-query";
import { computed, type ComputedRef } from "vue";

const UI_PERMISSIONS_ANNOTATION = "rbac.authorization.halo.run/ui-permissions";

export const QK_CURRENT_USER = "plugin:members:current-user";

interface CurrentUserResponse {
  user?: { metadata?: { name?: string } };
  roles?: Array<{
    metadata?: { name?: string; annotations?: Record<string, string> };
  }>;
}

/**
 * 读取当前账号的 UI 权限（角色注解里的 `ui-permissions`）。
 *
 * 控制台的 `v-permission` 指令只能作用在元素上，管不到 `VTabbar` 的 items 这类数据，
 * 而 `stores.currentUser()` 在插件上下文里并不保证已经载入（实测为空，权限判断会
 * 一直是「未知」）。这里直接读 `/users/-`：任何能进控制台的账号都能读自己，
 * 一次请求、缓存到会话结束。
 *
 * 请求未完成时 `permissions` 为 null，`can()` 按「有权限」处理——反过来会让管理员
 * 自己被挡在门外；等数据回来后自动收敛。
 */
export function useUiPermissions() {
  const { data } = useQuery({
    queryKey: [QK_CURRENT_USER],
    queryFn: async () => {
      const { data } = await axiosInstance.get<CurrentUserResponse>(
        "/apis/api.console.halo.run/v1alpha1/users/-",
      );
      return data;
    },
    staleTime: Infinity,
    retry: 1,
  });

  const permissions: ComputedRef<Set<string> | null> = computed(() => {
    const roles = data.value?.roles;
    if (!roles) {
      return null;
    }
    const granted = new Set<string>();
    for (const role of roles) {
      const raw = role.metadata?.annotations?.[UI_PERMISSIONS_ANNOTATION];
      if (!raw) {
        continue;
      }
      try {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) {
          parsed.forEach((item) => granted.add(String(item)));
        }
      } catch {
        // 注解写坏不该让控制台白屏，忽略这条角色即可。
      }
    }
    return granted;
  });

  /** 超级管理员角色是通配 `*`，与 v-permission 指令的处理保持一致。 */
  function can(permission: string) {
    if (!permissions.value) {
      return true;
    }
    return permissions.value.has("*") || permissions.value.has(permission);
  }

  return { permissions, can };
}
