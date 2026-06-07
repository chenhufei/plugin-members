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

/** 表单状态类型 — 用于 Form 组件与 Modal 之间的数据传递 */
export interface MemberFormState {
  displayName: string;
  email: string;
  school: string;
  qq: string;
  avatar: string;
  qqFriendLink: string;
  groupName: string;
  status: string;
  priority: number;
  annotations?: Record<string, string>;
}

export interface GroupFormState {
  displayName: string;
  description: string;
  priority: number;
  annotations?: Record<string, string>;
}
