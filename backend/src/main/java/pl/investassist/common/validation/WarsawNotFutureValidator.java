package pl.investassist.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class WarsawNotFutureValidator implements ConstraintValidator<WarsawNotFuture, LocalDateTime> {

  private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

  @Override
  public boolean isValid(LocalDateTime value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    LocalDate todayWarsaw = LocalDate.ofInstant(context.getClockProvider().getClock().instant(), WARSAW);
    return !value.toLocalDate().isAfter(todayWarsaw);
  }
}
