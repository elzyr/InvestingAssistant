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
    AuthResponse response = authService.register(request);
    ResponseCookie cookie = createRefreshTokenCookie(response.refreshToken(), refreshExpirationSeconds);
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(response);
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    AuthResponse response = authService.login(request);
    ResponseCookie cookie = createRefreshTokenCookie(response.refreshToken(), refreshExpirationSeconds);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(response);
  }

  @PostMapping("/refresh")
  public ResponseEntity<AuthResponse> refreshToken(
      @CookieValue(name = "refreshToken", required = false) String cookieToken,
      @RequestBody(required = false) RefreshTokenRequest request) {

    String token = cookieToken != null ? cookieToken : (request != null ? request.refreshToken() : null);
    if (token == null || token.isBlank()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    AuthResponse response = authService.refreshToken(new RefreshTokenRequest(token));
    ResponseCookie cookie = createRefreshTokenCookie(response.refreshToken(), refreshExpirationSeconds);

    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(response);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = "refreshToken", required = false) String cookieToken,
      @RequestBody(required = false) RefreshTokenRequest request) {

    String token = cookieToken != null ? cookieToken : (request != null ? request.refreshToken() : null);
    if (token != null && !token.isBlank()) {
      authService.logout(token);
    }

    ResponseCookie cleanCookie = createRefreshTokenCookie("", 0);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cleanCookie.toString())
        .build();
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
