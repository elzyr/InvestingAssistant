package pl.investassist.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record AuthResponse(
    String accessToken,
    @JsonIgnore String refreshToken,
    String tokenType,
    long expiresIn,
    UserProfileResponse user) {
  public static AuthResponse of(
      String accessToken, String refreshToken, long expiresIn, UserProfileResponse user) {
    return new AuthResponse(accessToken, refreshToken, "Bearer", expiresIn, user);
  }
}
