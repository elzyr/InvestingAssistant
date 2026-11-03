package pl.investassist.auth.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.investassist.auth.dto.AuthResponse;
import pl.investassist.auth.dto.LoginRequest;
import pl.investassist.auth.dto.RefreshTokenRequest;
import pl.investassist.auth.dto.RegisterRequest;
import pl.investassist.auth.service.AuthService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  @Value("${app.security.cookie-secure:false}")
  private boolean cookieSecure;

  private final long refreshExpirationSeconds;
  private final AuthService authService;

  public AuthController(
      AuthService authService,
      @Value("${jwt.refresh-expiration-ms:2592000000}") long refreshExpirationMs) {
    this.authService = authService;
    this.refreshExpirationSeconds = refreshExpirationMs / 1000;
  }

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    return responseWithCookie(HttpStatus.CREATED, authService.register(request));
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return responseWithCookie(HttpStatus.OK, authService.login(request));
  }

  @PostMapping("/refresh")
  public ResponseEntity<AuthResponse> refreshToken(
      @CookieValue(name = "refreshToken", required = false) String cookieToken,
      @RequestBody(required = false) RefreshTokenRequest request) {

    String token = extractToken(cookieToken, request);
    if (token == null || token.isBlank()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    return responseWithCookie(HttpStatus.OK, authService.refreshToken(new RefreshTokenRequest(token)));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = "refreshToken", required = false) String cookieToken,
      @RequestBody(required = false) RefreshTokenRequest request) {

    String token = extractToken(cookieToken, request);
    if (token != null && !token.isBlank()) {
      authService.logout(token);
    }

    ResponseCookie cleanCookie = createRefreshTokenCookie("", 0);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cleanCookie.toString())
        .build();
  }

  private String extractToken(String cookieToken, RefreshTokenRequest request) {
    return cookieToken != null ? cookieToken : (request != null ? request.refreshToken() : null);
  }

  private ResponseEntity<AuthResponse> responseWithCookie(HttpStatus status, AuthResponse response) {
    ResponseCookie cookie = createRefreshTokenCookie(response.refreshToken(), refreshExpirationSeconds);
    return ResponseEntity.status(status)
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(response);
  }

  private ResponseCookie createRefreshTokenCookie(String token, long maxAgeSeconds) {
    return ResponseCookie.from("refreshToken", token)
        .httpOnly(true)
        .secure(cookieSecure)
        .path("/api/auth")
        .maxAge(maxAgeSeconds)
        .sameSite("Lax")
        .build();
  }
}
