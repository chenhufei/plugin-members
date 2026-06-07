package run.halo.members.exception;

/**
 * 分组不存在异常
 * 
 * @author Sky
 * @since 2.0.0
 */
public class GroupNotFoundException extends RuntimeException {
    
    public GroupNotFoundException(String message) {
        super(message);
    }
    
    public GroupNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}