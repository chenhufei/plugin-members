package run.halo.members.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 安全内容验证注解
 * 检查内容是否包含恶意脚本或危险字符
 * 
 * @author Sky
 * @since 2.0.0
 */
@Documented
@Constraint(validatedBy = SafeContentValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeContent {
    
    String message() default "内容包含不安全字符";
    
    Class<?>[] groups() default {};
    
    Class<? extends Payload>[] payload() default {};
}