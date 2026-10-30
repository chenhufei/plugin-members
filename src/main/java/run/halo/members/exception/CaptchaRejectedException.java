package run.halo.members.exception;

/**
 * 图形验证码校验失败异常
 *
 * @author Sky
 * @since 1.5.0
 */
public class CaptchaRejectedException extends RuntimeException {

    public CaptchaRejectedException(String message) {
        super(message);
    }
}
