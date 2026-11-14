package pl.investassist.auth.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BcryptPasswordLimit.Validator.class)
public @interface BcryptPasswordLimit {

  String message() default "Password cannot exceed 72 bytes";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  final class Validator implements ConstraintValidator<BcryptPasswordLimit, String> {
    private static final int MAX_BCRYPT_BYTES = 72;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return value == null || value.getBytes(StandardCharsets.UTF_8).length <= MAX_BCRYPT_BYTES;
    }
  }
}
