package run.halo.members.exception;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;
import lombok.Data;

/**
 * 标准化错误响应
 * 
 * @author Sky
 * @since 2.0.0
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    
    /**
     * 错误代码
     */
    private String code;
    
    /**
     * 错误消息
     */
    private String message;
    
    /**
     * 详细错误信息（如字段验证错误）
     */
    private Map<String, Object> details;
    
    /**
     * 错误发生时间
     */
    private Instant timestamp;
    
    /**
     * 请求路径
     */
    private String path;
    
    /**
     * 跟踪ID（用于日志关联）
     */
    private String traceId;
}