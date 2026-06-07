package run.halo.members.cache;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
