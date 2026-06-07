package run.halo.members.exception;

/**
 * 重复成员异常
 * 
 * @author Sky
 * @since 2.0.0
 */
public class DuplicateMemberException extends RuntimeException {
    
    public DuplicateMemberException(String message) {
        super(message);
    }
    
    public DuplicateMemberException(String message, Throwable cause) {
        super(message, cause);
    }
}