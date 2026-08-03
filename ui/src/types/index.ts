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
    priority?: number;
    description?: string;
  };
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
  priority: number;
  annotations?: Record<string, string>;
}

export interface GroupFormState {
  displayName: string;
  description: string;
  annotations?: Record<string, string>;
}
