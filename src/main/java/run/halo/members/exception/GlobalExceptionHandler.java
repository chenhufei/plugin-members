package run.halo.members.exception;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理器
 * 统一处理各种异常并返回标准化的错误响应
 * 
 * @author Sky
 * @since 2.0.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    /**
     * 处理参数验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        log.warn("参数验证失败: {}", e.getMessage());
        
        Map<String, Object> fieldErrors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            if (error instanceof FieldError fieldError) {
                fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
            }
        });
        
        ErrorResponse errorResponse = ErrorResponse.builder()
            .code("VALIDATION_ERROR")
            .message("参数验证失败")
            .details(fieldErrors)
            .timestamp(Instant.now())
            .build();
            
        return ResponseEntity.badRequest().body(errorResponse);
    }
    
    /**
     * 处理成员不存在异常
     */
    @ExceptionHandler(MemberNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMemberNotFound(MemberNotFoundException e) {
        log.warn("成员不存在: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
            .code("MEMBER_NOT_FOUND")
            .message(e.getMessage())
            .timestamp(Instant.now())
            .build();
            
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }
    
    /**
     * 处理重复成员异常
     */
    @ExceptionHandler(DuplicateMemberException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateMember(DuplicateMemberException e) {
        log.warn("重复成员: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
            .code("DUPLICATE_MEMBER")
            .message(e.getMessage())
            .timestamp(Instant.now())
            .build();
            
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }
    
    /**
     * 处理分组不存在异常
     */
    @ExceptionHandler(GroupNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleGroupNotFound(GroupNotFoundException e) {
        log.warn("分组不存在: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
            .code("GROUP_NOT_FOUND")
            .message(e.getMessage())
            .timestamp(Instant.now())
            .build();
            
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }
    
    /**
     * 处理频率限制异常
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException e) {
        log.warn("请求频率超限: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
            .code("RATE_LIMIT_EXCEEDED")
            .message("请求过于频繁，请稍后再试")
            .timestamp(Instant.now())
            .build();
            
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(errorResponse);
    }
    
    /**
     * 处理安全异常
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurityException(SecurityException e) {
        log.error("安全异常: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
            .code("SECURITY_ERROR")
            .message("请求被拒绝")
            .timestamp(Instant.now())
            .build();
            
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }
    
    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
            .code("INVALID_ARGUMENT")
            .message(e.getMessage())
            .timestamp(Instant.now())
            .build();
            
        return ResponseEntity.badRequest().body(errorResponse);
    }
    
    /**
     * 处理其他未知异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception e) {
        log.error("未知异常: ", e);
        
        ErrorResponse errorResponse = ErrorResponse.builder()
            .code("INTERNAL_ERROR")
            .message("服务器内部错误")
            .timestamp(Instant.now())
            .build();
            
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
}