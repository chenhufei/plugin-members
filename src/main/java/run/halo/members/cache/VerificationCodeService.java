package run.halo.members.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 验证码服务 — 用于撤回申请时的邮箱验证
 * 使用内存存储，验证码6位数字，有效期5分钟
 * 
 * @since 1.0.3
 */
@Slf4j
@Service
public class VerificationCodeService {

    private static final int CODE_LENGTH = 6;
    private static final Duration CODE_EXPIRE_DURATION = Duration.ofMinutes(5);

    /**
     * 单个验证码允许的最大失败次数。
     *
     * <p>6 位数字只有一百万种组合，而验证码有效期 5 分钟、按邮箱维度存放；若只靠外层限流，
     * 分布式来源可以把这个邮箱的验证码在一分钟内试完。超过次数即作废，用户重新获取即可。
     */
    private static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, VerificationCodeEntry> codeStore = new ConcurrentHashMap<>();

    /**
     * 生成验证码并存储
     * 
     * @param email 邮箱地址（作为验证码的key）
     * @return 6位数字验证码
     */
    public String generateCode(String email) {
        String code = generateRandomCode();
        VerificationCodeEntry entry = new VerificationCodeEntry(code, Instant.now());
        codeStore.put(email, entry);
        
        log.info("验证码已生成并存储");
        return code;
    }

    /**
     * 验证验证码是否正确且未过期。
     *
     * <p>读取与删除必须是同一次原子操作：原来先 get 再 remove，两个并发请求都能读到同一条记录、
     * 都返回 true，验证码因此可被用两次。这里用 {@code computeIfPresent} 在同一个桶锁内
     * 完成「校验 + 作废」，并返回 null 让条目立即移除。
     *
     * <p>失败次数在同一个桶锁内累加，累计到 {@link #MAX_ATTEMPTS} 后条目作废：否则 6 位数字
     * 可以在有效期内被穷举，外层限流只是按邮箱/IP 限速，挡不住换 IP 的尝试。
     *
     * @param email 邮箱地址
     * @param code 用户输入的验证码
     * @return true 如果验证码正确且未过期
     */
    public boolean verifyCode(String email, String code) {
        if (email == null || code == null) {
            return false;
        }
        byte[] expected = code.getBytes(StandardCharsets.UTF_8);
        boolean[] matched = {false};
        codeStore.computeIfPresent(email, (key, entry) -> {
            if (entry.isExpired()) {
                log.warn("验证码已过期");
                return null;
            }
            if (entry.getAttempts() >= MAX_ATTEMPTS) {
                // 理论上失败到上限时就已移除，这里兜底：条目残留也不再参与校验
                log.warn("验证码失败次数已达上限，作废");
                return null;
            }
            // 常量时间比较，避免按字节提前返回泄漏验证码前缀
            if (MessageDigest.isEqual(entry.getCode().getBytes(StandardCharsets.UTF_8), expected)) {
                matched[0] = true;
                return null;
            }
            int attempts = entry.incrementAttempts();
            if (attempts >= MAX_ATTEMPTS) {
                log.warn("验证码错误次数达到上限（{} 次），已作废", attempts);
                return null;
            }
            log.warn("验证码不匹配（第 {} 次）", attempts);
            return entry;
        });
        if (matched[0]) {
            log.info("验证码验证成功");
        }
        return matched[0];
    }

    /**
     * 检查是否有有效的验证码（未过期）
     * 
     * @param email 邮箱地址
     * @return true 如果有有效验证码
     */
    public boolean hasValidCode(String email) {
        VerificationCodeEntry entry = codeStore.get(email);
        if (entry == null) {
            return false;
        }
        if (entry.isExpired()) {
            codeStore.remove(email);
            return false;
        }
        return true;
    }

    private String generateRandomCode() {
        int code = RANDOM.nextInt(1000000);
        return String.format("%06d", code);
    }

    /**
     * 作废验证码。邮件发送失败时必须回滚，否则残留的有效验证码会让用户在有效期内无法重试。
     *
     * @param email 邮箱地址（作为验证码的key）
     */
    public void invalidate(String email) {
        codeStore.remove(email);
    }

    public void cleanup() {
        codeStore.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * 验证码条目
     */
    private static class VerificationCodeEntry {
        private final String code;
        private final Instant createdAt;
        private int attempts;

        VerificationCodeEntry(String code, Instant createdAt) {
            this.code = code;
            this.createdAt = createdAt;
        }

        String getCode() {
            return code;
        }

        /**
         * 失败计数只在 {@code computeIfPresent} 的桶锁内调用，无需原子类型。
         */
        int incrementAttempts() {
            return ++attempts;
        }

        int getAttempts() {
            return attempts;
        }

        boolean isExpired() {
            return Instant.now().isAfter(createdAt.plus(CODE_EXPIRE_DURATION));
        }
    }
}
