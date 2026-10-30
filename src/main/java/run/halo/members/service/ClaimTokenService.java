package run.halo.members.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;

/**
 * 成员认领链接 token 服务
 * 负责生成、验证、存储和作废一次性认领 token
 *
 * @since 1.9.0
 */
@Service
@RequiredArgsConstructor
public class ClaimTokenService {

    private static final String CLAIM_TOKEN_ANNOTATION = "member.plugin.halo.run/claim-token";
    private static final String CLAIM_TOKEN_EXPIRES_ANNOTATION = "member.plugin.halo.run/claim-token-expires";
    private static final int TOKEN_BYTES = 32;
    private static final long DEFAULT_EXPIRY_HOURS = 168; // 7 days

    private final ReactiveExtensionClient client;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 为成员生成认领 token
     *
     * @param memberName 成员名称（metadata.name）
     * @param expiresAt 过期时间（可选，默认 7 天后）
     * @return 生成的 token 字符串
     */
    public Mono<String> generateToken(String memberName, Instant expiresAt) {
        return client.fetch(Member.class, memberName)
            .flatMap(member -> {
                String token = generateSecureToken();
                Instant expiry = expiresAt != null
                    ? expiresAt
                    : Instant.now().plusSeconds(DEFAULT_EXPIRY_HOURS * 3600);

                var annotations = member.getMetadata().getAnnotations();
                if (annotations == null) {
                    annotations = new java.util.HashMap<>();
                    member.getMetadata().setAnnotations(annotations);
                }
                annotations.put(CLAIM_TOKEN_ANNOTATION, token);
                annotations.put(CLAIM_TOKEN_EXPIRES_ANNOTATION, expiry.toString());

                return client.update(member).thenReturn(token);
            });
    }

    /**
     * 验证 token 并返回对应的成员
     *
     * @param token 认领 token
     * @return 成员（如果 token 有效），否则 empty
     */
    public Mono<Member> validateToken(String token) {
        return client.list(Member.class, member -> {
            var annotations = member.getMetadata().getAnnotations();
            if (annotations == null) {
                return false;
            }
            String storedToken = annotations.get(CLAIM_TOKEN_ANNOTATION);
            if (storedToken == null || !storedToken.equals(token)) {
                return false;
            }
            // 检查过期时间
            String expiresStr = annotations.get(CLAIM_TOKEN_EXPIRES_ANNOTATION);
            if (expiresStr != null) {
                try {
                    Instant expires = Instant.parse(expiresStr);
                    if (Instant.now().isAfter(expires)) {
                        return false;
                    }
                } catch (DateTimeParseException e) {
                    return false;
                }
            }
            return true;
        }, null).next();
    }

    /**
     * 作废 token（认领成功后调用）
     *
     * @param memberName 成员名称
     * @return 更新后的成员
     */
    public Mono<Member> invalidateToken(String memberName) {
        return client.fetch(Member.class, memberName)
            .flatMap(member -> {
                var annotations = member.getMetadata().getAnnotations();
                if (annotations != null) {
                    annotations.remove(CLAIM_TOKEN_ANNOTATION);
                    annotations.remove(CLAIM_TOKEN_EXPIRES_ANNOTATION);
                }
                return client.update(member);
            });
    }

    /**
     * 检查成员是否已有有效 token
     *
     * @param memberName 成员名称
     * @return token（如果存在且有效），否则 empty
     */
    public Mono<String> getExistingToken(String memberName) {
        return client.fetch(Member.class, memberName)
            .flatMap(member -> {
                var annotations = member.getMetadata().getAnnotations();
                if (annotations == null) {
                    return Mono.empty();
                }
                String token = annotations.get(CLAIM_TOKEN_ANNOTATION);
                if (token == null) {
                    return Mono.empty();
                }
                // 检查是否过期
                String expiresStr = annotations.get(CLAIM_TOKEN_EXPIRES_ANNOTATION);
                if (expiresStr != null) {
                    try {
                        Instant expires = Instant.parse(expiresStr);
                        if (Instant.now().isAfter(expires)) {
                            return Mono.empty();
                        }
                    } catch (DateTimeParseException e) {
                        return Mono.empty();
                    }
                }
                return Mono.just(token);
            });
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
