export interface Member {
  apiVersion: string;
  kind: string;
  metadata: {
    name: string;
    generateName?: string;
    creationTimestamp?: string;
    deletionTimestamp?: string;
    annotations?: Record<string, string>;
  };
  spec: {
    displayName: string;
    email: string;
    school?: string;
    qq?: string;
    avatar?: string;
    background?: string;
    qqFriendLink?: string;
    groupName?: string;
    status: string;
    origin?: "FORM" | "CONSOLE" | string;
    /** 自定义加入时间（ISO-8601），为空时使用创建时间 */
    joinedAt?: string;
    /** @deprecated Legacy data only; no longer used for sorting or editing. */
    priority?: number;
    // 保留旧字段以兼容现有数据
    website?: string;
    description?: string;
  };
}

export interface MemberGroup {
  apiVersion: string;
  kind: string;
  metadata: {
    name: string;
    generateName?: string;
    creationTimestamp?: string;
    deletionTimestamp?: string;
    annotations?: Record<string, string>;
  };
  spec: {
    displayName: string;
    description?: string;
  };
}

/**
 * JSON Patch 操作（RFC 6902）。成员与分组的编辑走 PATCH，
 * 载荷形态固定为「操作 + 路径 + 值」，这里给出类型避免调用处各写各的。
 */
export interface JsonPatchOperation {
  op: "add" | "replace" | "remove";
  path: string;
  value?: unknown;
}

export interface CronMemberSubmit {
  apiVersion: "member.plugin.halo.run/v1alpha1";
  kind: "CronMemberSubmit";
  metadata: {
    name: string;
    creationTimestamp?: string;
    resourceVersion?: string;
    [key: string]: unknown;
  };
  spec: {
    cron: string;
    enabled: boolean;
    cleanupRejected: boolean;
    rejectedRetentionDays: number;
    cleanupPending: boolean;
    pendingRetentionDays: number;
  };
  status?: {
    lastScheduledTimestamp?: string;
    nextSchedulingTimestamp?: string;
    lastCleanedCount?: number;
    lastMessage?: string;
  };
}

/** 表单状态类型 — 用于 Form 组件与 Modal 之间的数据传递 */
export interface MemberFormState {
  displayName: string;
  email: string;
  school: string;
  qq: string;
  avatar: string;
  background: string;
  qqFriendLink: string;
  groupName: string;
  status: string;
  origin?: "FORM" | "CONSOLE" | string;
  /** 自定义加入时间，表单内为本地 datetime-local 值，提交前转换为 ISO */
  joinedAt?: string;
  /** @deprecated Legacy data only; no longer used for sorting or editing. */
  priority?: number;
  annotations?: Record<string, string>;
}

export interface GroupFormState {
  displayName: string;
  description: string;
  annotations?: Record<string, string>;
}
