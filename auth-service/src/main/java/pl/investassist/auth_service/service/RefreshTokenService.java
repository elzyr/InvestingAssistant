package pl.investassist.auth_service.service;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenService {
  String createRefreshToken(UUID userId);

  Optional<UUID> validateAndGetUserId(String token);

  void deleteRefreshToken(String token);
}
