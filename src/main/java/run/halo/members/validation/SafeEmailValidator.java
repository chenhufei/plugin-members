package run.halo.members.validation;

import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import run.halo.members.security.SecurityService;

/**
 * 安全邮箱验证器
 * 
 * @author Sky
 * @since 2.0.0
 */
public class SafeEmailValidator implements ConstraintValidator<SafeEmail, String> {
    
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );
    
    @Autowired
    private SecurityService securityService;
    
    private boolean allowTemporary;
    
    @Override
    public void initialize(SafeEmail constraintAnnotation) {
        this.allowTemporary = constraintAnnotation.allowTemporary();
    }
    
    @Override
    public boolean isValid(String email, ConstraintValidatorContext context) {
        if (!StringUtils.hasText(email)) {
            return false;
        }
        
        // 基本格式验证
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("邮箱格式不正确")
                .addConstraintViolation();
            return false;
        }
        
        // 检查是否为临时邮箱
        if (!allowTemporary && securityService.isTemporaryEmail(email)) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("不允许使用临时邮箱")
                .addConstraintViolation();
            return false;
        }
        
        return true;
    }
}