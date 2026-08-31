package pl.investassist.gateway.filter;

import java.util.List;
import java.util.function.Predicate;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

@Component
public class RouteValidator {

  public static final List<String> OPEN_API_ENDPOINTS =
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

  public Predicate<ServerHttpRequest> isSecured =
      request ->
          OPEN_API_ENDPOINTS.stream().noneMatch(uri -> request.getURI().getPath().startsWith(uri));
}
