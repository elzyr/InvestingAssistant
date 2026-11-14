package pl.investassist.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import pl.investassist.auth.dto.RegisterRequest;
import pl.investassist.auth.entity.User;
import pl.investassist.auth.repository.UserRepository;
import pl.investassist.common.enums.Currency;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

  @Test
  void register_translatesConcurrentDataIntegrityViolationToConflict() {
    UserRepository userRepository = mock(UserRepository.class);
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    JwtService jwtService = mock(JwtService.class);
    RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    AuthService authService =
        new AuthService(userRepository, passwordEncoder, jwtService, refreshTokenService);

    when(userRepository.existsByEmail("dup@example.com")).thenReturn(false);
    when(passwordEncoder.encode("StrongPass123")).thenReturn("encoded");
    when(userRepository.save(any(User.class)))
        .thenThrow(new DataIntegrityViolationException("unique email violation"));

    RegisterRequest request =
        new RegisterRequest("DUP@example.com", "StrongPass123", "Jan", "Test", Currency.PLN);

    assertThatThrownBy(() -> authService.register(request))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void login_validatesCredentialsAndIssuesTokens() {
    UserRepository userRepository = mock(UserRepository.class);
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    JwtService jwtService = mock(JwtService.class);
    RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    AuthService authService =
        new AuthService(userRepository, passwordEncoder, jwtService, refreshTokenService);

    User user = new User("jan@example.com", "hashed", "Jan", "Kowalski", pl.investassist.auth.enums.Role.USER, Currency.PLN);
    java.util.UUID userId = java.util.UUID.randomUUID();
    user.setId(userId);

    when(userRepository.findByEmail("missing@example.com")).thenReturn(java.util.Optional.empty());
    assertThatThrownBy(() -> authService.login(new pl.investassist.auth.dto.LoginRequest("missing@example.com", "secret")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED));

    when(userRepository.findByEmail("jan@example.com")).thenReturn(java.util.Optional.of(user));
    when(passwordEncoder.matches("wrongpass", "hashed")).thenReturn(false);
    assertThatThrownBy(() -> authService.login(new pl.investassist.auth.dto.LoginRequest("JAN@example.com", "wrongpass")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED));

    when(passwordEncoder.matches("correctpass", "hashed")).thenReturn(true);
    when(jwtService.generateAccessToken(user)).thenReturn("access-jwt");
    when(jwtService.getJwtExpirationMs()).thenReturn(900_000L);
    when(refreshTokenService.createRefreshToken(userId)).thenReturn("refresh-token");

    var response = authService.login(new pl.investassist.auth.dto.LoginRequest(" jan@example.com ", "correctpass"));
    org.assertj.core.api.Assertions.assertThat(response.accessToken()).isEqualTo("access-jwt");
    org.assertj.core.api.Assertions.assertThat(response.refreshToken()).isEqualTo("refresh-token");
  }

  @Test
  void refreshToken_and_logout_handleInvalidAndBlankTokens() {
    UserRepository userRepository = mock(UserRepository.class);
    PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    JwtService jwtService = mock(JwtService.class);
    RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    AuthService authService =
        new AuthService(userRepository, passwordEncoder, jwtService, refreshTokenService);

    when(refreshTokenService.validateAndConsumeUserId("expired")).thenReturn(java.util.Optional.empty());
    assertThatThrownBy(() -> authService.refreshToken(new pl.investassist.auth.dto.RefreshTokenRequest("expired")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED));

    authService.logout("   ");
    org.mockito.Mockito.verify(refreshTokenService, org.mockito.Mockito.never()).deleteRefreshToken(org.mockito.ArgumentMatchers.any());
    authService.logout("valid-token");
    org.mockito.Mockito.verify(refreshTokenService).deleteRefreshToken("valid-token");
  }
}
