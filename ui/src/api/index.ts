import { axiosInstance } from "@halo-dev/api-client";
import type { Member, MemberGroup } from "@/types";

const baseURL = "/apis/member.plugin.halo.run/v1alpha1";
const batchBaseURL = "/apis/api.member.plugin.halo.run/v1alpha1";

export interface ListResult<T> {
  page: number;
  size: number;
  total: number;
  items: T[];
}

type ApiResponse<T = unknown> = Promise<{ data: T }>;

export interface MemberQueryParams {
  page?: number;
  size?: number;
  keyword?: string;
  status?: string;
  groupName?: string;
  sort?: string;
}

export interface BatchOperationResult {
  total: number;
  success: number;
  failed: number;
  failedMembers: string[];
  message: string;
}

interface MembersCoreApiClient {
  member: {
    list: (params?: { page?: number; size?: number }) => ApiResponse<ListResult<Member>>;
    get: (name: string) => ApiResponse<Member>;
    create: (member: unknown) => ApiResponse<Member>;
    update: (name: string, member: unknown) => ApiResponse<Member>;
    patch: (name: string, patch: unknown[]) => ApiResponse<Member>;
    delete: (name: string) => ApiResponse<unknown>;
  };
  memberGroup: {
    list: () => ApiResponse<ListResult<MemberGroup>>;
    get: (name: string) => ApiResponse<MemberGroup>;
    create: (group: unknown) => ApiResponse<MemberGroup>;
    update: (name: string, group: unknown) => ApiResponse<MemberGroup>;
    patch: (name: string, patch: unknown[]) => ApiResponse<MemberGroup>;
    delete: (name: string) => ApiResponse<unknown>;
  };
}

interface MembersBatchApiClient {
  member: {
    list: (params?: MemberQueryParams) => ApiResponse<ListResult<Member>>;
    batchApprove: (
      memberNames: string[],
      approved: boolean
    ) => ApiResponse<BatchOperationResult>;
    batchDelete: (memberNames: string[]) => ApiResponse<BatchOperationResult>;
    batchChangeGroup: (
      memberNames: string[],
      groupName: string
    ) => ApiResponse<BatchOperationResult>;
    batchChangePriority: (
      memberNames: string[],
      priority: number
    ) => ApiResponse<BatchOperationResult>;
    exportCsv: (memberNames?: string[]) => ApiResponse<string>;
    exportJson: (memberNames?: string[]) => ApiResponse<string>;
  };
}

export const membersCoreApiClient: MembersCoreApiClient = {
  member: {
    list: (params?: { page?: number; size?: number }) => {
      return axiosInstance.get(`${baseURL}/members`, { params });
    },
    get: (name: string) => {
      return axiosInstance.get(`${baseURL}/members/${name}`);
    },
    create: (member: any) => {
      return axiosInstance.post(`${baseURL}/members`, member);
    },
    update: (name: string, member: any) => {
      return axiosInstance.put(`${baseURL}/members/${name}`, member);
    },
    patch: (name: string, patch: any[]) => {
      return axiosInstance.patch(`${baseURL}/members/${name}`, patch, {
        headers: { "Content-Type": "application/json-patch+json" },
      });
    },
    delete: (name: string) => {
      return axiosInstance.delete(`${baseURL}/members/${name}`);
    },
  },
  memberGroup: {
    list: () => {
      return axiosInstance.get(`${baseURL}/membergroups`);
    },
    get: (name: string) => {
      return axiosInstance.get(`${baseURL}/membergroups/${name}`);
    },
    create: (group: any) => {
      return axiosInstance.post(`${baseURL}/membergroups`, group);
    },
    update: (name: string, group: any) => {
      return axiosInstance.put(`${baseURL}/membergroups/${name}`, group);
    },
    patch: (name: string, patch: any[]) => {
      return axiosInstance.patch(`${baseURL}/membergroups/${name}`, patch, {
        headers: { "Content-Type": "application/json-patch+json" },
      });
    },
    delete: (name: string) => {
      return axiosInstance.delete(`${baseURL}/membergroups/${name}`);
    },
  },
};

export const membersBatchApiClient: MembersBatchApiClient = {
  member: {
    list: (params?: MemberQueryParams) => {
      return axiosInstance.get(`${batchBaseURL}/members`, { params });
    },
    batchApprove: (memberNames: string[], approved: boolean) => {
      return axiosInstance.post(`${batchBaseURL}/members/-/batch-approve`, {
        memberNames,
        approved,
      });
    },
    batchDelete: (memberNames: string[]) => {
      return axiosInstance.post(`${batchBaseURL}/members/-/batch-delete`, {
        memberNames,
      });
    },
    batchChangeGroup: (memberNames: string[], groupName: string) => {
      return axiosInstance.post(`${batchBaseURL}/members/-/batch-change-group`, {
        memberNames,
        groupName,
      });
    },
    batchChangePriority: (memberNames: string[], priority: number) => {
      return axiosInstance.post(`${batchBaseURL}/members/-/batch-change-priority`, {
        memberNames,
        priority,
      });
    },
    exportCsv: (memberNames: string[] = []) => {
      return axiosInstance.post(`${batchBaseURL}/members/-/export-csv`, {
        memberNames,
      });
    },
    exportJson: (memberNames: string[] = []) => {
      return axiosInstance.post(`${batchBaseURL}/members/-/export-json`, {
        memberNames,
      });
    },
  },
};
