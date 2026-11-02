package run.halo.members.cache;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheServiceTest {

    @Test
    @DisplayName("按模式删除缓存时会按字面量匹配非通配符字符")
    void evictByPatternShouldEscapeRegexCharacters() {
        CacheService cacheService = new CacheService();
        cacheService.put("member.groups:1", "matched");
        cacheService.put("memberXgroups:1", "kept");

        cacheService.evictByPattern("member.groups:*");

        assertNull(cacheService.get("member.groups:1", String.class));
        assertTrue("kept".equals(cacheService.get("memberXgroups:1", String.class)));
    }

    @Test
    @DisplayName("缓存类型不匹配时会清理坏条目")
    void getShouldEvictValueWithUnexpectedType() {
        CacheService cacheService = new CacheService();
        cacheService.put("typed", "value");

        assertNull(cacheService.get("typed", Integer.class));
        assertNull(cacheService.get("typed", String.class));
    }

    @Test
    @DisplayName("缓存条目数量会被限制")
    void cacheShouldCapEntryCount() {
        CacheService cacheService = new CacheService();

        for (int i = 0; i < 600; i++) {
            cacheService.put("key:" + i, i, Duration.ofMinutes(5));
        }

        assertTrue(cacheService.getStats().getTotalEntries() <= 512);
    }

    @Test
    @DisplayName("统计只数未过期条目，且过期项不会被当成有效数据返回")
    void statsCountsOnlyLiveEntries() throws InterruptedException {
        CacheService cacheService = new CacheService();
        cacheService.put("live", "v", Duration.ofMinutes(1));
        cacheService.put("dead", "v", Duration.ofMillis(1));
        Thread.sleep(10);

        assertEquals(1, cacheService.getStats().getTotalEntries());
        assertNull(cacheService.get("dead", String.class));
        assertEquals("v", cacheService.get("live", String.class));
    }

    @Test
    @DisplayName("惰性清理不会漏掉仍有效的条目（批量写入后计数准确）")
    void bulkWritesKeepEveryLiveEntry() {
        CacheService cacheService = new CacheService();
        for (int i = 0; i < 200; i++) {
            cacheService.put("bulk:" + i, "v", Duration.ofMinutes(5));
        }

        // 清理改成每 32 次写才扫一遍，但绝不能把未过期的条目一起删掉
        assertEquals(200, cacheService.getStats().getTotalEntries());
        assertEquals("v", cacheService.get("bulk:0", String.class));
        assertEquals("v", cacheService.get("bulk:199", String.class));
    }
}
