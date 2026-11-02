package run.halo.members.validation;

import org.springframework.beans.factory.annotation.Autowired;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import run.halo.members.security.SecurityService;

/**
 * 学校名称验证器
 * 
 * @author Sky
 * @since 2.0.0
 */
public class ValidSchoolValidator implements ConstraintValidator<ValidSchool, String> {
    
    @Autowired
    private SecurityService securityService;
    
    @Override
    public boolean isValid(String schoolName, ConstraintValidatorContext context) {
        return securityService.isValidSchoolName(schoolName);
    }
}