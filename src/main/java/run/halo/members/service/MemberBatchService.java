package run.halo.members.service;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * 成员批量操作服务接口
 * 提供批量审核、删除、导出等功能
 * 
 * @author Sky
 * @since 2.1.0
 */
public interface MemberBatchService {
    
    /**
     * 批量审核成员
     * 
     * @param memberNames 成员名称列表
     * @param approved 是否通过审核
     * @return 操作结果
     */
    Mono<BatchOperationResult> batchApprove(List<String> memberNames, boolean approved);
    
    /**
     * 批量删除成员
     * 
     * @param memberNames 成员名称列表
     * @return 操作结果
     */
    Mono<BatchOperationResult> batchDelete(List<String> memberNames);
    
    /**
     * 批量修改分组
     * 
     * @param memberNames 成员名称列表
     * @param groupName 目标分组名称
     * @return 操作结果
     */
    Mono<BatchOperationResult> batchChangeGroup(List<String> memberNames, String groupName);
    
    /**
     * 批量修改优先级
     * 
     * @param memberNames 成员名称列表
     * @param priority 优先级
     * @return 操作结果
     */
    Mono<BatchOperationResult> batchChangePriority(List<String> memberNames, Integer priority);
    
    /**
     * 导出成员数据（CSV格式）
     * 
     * @param memberNames 成员名称列表（为空则导出全部）
     * @return CSV内容
     */
    Mono<String> exportToCSV(List<String> memberNames);
    
    /**
     * 导出成员数据（JSON格式）
     * 
     * @param memberNames 成员名称列表（为空则导出全部）
     * @return JSON内容
     */
    Mono<String> exportToJSON(List<String> memberNames);
    
    /**
     * 批量操作结果
     */
    record BatchOperationResult(
        int total,
        int success,
        int failed,
        List<String> failedMembers,
        String message
    ) {
        public static BatchOperationResult success(int total) {
            return new BatchOperationResult(total, total, 0, List.of(), "操作成功");
        }
        
        public static BatchOperationResult partial(int total, int success, List<String> failedMembers) {
            return new BatchOperationResult(
                total, 
                success, 
                total - success, 
                failedMembers, 
                String.format("部分成功：成功 %d，失败 %d", success, total - success)
            );
        }
        
        public static BatchOperationResult failed(int total, String message) {
            return new BatchOperationResult(total, 0, total, List.of(), message);
        }
    }
}
