package pl.investassist.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

  private static final List<String> OPEN_ENDPOINTS =
      List.of(
          "/api/auth/register",
          "/api/auth/login",
          "/api/auth/refresh",
          "/api/market",
          "/api/prices",
          "/api/currencies",
          "/api/instruments",
          "/actuator",
          "/v3/api-docs");

  @Value("${jwt.secret}")
  private String secretKey;

  private SecretKey signingKey;

  @PostConstruct
  void init() {
    getSigningKey();
  }

  private SecretKey getSigningKey() {
    if (signingKey != null) {
      return signingKey;
    }
    byte[] keyBytes = secretKey != null ? secretKey.getBytes(StandardCharsets.UTF_8) : new byte[0];
    if (keyBytes.length < 32) {
      throw new IllegalStateException("jwt.secret must be set and at least 32 bytes (HS256)");
    }
    this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    return signingKey;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();

    if (request.getMethod() == HttpMethod.OPTIONS || isPublic(request)) {
      return chain.filter(exchange);
    }

    String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      return onError(exchange, HttpStatus.UNAUTHORIZED);
    }

    String token = authHeader.substring(7);
    Claims claims;
    try {
      claims = Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
    } catch (Exception e) {
      return onError(exchange, HttpStatus.UNAUTHORIZED);
    }

    String userId = claims.getSubject();
    if (userId == null || userId.isBlank()) {
      return onError(exchange, HttpStatus.UNAUTHORIZED);
    }

    String email = claims.get("email", String.class);
    String role = claims.get("role", String.class);

    ServerHttpRequest mutatedRequest =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {
                  headers.set("X-User-Id", userId);
                  headers.set("X-User-Email", email != null ? email : "");
                  headers.set("X-User-Role", role != null ? role : "");
                })
            .build();

    return chain.filter(exchange.mutate().request(mutatedRequest).build());
  }

  private boolean isPublic(ServerHttpRequest request) {
    String path = request.getURI().getPath();
    return OPEN_ENDPOINTS.stream().anyMatch(path::startsWith);
  }

  private Mono<Void> onError(ServerWebExchange exchange, HttpStatus status) {
    exchange.getResponse().setStatusCode(status);
    return exchange.getResponse().setComplete();
  }

  @Override
  public int getOrder() {
    return -1;
  }
}
