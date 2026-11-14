package pl.investassist.security;

import tools.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import pl.investassist.auth.service.JwtService;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final Set<String> EXACT_PUBLIC_PATHS =
      Set.of(
          "/health",
          "/api/health",
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
    SecretKey signingKey = JwtService.hmacKeyFromSecret(secretKey);
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
      filterChain.doFilter(
          HeaderOverrideRequestWrapper.strip(request, STRIPPED_SECURITY_HEADERS), response);
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
        HeaderOverrideRequestWrapper.withUser(request, userId, email, role);

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

  private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

  private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    ERROR_MAPPER.writeValue(
        response.getWriter(),
        Map.of("message", message, "timestamp", System.currentTimeMillis()));
  }

  private static final class HeaderOverrideRequestWrapper extends HttpServletRequestWrapper {
    private final Map<String, String> overridesByLowerName;
    private final Map<String, String> displayNamesByLowerName;

    private HeaderOverrideRequestWrapper(HttpServletRequest request, Map<String, String> overrides) {
      super(request);
      this.overridesByLowerName = new LinkedHashMap<>();
      this.displayNamesByLowerName = new LinkedHashMap<>();
      overrides.forEach((key, value) -> {
        String lower = key.toLowerCase(Locale.ROOT);
        this.overridesByLowerName.put(lower, value);
        if (value != null) {
          this.displayNamesByLowerName.put(lower, key);
        }
      });
    }

    static HeaderOverrideRequestWrapper strip(HttpServletRequest request, Set<String> headers) {
      Map<String, String> stripped = new LinkedHashMap<>();
      for (String h : headers) {
        stripped.put(h, null);
      }
      return new HeaderOverrideRequestWrapper(request, stripped);
    }

    static HeaderOverrideRequestWrapper withUser(
        HttpServletRequest request, String userId, String email, String role) {
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("X-User-Id", userId);
      headers.put("X-User-Email", email);
      headers.put("X-User-Role", role);
      return new HeaderOverrideRequestWrapper(request, headers);
    }

    @Override
    public String getHeader(String name) {
      if (name != null) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (overridesByLowerName.containsKey(lower)) {
          return overridesByLowerName.get(lower);
        }
      }
      return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      if (name != null) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (overridesByLowerName.containsKey(lower)) {
          String val = overridesByLowerName.get(lower);
          return val != null ? Collections.enumeration(List.of(val)) : Collections.emptyEnumeration();
        }
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
          if (h != null && !overridesByLowerName.containsKey(h.toLowerCase(Locale.ROOT))) {
            names.add(h);
          }
        }
      }
      names.addAll(displayNamesByLowerName.values());
      return Collections.enumeration(names);
    }
  }
}
