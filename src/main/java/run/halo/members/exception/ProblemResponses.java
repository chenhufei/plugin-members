package run.halo.members.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

/**
 * {@link ProblemResponse} 的构造与写出工具。
 *
 * <p>插件里曾经同时存在五套错误形态（problem+json、自建 {@code ErrorResponse}、
 * {@code {success:false,message}}、{@code {code,message,details,timestamp}}、以及内核兜底），
 * 调用方只能按接口逐个试探。现在所有错误都从这里产出：内容类型固定
 * {@code application/problem+json}，字段固定 {@code type/title/status/detail}。
 *
 * @author Sky
 * @since 2.0.0
 */
public final class ProblemResponses {

    /** Halo 生态约定的错误类型前缀（与内核、官方插件保持一致）。 */
    public static final String TYPE_BASE = "https://halo.run/probs/";

    /** 未收录的具体类型时的通用标识。 */
    public static final String TYPE_GENERIC = TYPE_BASE + "request-failed";

    private ProblemResponses() {
    }

    /**
     * 按「类型 slug」构造，slug 会自动拼上 {@link #TYPE_BASE}。
     */
    public static ProblemResponse of(HttpStatus status, String typeSlug, String title,
        String detail) {
        return new ProblemResponse(TYPE_BASE + typeSlug, title, status.value(), detail);
    }

    /**
     * 直接给完整 type（需要 URI 形式或外部标准类型时用）。
     */
    public static ProblemResponse typeOf(HttpStatus status, String type, String title,
        String detail) {
        return new ProblemResponse(type, title, status.value(), detail);
    }

    /**
     * 构造并写成响应式响应体（公开/匿名接口用）。
     */
    public static Mono<ServerResponse> problem(HttpStatus status, String typeSlug, String title,
        String detail) {
        return write(of(status, typeSlug, title, detail));
    }

    /**
     * 构造并写成响应式响应体（已持有 {@link ProblemResponse} 时用，便于追加扩展字段）。
     */
    public static Mono<ServerResponse> write(ProblemResponse problem) {
        return ServerResponse.status(problem.getStatus())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .bodyValue(problem);
    }

    /**
     * 构造并写成 MVC 响应实体（{@code @RestControllerAdvice} 用）。
     *
     * <p>显式声明 {@code application/problem+json}：不依赖内容协商的默认选择，
     * 保证异常路径与正常路径的错误体走同一个内容类型。
     */
    public static ResponseEntity<ProblemResponse> entity(ProblemResponse problem) {
        return ResponseEntity.status(problem.getStatus())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem);
    }

    /**
     * 参数校验失败：{@code errors} 里放「字段 → 提示」。
     */
    public static ProblemResponse validation(String detail, Map<String, Object> fieldErrors) {
        return of(HttpStatus.BAD_REQUEST, "invalid-request-parameters", "参数验证失败", detail)
            .withExtension("code", "VALIDATION_ERROR")
            .withExtension("errors", fieldErrors);
    }
}
