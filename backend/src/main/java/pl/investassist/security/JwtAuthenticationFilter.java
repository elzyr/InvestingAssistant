package pl.investassist.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final Set<String> EXACT_PUBLIC_PATHS =
      Set.of(
          "/api/auth/register",
          "/api/auth/login",
          "/api/auth/refresh",
          "/api/auth/logout");

  private static final Set<String> STRIPPED_SECURITY_HEADERS =
      Set.of("x-user-id", "x-user-email", "x-user-role");

  @Value("${jwt.secret}")
  private String secretKey;

  private JwtParser jwtParser;

  public JwtAuthenticationFilter() {}

  public JwtAuthenticationFilter(String secretKey) {
    this.secretKey = secretKey;
    init();
  }

  @PostConstruct
  void init() {
    byte[] keyBytes = secretKey != null ? secretKey.getBytes(StandardCharsets.UTF_8) : new byte[0];
    if (keyBytes.length < 32) {
      throw new IllegalStateException("jwt.secret must be set and at least 32 bytes (HS256)");
    }
    SecretKey signingKey = Keys.hmacShaKeyFor(keyBytes);
    this.jwtParser = Jwts.parser().verifyWith(signingKey).build();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String rawServletPath = request.getServletPath() != null ? request.getServletPath() : "";
    String rawUri = request.getRequestURI() != null ? request.getRequestURI() : "";

    String decodedServletPath;
    String decodedUri;
    try {
      decodedServletPath = URLDecoder.decode(rawServletPath, StandardCharsets.UTF_8);
      decodedUri = URLDecoder.decode(rawUri, StandardCharsets.UTF_8);
    } catch (Exception e) {
      sendUnauthorized(response, "Invalid URI encoding");
      return;
    }

    if (hasTraversalOrMatrix(rawServletPath) || hasTraversalOrMatrix(rawUri)
        || hasTraversalOrMatrix(decodedServletPath) || hasTraversalOrMatrix(decodedUri)) {
      sendUnauthorized(response, "Path traversal or matrix parameters forbidden");
      return;
    }

    String path = rawServletPath.isBlank() ? rawUri : rawServletPath;
    try {
      path = URI.create(path).normalize().getPath();
    } catch (Exception ignored) {
    }
    if (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }

    if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublicEndpoint(request.getMethod(), path)) {
      filterChain.doFilter(new StripUserIdRequestWrapper(request), response);
      return;
    }

    String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      sendUnauthorized(response, "Missing or invalid Authorization header");
      return;
    }

    String token = authHeader.substring(7).trim();
    Claims claims;
    try {
      claims = jwtParser.parseSignedClaims(token).getPayload();
    } catch (Exception e) {
      sendUnauthorized(response, "Invalid or expired JWT token");
      return;
    }

    String userId = claims.getSubject();
    if (userId == null || userId.isBlank()) {
      sendUnauthorized(response, "Invalid JWT subject");
      return;
    }

    try {
      UUID.fromString(userId);
    } catch (IllegalArgumentException e) {
      sendUnauthorized(response, "Invalid JWT subject UUID");
      return;
    }

    String email = claims.get("email", String.class);
    String role = claims.get("role", String.class);

    HttpServletRequest authenticatedRequest =
        new AuthenticatedUserRequestWrapper(request, userId, email, role);

    filterChain.doFilter(authenticatedRequest, response);
  }

  private boolean hasTraversalOrMatrix(String value) {
    if (value == null) {
      return false;
    }
    String lower = value.toLowerCase(Locale.ROOT);
    return lower.contains("..") || lower.contains(";") || lower.contains("%2e") || lower.contains("%3b");
  }

  private boolean isPublicEndpoint(String method, String path) {
    if (EXACT_PUBLIC_PATHS.contains(path)) {
      return true;
    }
    return "GET".equalsIgnoreCase(method)
        && (path.equals("/api/instruments") || path.matches("^/api/instruments/[0-9a-fA-F-]+$"));
  }

  private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.getWriter().write("{\"message\":\"" + message + "\",\"timestamp\":" + System.currentTimeMillis() + "}");
  }

  private static class StripUserIdRequestWrapper extends HttpServletRequestWrapper {
    public StripUserIdRequestWrapper(HttpServletRequest request) {
      super(request);
    }

    @Override
    public String getHeader(String name) {
      if (name != null && STRIPPED_SECURITY_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        return null;
      }
      return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      if (name != null && STRIPPED_SECURITY_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        return Collections.emptyEnumeration();
      }
      return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      Enumeration<String> originalNames = super.getHeaderNames();
      if (originalNames == null) {
        return Collections.emptyEnumeration();
      }
      List<String> names = Collections.list(originalNames).stream()
          .filter(h -> h != null && !STRIPPED_SECURITY_HEADERS.contains(h.toLowerCase(Locale.ROOT)))
          .toList();
      return Collections.enumeration(names);
    }
  }

  private static class AuthenticatedUserRequestWrapper extends HttpServletRequestWrapper {
    private final String userId;
    private final String email;
    private final String role;

    public AuthenticatedUserRequestWrapper(
        HttpServletRequest request, String userId, String email, String role) {
      super(request);
      this.userId = userId;
      this.email = email;
      this.role = role;
    }

    @Override
    public String getHeader(String name) {
      if ("X-User-Id".equalsIgnoreCase(name)) {
        return userId;
      }
      if ("X-User-Email".equalsIgnoreCase(name)) {
        return email;
      }
      if ("X-User-Role".equalsIgnoreCase(name)) {
        return role;
      }
      return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      if ("X-User-Id".equalsIgnoreCase(name)) {
        return Collections.enumeration(List.of(userId));
      }
      if ("X-User-Email".equalsIgnoreCase(name)) {
        return email != null ? Collections.enumeration(List.of(email)) : Collections.emptyEnumeration();
      }
      if ("X-User-Role".equalsIgnoreCase(name)) {
        return role != null ? Collections.enumeration(List.of(role)) : Collections.emptyEnumeration();
      }
      return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      Set<String> names = new LinkedHashSet<>();
      Enumeration<String> originalNames = super.getHeaderNames();
      if (originalNames != null) {
        while (originalNames.hasMoreElements()) {
          String h = originalNames.nextElement();
          if (h != null
              && !"X-User-Id".equalsIgnoreCase(h)
              && !"X-User-Email".equalsIgnoreCase(h)
              && !"X-User-Role".equalsIgnoreCase(h)) {
            names.add(h);
          }
        }
      }
      names.add("X-User-Id");
      if (email != null) {
        names.add("X-User-Email");
      }
      if (role != null) {
        names.add("X-User-Role");
      }
      return Collections.enumeration(names);
    }
  }
}
