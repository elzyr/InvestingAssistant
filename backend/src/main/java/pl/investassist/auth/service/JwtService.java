package pl.investassist.auth.service;

import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pl.investassist.auth.entity.User;
import pl.investassist.security.JwtKeyUtils;

@Service
public class JwtService {

  @Value("${jwt.secret}")
  private String secretKey;

  @Value("${jwt.expiration-ms}")
  private long jwtExpirationMs;

  private SecretKey signingKey;

  @PostConstruct
  void init() {
    this.signingKey = JwtKeyUtils.hmacKeyFromSecret(secretKey);
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

  public long getJwtExpirationMs() {
    return jwtExpirationMs;
  }
}
