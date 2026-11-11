package pl.investassist.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.investassist.auth.dto.AuthResponse;
import pl.investassist.auth.dto.RefreshTokenRequest;
import pl.investassist.auth.dto.UserProfileResponse;
import pl.investassist.auth.enums.Role;
import pl.investassist.auth.service.AuthService;
import pl.investassist.common.enums.Currency;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

  @Mock
  private AuthService authService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    AuthController controller = new AuthController(authService, 2592000000L);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void refreshToken_whenCookieIsBlank_fallsBackToRequestBodyToken() throws Exception {
    UserProfileResponse user = new UserProfileResponse(
        UUID.randomUUID(), "user@example.com", "Jan", "Kowalski", Role.USER, Currency.PLN, LocalDateTime.now());
    when(authService.refreshToken(new RefreshTokenRequest("body-refresh-token")))
        .thenReturn(AuthResponse.of("new-access", "new-refresh", 3600000L, user));

    mockMvc.perform(post("/api/auth/refresh")
            .cookie(new Cookie("refreshToken", ""))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"body-refresh-token\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("new-access"));

    verify(authService).refreshToken(any(RefreshTokenRequest.class));
  }
}
