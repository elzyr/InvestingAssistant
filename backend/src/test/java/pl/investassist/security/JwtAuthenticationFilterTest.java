package pl.investassist.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtAuthenticationFilterTest {

  private static final String SECRET = "01234567890123456789012345678901";
  private JwtAuthenticationFilter filter;
  private SecretKey signingKey;

  @BeforeEach
  void setUp() {
    filter = new JwtAuthenticationFilter(SECRET);
    signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
  }

  private String generateToken(String subject, String email, String role, long expirationMs) {
    return Jwts.builder()
        .subject(subject)
        .claim("email", email)
        .claim("role", role)
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + expirationMs))
        .signWith(signingKey)
        .compact();
  }

  @Test
  void doFilter_whenOptionsRequest_passesThroughAndStripsSpoofedUserId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/portfolios");
    request.setServletPath("/api/portfolios");
    request.addHeader("X-User-Id", UUID.randomUUID().toString());
    request.addHeader("X-User-Role", "ADMIN");
    MockHttpServletResponse response = new MockHttpServletResponse();

    MockFilterChain chain = new MockFilterChain() {
      @Override
      public void doFilter(ServletRequest req, ServletResponse res) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        assertThat(httpReq.getHeader("X-User-Id")).isNull();
        assertThat(httpReq.getHeader("X-User-Role")).isNull();
        super.doFilter(req, res);
      }
    };

    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }

  @Test
  void doFilter_whenPublicAuthEndpoint_passesThroughAndStripsSpoofedUserId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
    request.setServletPath("/api/auth/login");
    request.addHeader("X-User-Id", UUID.randomUUID().toString());
    request.addHeader("X-User-Role", "ADMIN");
    MockHttpServletResponse response = new MockHttpServletResponse();

    MockFilterChain chain = new MockFilterChain() {
      @Override
      public void doFilter(ServletRequest req, ServletResponse res) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        assertThat(httpReq.getHeader("X-User-Id")).isNull();
        assertThat(httpReq.getHeader("X-User-Role")).isNull();
        super.doFilter(req, res);
      }
    };

    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }

  @Test
  void doFilter_whenPublicAuthEndpointWithTrailingSlash_passesThrough() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login/");
    request.setServletPath("/api/auth/login/");
    MockHttpServletResponse response = new MockHttpServletResponse();

    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }

  @Test
  void doFilter_whenPublicGetInstruments_passesThroughAndStripsSpoofedUserId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instruments");
    request.setServletPath("/api/instruments");
    request.addHeader("X-User-Id", UUID.randomUUID().toString());
    MockHttpServletResponse response = new MockHttpServletResponse();

    MockFilterChain chain = new MockFilterChain() {
      @Override
      public void doFilter(ServletRequest req, ServletResponse res) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        assertThat(httpReq.getHeader("X-User-Id")).isNull();
        super.doFilter(req, res);
      }
    };

    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }

  @Test
  void doFilter_whenPublicGetInstrumentByUuid_passesThrough() throws Exception {
    UUID id = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instruments/" + id);
    request.setServletPath("/api/instruments/" + id);
    MockHttpServletResponse response = new MockHttpServletResponse();

    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }

  @Test
  void doFilter_whenGetInstrumentWithArbitraryPath_treatedAsProtected() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instruments/nested/secret");
    request.setServletPath("/api/instruments/nested/secret");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void doFilter_whenPathTraversal_returns401WithoutInvokingChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instruments/../portfolios");
    request.setServletPath("/api/instruments/../portfolios");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void doFilter_whenUrlEncodedPathTraversal_returns401() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instruments/%2e%2e/portfolios");
    request.setServletPath("/api/instruments/%2e%2e/portfolios");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void doFilter_whenMatrixParameter_returns401WithoutInvokingChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instruments;jsessionid=123");
    request.setServletPath("/api/instruments;jsessionid=123");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void doFilter_whenUrlEncodedMatrixParameter_returns401() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instruments%3bjsessionid=123");
    request.setServletPath("/api/instruments%3bjsessionid=123");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void doFilter_whenProtectedEndpointMissingToken_returns401WithoutInvokingChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/portfolios");
    request.setServletPath("/api/portfolios");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void doFilter_whenProtectedEndpointInvalidToken_returns401WithoutInvokingChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/instruments");
    request.setServletPath("/api/instruments");
    request.addHeader("Authorization", "Bearer invalid-garbage-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void doFilter_whenSubjectNotValidUuid_returns401() throws Exception {
    String invalidSubjectToken = generateToken("not-a-valid-uuid", "user@test.com", "USER", 3600000);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/portfolios");
    request.setServletPath("/api/portfolios");
    request.addHeader("Authorization", "Bearer " + invalidSubjectToken);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void doFilter_whenProtectedEndpointValidToken_injectsUserIdAndOverwritesSpoofing() throws Exception {
    UUID validUserId = UUID.randomUUID();
    String validToken = generateToken(validUserId.toString(), "investor@example.com", "USER", 3600000);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/portfolios");
    request.setServletPath("/api/portfolios");
    request.addHeader("Authorization", "Bearer " + validToken);
    request.addHeader("X-User-Id", "attacker-spoofed-id");
    request.addHeader("X-User-Role", "ADMIN");
    MockHttpServletResponse response = new MockHttpServletResponse();

    MockFilterChain chain = new MockFilterChain() {
      @Override
      public void doFilter(ServletRequest req, ServletResponse res) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        assertThat(httpReq.getHeader("X-User-Id")).isEqualTo(validUserId.toString());
        assertThat(httpReq.getHeader("x-user-id")).isEqualTo(validUserId.toString());
        assertThat(httpReq.getHeader("X-User-Email")).isEqualTo("investor@example.com");
        assertThat(httpReq.getHeader("X-User-Role")).isEqualTo("USER");
        super.doFilter(req, res);
      }
    };

    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
  }
}
