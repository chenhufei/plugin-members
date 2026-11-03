/**
 * 成员的「生效邮箱」与「生效头像」。
 *
 * 本插件的默认约定是：成员只用 QQ 号登记，邮箱和头像都由 QQ 号派生
 * （`<QQ号>@qq.com`、QQ 头像）。所以后台列表、导出、通知目标这些地方
 * 不应该把「没登记邮箱/头像」当成缺失来提示，而应当直接呈现派生出来的值 ——
 * 它就是这个成员的邮箱和头像，不是一个需要管理员补的窟窿。
 *
 * 规则必须与后端 `EmailUtils.resolveEmail` / `AvatarUtils.defaultQqAvatar` 一致，
 * 改一处就要改另一处。
 */
import { qqAvatar } from "./qq-link";

const QQ_PATTERN = /^\d{5,12}$/;

export interface MemberContactSpec {
  email?: string;
  qq?: string;
  avatar?: string;
}

/** 生效邮箱：登记了用自己的，没登记用 `<QQ号>@qq.com`；都没有时返回空串 */
export function resolveMemberEmail(spec?: MemberContactSpec | null): string {
  const email = String(spec?.email ?? "").trim();
  if (email) return email;
  const qq = String(spec?.qq ?? "").trim();
  return QQ_PATTERN.test(qq) ? `${qq}@qq.com` : "";
}

/** 生效头像：登记了用自己的，没登记用 QQ 头像；都没有时返回空串 */
export function resolveMemberAvatar(spec?: MemberContactSpec | null): string {
  const avatar = String(spec?.avatar ?? "").trim();
  if (avatar) return avatar;
  const qq = String(spec?.qq ?? "").trim();
  return QQ_PATTERN.test(qq) ? qqAvatar(qq) : "";
}

/** 邮箱是否由 QQ 号派生（用于「这个地址从哪来」的说明，不用于判断缺失） */
export function isQqDerivedEmail(spec?: MemberContactSpec | null): boolean {
  return !String(spec?.email ?? "").trim() && resolveMemberEmail(spec) !== "";
}
