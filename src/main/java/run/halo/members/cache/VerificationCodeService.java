package run.halo.members.cache;

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
        
        log.info("验证码已生成并存储: email={}", email);
        return code;
    }

    /**
     * 验证验证码是否正确且未过期
     * 
     * @param email 邮箱地址
     * @param code 用户输入的验证码
     * @return true 如果验证码正确且未过期
     */
    public boolean verifyCode(String email, String code) {
        VerificationCodeEntry entry = codeStore.get(email);
        if (entry == null) {
            log.warn("验证码不存在: email={}", email);
            return false;
        }

        if (entry.isExpired()) {
            codeStore.remove(email);
            log.warn("验证码已过期: email={}", email);
            return false;
        }

        boolean match = entry.getCode().equals(code);
        if (match) {
            // 验证成功后立即删除，防止重复使用
            codeStore.remove(email);
            log.info("验证码验证成功: email={}", email);
        } else {
            log.warn("验证码不匹配: email={}", email);
        }
        return match;
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

    public void cleanup() {
        codeStore.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * 验证码条目
     */
    private static class VerificationCodeEntry {
        private final String code;
        private final Instant createdAt;

        VerificationCodeEntry(String code, Instant createdAt) {
            this.code = code;
            this.createdAt = createdAt;
        }

        String getCode() {
            return code;
        }

        boolean isExpired() {
            return Instant.now().isAfter(createdAt.plus(CODE_EXPIRE_DURATION));
        }
    }
}
