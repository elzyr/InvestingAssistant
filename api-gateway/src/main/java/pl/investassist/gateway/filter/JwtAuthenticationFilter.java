package pl.investassist.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

  private final RouteValidator routeValidator;
  private final JwtUtil jwtUtil;

  public JwtAuthenticationFilter(RouteValidator routeValidator, JwtUtil jwtUtil) {
    this.routeValidator = routeValidator;
    this.jwtUtil = jwtUtil;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();

    if (request.getMethod() == org.springframework.http.HttpMethod.OPTIONS) {
      return chain.filter(exchange);
    }

    if (routeValidator.isSecured.test(request)) {
      if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
        return onError(exchange, HttpStatus.UNAUTHORIZED);
      }

      String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
      if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        return onError(exchange, HttpStatus.UNAUTHORIZED);
      }

      String token = authHeader.substring(7);
      if (!jwtUtil.validateToken(token)) {
        return onError(exchange, HttpStatus.UNAUTHORIZED);
      }

      try {
        String userId = jwtUtil.extractUserId(token);
        String email = jwtUtil.extractEmail(token);
        String role = jwtUtil.extractRole(token);

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
      } catch (Exception e) {
        return onError(exchange, HttpStatus.UNAUTHORIZED);
      }
    }

    return chain.filter(exchange);
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
