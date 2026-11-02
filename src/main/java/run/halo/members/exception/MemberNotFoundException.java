package run.halo.members.exception;

/**
 * 成员不存在异常
 * 
 * @author Sky
 * @since 2.0.0
 */
public class MemberNotFoundException extends RuntimeException {
    
    public MemberNotFoundException(String message) {
        super(message);
    }
    
    public MemberNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}