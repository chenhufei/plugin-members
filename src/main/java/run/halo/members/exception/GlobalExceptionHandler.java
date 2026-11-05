package run.halo.members.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理器：把散落的异常翻成统一的 RFC 7807 错误体。
 *
 * <p>与端点内直接写出的错误体共用 {@link ProblemResponses}，因此前端只需要认
 * {@code type/title/status/detail} 四个字段；{@code code} 供调用方做程序分支，
 * {@code errors} 只在参数校验失败时出现。
 *
 * @author Sky
 * @since 2.0.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String VALIDATION_SLUG = "invalid-request-parameters";

    /**
     * 处理参数验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemResponse> handleValidationException(
        MethodArgumentNotValidException e) {
        log.warn("参数验证失败: {}", e.getMessage());

        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            if (error instanceof FieldError fieldError) {
                fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
            }
        });

        return respond(ProblemResponses.validation("参数验证失败", fieldErrors));
    }

    /**
     * 处理成员不存在异常
     */
    @ExceptionHandler(MemberNotFoundException.class)
    public ResponseEntity<ProblemResponse> handleMemberNotFound(MemberNotFoundException e) {
        log.warn("成员不存在: {}", e.getMessage());
        return respond(problem(HttpStatus.NOT_FOUND, "member-not-found", "成员不存在",
            e.getMessage()));
    }

    /**
     * 处理重复成员异常
     */
    @ExceptionHandler(DuplicateMemberException.class)
    public ResponseEntity<ProblemResponse> handleDuplicateMember(DuplicateMemberException e) {
        log.warn("重复成员: {}", e.getMessage());
        return respond(problem(HttpStatus.CONFLICT, "duplicate-member-application", "成员申请重复",
            e.getMessage()));
    }

    /**
     * 处理分组不存在异常
     */
    @ExceptionHandler(GroupNotFoundException.class)
    public ResponseEntity<ProblemResponse> handleGroupNotFound(GroupNotFoundException e) {
        log.warn("分组不存在: {}", e.getMessage());
        return respond(problem(HttpStatus.NOT_FOUND, "member-group-not-found", "分组不存在",
            e.getMessage()));
    }

    /**
     * 处理频率限制异常
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemResponse> handleRateLimitExceeded(RateLimitExceededException e) {
        log.warn("请求频率超限: {}", e.getMessage());
        return respond(problem(HttpStatus.TOO_MANY_REQUESTS, "request-not-permitted",
            "请求过于频繁", "请求过于频繁，请稍后再试"));
    }

    /**
     * 处理安全异常
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ProblemResponse> handleSecurityException(SecurityException e) {
        log.error("安全异常: {}", e.getMessage());
        return respond(problem(HttpStatus.FORBIDDEN, "member-application-forbidden",
            "请求被拒绝", "请求被拒绝"));
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return respond(problem(HttpStatus.BAD_REQUEST, VALIDATION_SLUG, "请求参数无效",
            e.getMessage()));
    }

    /**
     * 处理带状态码的 Web 异常（请求体解析失败、方法不支持、主动抛出 4xx 等）。
     *
     * <p>这些异常都带了真实的状态语义，兜底到 500 会让调用方把「参数不对」当成「服务端炸了」。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemResponse> handleResponseStatus(ResponseStatusException e) {
        HttpStatusCode status = e.getStatusCode();
        boolean serverError = status.is5xxServerError();
        if (serverError) {
            log.error("请求处理失败: {} {}", status.value(), e.getMessage());
        } else {
            log.warn("请求被拒绝: {} {}", status.value(), e.getMessage());
        }
        String detail = serverError
            ? "服务器内部错误"
            : (e.getReason() == null || e.getReason().isBlank() ? "请求无法处理" : e.getReason());
        return respond(problem(status, serverError ? "internal-error" : "request-rejected",
            serverError ? "服务器内部错误" : "请求被拒绝", detail));
    }

    /**
     * 处理其他未知异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemResponse> handleGenericException(Exception e) {
        log.error("未知异常: ", e);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
            "服务器内部错误", "服务器内部错误"));
    }

    private static ProblemResponse problem(HttpStatusCode status, String typeSlug, String title,
        String detail) {
        return ProblemResponses.of(HttpStatus.valueOf(status.value()), typeSlug, title, detail)
            .withExtension("code", codeOf(status))
            .withExtension("timestamp", Instant.now().toString())
            .withExtension("traceId", MDC.get("traceId"));
    }

    private static String codeOf(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "INVALID_ARGUMENT";
            case 403 -> "SECURITY_ERROR";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMIT_EXCEEDED";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        };
    }

    private static ResponseEntity<ProblemResponse> respond(ProblemResponse problem) {
        return ProblemResponses.entity(problem);
    }
}
