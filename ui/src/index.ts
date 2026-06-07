import { definePlugin } from "@halo-dev/ui-shared";
import { IconTeam } from "@halo-dev/components";

import { markRaw } from "vue";

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
          },
        },
      },
    },
  ],
});
