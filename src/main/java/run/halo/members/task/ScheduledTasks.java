package run.halo.members.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import run.halo.members.cache.CacheService;
import run.halo.members.security.RateLimitService;

/**
 * 定时任务
 * 负责清理缓存、频率限制等维护工作
 * 
 * @author Sky
 * @since 2.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {
    
    private final CacheService cacheService;
    private final RateLimitService rateLimitService;
    
    /**
     * 每5分钟清理一次过期缓存
     */
    @Scheduled(fixedRate = 300000) // 5分钟
    public void cleanupExpiredCache() {
        try {
            cacheService.cleanup();
            log.debug("定时清理过期缓存完成");
        } catch (Exception e) {
            log.error("清理过期缓存失败", e);
        }
    }
    
    /**
     * 每10分钟清理一次过期的频率限制记录
     */
    @Scheduled(fixedRate = 600000) // 10分钟
    public void cleanupRateLimitRecords() {
        try {
            rateLimitService.cleanup();
            log.debug("定时清理频率限制记录完成");
        } catch (Exception e) {
            log.error("清理频率限制记录失败", e);
        }
    }
    
    /**
     * 每小时输出系统统计信息
     */
    @Scheduled(fixedRate = 3600000) // 1小时
    public void logSystemStats() {
        try {
            var cacheStats = cacheService.getStats();
            log.info("系统统计 - 缓存条目数: {}", cacheStats.getTotalEntries());
        } catch (Exception e) {
            log.error("输出系统统计信息失败", e);
        }
    }
}