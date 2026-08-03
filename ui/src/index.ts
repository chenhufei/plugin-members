import { definePlugin } from "@halo-dev/ui-shared";
import { IconTeam } from "@halo-dev/components";

import { markRaw } from "vue";
import "uno.css";

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "Root",
      route: {
        path: "/members",
        name: "Members",
        component: () => import("@/views/MemberList.vue"),
        meta: {
          permissions: ["plugin:members:view"],
          title: "成员",
          menu: {
            name: "成员",
            group: "content",
            icon: markRaw(IconTeam),
            priority: 30,
            mobile: true,
          },
        },
      },
    },
    {
      parentName: "Root",
      route: {
        path: "/members/submits",
        name: "MemberSubmits",
        component: () => import("@/views/MemberSubmitManagement.vue"),
        meta: { permissions: ["plugin:members:view"], title: "申请记录" },
      },
    },
    {
      parentName: "Root",
      route: {
        path: "/members/withdrawals",
        name: "MemberWithdrawals",
        redirect: "/members/submits?tab=withdrawals",
        meta: { permissions: ["plugin:members:manage"], title: "撤回审核" },
      },
    },
  ],
  // 控制台导航入口
  extensionPoints: {},
});
