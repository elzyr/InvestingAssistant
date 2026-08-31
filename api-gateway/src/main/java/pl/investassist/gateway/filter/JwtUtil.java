package pl.investassist.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

  @Value("${jwt.secret}")
  private String secretKey;

  private SecretKey getSigningKey() {
    byte[] keyBytes = secretKey != null ? secretKey.getBytes(StandardCharsets.UTF_8) : new byte[0];
    if (keyBytes.length < 32) {
      throw new IllegalStateException("jwt.secret must be set and at least 32 bytes (HS256)");
    }
    return Keys.hmacShaKeyFor(keyBytes);
  }

  public Claims extractClaims(String token) {
    return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
  }

  public String extractUserId(String token) {
    Claims claims = extractClaims(token);
    return claims.getSubject();
  }

  public String extractEmail(String token) {
    Claims claims = extractClaims(token);
    return claims.get("email", String.class);
  }

  public String extractRole(String token) {
    Claims claims = extractClaims(token);
    return claims.get("role", String.class);
  }

  public boolean validateToken(String token) {
    try {
      Claims claims = extractClaims(token);
      Date exp = claims.getExpiration();
      return exp != null && !exp.before(new Date());
    } catch (Exception e) {
      return false;
    }
  }
}
