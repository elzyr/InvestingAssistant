package pl.investassist.auth_service.service;

import java.util.UUID;
import pl.investassist.auth_service.dto.AuthResponse;
import pl.investassist.auth_service.dto.LoginRequest;
import pl.investassist.auth_service.dto.RefreshTokenRequest;
import pl.investassist.auth_service.dto.RegisterRequest;
import pl.investassist.auth_service.dto.UserProfileResponse;

public interface AuthService {
  AuthResponse register(RegisterRequest request);

  AuthResponse login(LoginRequest request);

  AuthResponse refreshToken(RefreshTokenRequest request);

  void logout(String refreshToken);

  UserProfileResponse getUserProfile(UUID userId);
}
