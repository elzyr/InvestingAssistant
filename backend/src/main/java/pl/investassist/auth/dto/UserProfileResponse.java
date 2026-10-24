package pl.investassist.auth.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import pl.investassist.auth.entity.User;
import pl.investassist.auth.enums.Role;
import pl.investassist.common.enums.Currency;

public record UserProfileResponse(
    UUID id,
    String email,
    String firstName,
    String lastName,
    Role role,
    Currency baseCurrency,
    LocalDateTime createdAt) {
  public static UserProfileResponse from(User user) {
    return new UserProfileResponse(
        user.getId(),
        user.getEmail(),
        user.getFirstName(),
        user.getLastName(),
        user.getRole(),
        user.getBaseCurrency(),
        user.getCreatedAt());
  }
}
