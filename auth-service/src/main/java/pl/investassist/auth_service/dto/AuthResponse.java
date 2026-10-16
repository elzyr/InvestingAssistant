package pl.investassist.auth_service.dto;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    UserProfileResponse user) {
  public static AuthResponse of(
      String accessToken, String refreshToken, long expiresIn, UserProfileResponse user) {
    return new AuthResponse(accessToken, refreshToken, "Bearer", expiresIn, user);
  }
}
