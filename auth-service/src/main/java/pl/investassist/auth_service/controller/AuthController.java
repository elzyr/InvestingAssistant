package pl.investassist.auth_service.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.investassist.auth_service.dto.AuthResponse;
import pl.investassist.auth_service.dto.LoginRequest;
import pl.investassist.auth_service.dto.RefreshTokenRequest;
import pl.investassist.auth_service.dto.RegisterRequest;
import pl.investassist.auth_service.dto.UserProfileResponse;
import pl.investassist.auth_service.service.AuthService;
import pl.investassist.auth_service.service.JwtService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService authService;
  private final JwtService jwtService;

  public AuthController(AuthService authService, JwtService jwtService) {
    this.authService = authService;
    this.jwtService = jwtService;
  }

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    AuthResponse response = authService.register(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    AuthResponse response = authService.login(request);
    return ResponseEntity.ok(response);
  }

  @PostMapping("/refresh")
  public ResponseEntity<AuthResponse> refreshToken(
      @Valid @RequestBody RefreshTokenRequest request) {
    AuthResponse response = authService.refreshToken(request);
    return ResponseEntity.ok(response);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@RequestBody RefreshTokenRequest request) {
    authService.logout(request.refreshToken());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  public ResponseEntity<UserProfileResponse> getCurrentUser(
      @RequestHeader(value = "Authorization", required = false) String authHeader) {
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    try {
      String token = authHeader.substring(7);
      if (!jwtService.validateToken(token)) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
      }
      UUID userId = jwtService.extractUserId(token);
      return ResponseEntity.ok(authService.getUserProfile(userId));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
  }
}
