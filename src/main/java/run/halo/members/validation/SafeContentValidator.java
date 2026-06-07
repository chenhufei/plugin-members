package run.halo.members.validation;

import org.springframework.beans.factory.annotation.Autowired;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import run.halo.members.security.SecurityService;

/**
 * 安全内容验证器
 * 
 * @author Sky
 * @since 2.0.0
 */
public class SafeContentValidator implements ConstraintValidator<SafeContent, String> {
    
    @Autowired
    private SecurityService securityService;
    
    @Override
    public boolean isValid(String content, ConstraintValidatorContext context) {
        return securityService.isContentSafe(content);
    }
}