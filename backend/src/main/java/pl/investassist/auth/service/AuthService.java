package pl.investassist.auth.service;

import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.investassist.auth.dto.AuthResponse;
import pl.investassist.auth.dto.LoginRequest;
import pl.investassist.auth.dto.RefreshTokenRequest;
import pl.investassist.auth.dto.RegisterRequest;
import pl.investassist.auth.dto.UserProfileResponse;
import pl.investassist.auth.entity.User;
import pl.investassist.auth.enums.Role;
import pl.investassist.auth.repository.UserRepository;
import pl.investassist.common.enums.Currency;

@Service
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final RefreshTokenService refreshTokenService;

  public AuthService(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      RefreshTokenService refreshTokenService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.refreshTokenService = refreshTokenService;
  }

  public AuthResponse register(RegisterRequest request) {
    String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
    if (userRepository.existsByEmail(normalizedEmail)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "User with email " + normalizedEmail + " already exists");
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
    return issueTokens(savedUser);
  }

  public AuthResponse login(LoginRequest request) {
    String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
    User user =
        userRepository
            .findByEmail(normalizedEmail)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid email or password"));

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    return issueTokens(user);
  }

  @Transactional(readOnly = true)
  public AuthResponse refreshToken(RefreshTokenRequest request) {
    UUID userId =
        refreshTokenService
            .validateAndConsumeUserId(request.refreshToken())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token"));

    User user =
        userRepository
            .findById(userId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "User associated with token not found"));

    return issueTokens(user);
  }

  public void logout(String refreshToken) {
    if (refreshToken != null && !refreshToken.isBlank()) {
      refreshTokenService.deleteRefreshToken(refreshToken);
    }
  }

  private AuthResponse issueTokens(User user) {
    String accessToken = jwtService.generateAccessToken(user);
    String refreshToken = refreshTokenService.createRefreshToken(user.getId());
    return AuthResponse.of(
        accessToken,
        refreshToken,
        jwtService.getJwtExpirationMs(),
        UserProfileResponse.from(user));
  }
}
