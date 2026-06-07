package run.halo.members.service;

import java.util.Map;

import reactor.core.publisher.Mono;

/**
 * 成员统计分析服务接口
 * 提供各种统计数据和分析功能
 * 
 * @author Sky
 * @since 2.1.0
 */
public interface MemberStatisticsService {
    
    /**
     * 获取总体统计信息
     * 
     * @return 统计信息
     */
    Mono<OverallStatistics> getOverallStatistics();
    
    /**
     * 获取按分组统计
     * 
     * @return 分组统计 Map<分组名称, 成员数量>
     */
    Mono<Map<String, Integer>> getGroupStatistics();
    
    /**
     * 获取按状态统计
     * 
     * @return 状态统计 Map<状态, 成员数量>
     */
    Mono<Map<String, Integer>> getStatusStatistics();
    
    /**
     * 获取按学校统计（Top 10）
     * 
     * @return 学校统计 Map<学校名称, 成员数量>
     */
    Mono<Map<String, Integer>> getSchoolStatistics();
    
    /**
     * 获取最近申请趋势（按天统计）
     * 
     * @param days 统计天数
     * @return 趋势数据 Map<日期, 申请数量>
     */
    Mono<Map<String, Integer>> getApplicationTrend(int days);
    
    /**
     * 总体统计信息
     */
    record OverallStatistics(
        int totalMembers,
        int approvedMembers,
        int pendingMembers,
        int rejectedMembers,
        int totalGroups,
        double approvalRate,
        String mostPopularGroup,
        String mostPopularSchool
    ) {}
}
