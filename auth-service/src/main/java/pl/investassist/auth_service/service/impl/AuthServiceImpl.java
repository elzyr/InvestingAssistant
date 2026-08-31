package pl.investassist.auth_service.service.impl;

import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.investassist.auth_service.dto.AuthResponse;
import pl.investassist.auth_service.dto.LoginRequest;
import pl.investassist.auth_service.dto.RefreshTokenRequest;
import pl.investassist.auth_service.dto.RegisterRequest;
import pl.investassist.auth_service.dto.UserProfileResponse;
import pl.investassist.auth_service.entity.User;
import pl.investassist.auth_service.enums.Currency;
import pl.investassist.auth_service.enums.Role;
import pl.investassist.auth_service.exception.InvalidCredentialsException;
import pl.investassist.auth_service.exception.InvalidTokenException;
import pl.investassist.auth_service.exception.UserAlreadyExistsException;
import pl.investassist.auth_service.repository.UserRepository;
import pl.investassist.auth_service.service.AuthService;
import pl.investassist.auth_service.service.JwtService;
import pl.investassist.auth_service.service.RefreshTokenService;

@Service
public class AuthServiceImpl implements AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final RefreshTokenService refreshTokenService;

  public AuthServiceImpl(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      RefreshTokenService refreshTokenService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.refreshTokenService = refreshTokenService;
  }

  @Override
  @Transactional
  public AuthResponse register(RegisterRequest request) {
    String normalizedEmail = request.email().trim().toLowerCase(java.util.Locale.ROOT);
    if (userRepository.existsByEmail(normalizedEmail)) {
      throw new UserAlreadyExistsException(
          "User with email " + normalizedEmail + " already exists");
    }

    Currency currency = request.baseCurrency() != null ? request.baseCurrency() : Currency.PLN;
    String hashedPassword = passwordEncoder.encode(request.password());

    User user =
        new User(
            normalizedEmail,
            hashedPassword,
            request.firstName().trim(),
            request.lastName().trim(),
            Role.USER,
            currency);

    User savedUser = userRepository.save(user);

    String accessToken = jwtService.generateAccessToken(savedUser);
    String refreshToken = refreshTokenService.createRefreshToken(savedUser.getId());

    return AuthResponse.of(accessToken, refreshToken, jwtService.getJwtExpirationMs());
  }

  @Override
  public AuthResponse login(LoginRequest request) {
    String normalizedEmail = request.email().trim().toLowerCase(java.util.Locale.ROOT);
    User user =
        userRepository
            .findByEmail(normalizedEmail)
            .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new InvalidCredentialsException("Invalid email or password");
    }

    String accessToken = jwtService.generateAccessToken(user);
    String refreshToken = refreshTokenService.createRefreshToken(user.getId());

    return AuthResponse.of(accessToken, refreshToken, jwtService.getJwtExpirationMs());
  }

  @Override
  public AuthResponse refreshToken(RefreshTokenRequest request) {
    UUID userId =
        refreshTokenService
            .validateAndGetUserId(request.refreshToken())
            .orElseThrow(() -> new InvalidTokenException("Invalid or expired refresh token"));

    refreshTokenService.deleteRefreshToken(request.refreshToken());

    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new InvalidTokenException("User associated with token not found"));

    String newAccessToken = jwtService.generateAccessToken(user);
    String newRefreshToken = refreshTokenService.createRefreshToken(user.getId());

    return AuthResponse.of(newAccessToken, newRefreshToken, jwtService.getJwtExpirationMs());
  }

  @Override
  public void logout(String refreshToken) {
    if (refreshToken != null && !refreshToken.isBlank()) {
      refreshTokenService.deleteRefreshToken(refreshToken);
    }
  }

  @Override
  public UserProfileResponse getUserProfile(UUID userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new InvalidCredentialsException("User not found"));
    return UserProfileResponse.from(user);
  }
}
