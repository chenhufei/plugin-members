package run.halo.members.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 安全邮箱验证注解
 * 验证邮箱格式并检查是否为临时邮箱
 * 
 * @author Sky
 * @since 2.0.0
 */
@Documented
@Constraint(validatedBy = SafeEmailValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeEmail {
    
    String message() default "邮箱格式不正确或为临时邮箱";
    
    Class<?>[] groups() default {};
    
    Class<? extends Payload>[] payload() default {};
    
    /**
     * 是否允许临时邮箱
     */
    boolean allowTemporary() default false;
}