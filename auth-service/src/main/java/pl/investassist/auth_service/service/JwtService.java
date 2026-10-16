package pl.investassist.auth_service.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pl.investassist.auth_service.entity.User;

@Service
public class JwtService {

  @Value("${jwt.secret}")
  private String secretKey;

  @Value("${jwt.expiration-ms}")
  private long jwtExpirationMs;

  private SecretKey signingKey;
  private JwtParser jwtParser;

  @PostConstruct
  void init() {
    byte[] keyBytes = secretKey != null ? secretKey.getBytes(StandardCharsets.UTF_8) : new byte[0];
    if (keyBytes.length < 32) {
      throw new IllegalStateException("jwt.secret must be set and at least 32 bytes (HS256)");
    }
    this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    this.jwtParser = Jwts.parser().verifyWith(this.signingKey).build();
  }

  public String generateAccessToken(User user) {
    Date now = new Date();
    Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

    return Jwts.builder()
        .subject(user.getId().toString())
        .claim("email", user.getEmail())
        .claim("role", user.getRole().name())
        .claim("baseCurrency", user.getBaseCurrency().name())
        .issuedAt(now)
        .expiration(expiryDate)
        .signWith(signingKey)
        .compact();
  }

  public Claims extractClaims(String token) {
    return jwtParser.parseSignedClaims(token).getPayload();
  }

  public UUID extractUserId(String token) {
    return UUID.fromString(extractClaims(token).getSubject());
  }

  public boolean validateToken(String token) {
    try {
      Date exp = extractClaims(token).getExpiration();
      return exp != null && !exp.before(new Date());
    } catch (Exception e) {
      return false;
    }
  }

  public long getJwtExpirationMs() {
    return jwtExpirationMs;
  }
}
