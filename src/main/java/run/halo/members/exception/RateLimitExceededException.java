package run.halo.members.exception;

/**
 * 频率限制超出异常
 * 
 * @author Sky
 * @since 2.0.0
 */
public class RateLimitExceededException extends RuntimeException {
    
    public RateLimitExceededException(String message) {
        super(message);
    }
    
    public RateLimitExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}