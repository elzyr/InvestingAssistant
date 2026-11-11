package pl.investassist.security;

import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;

public final class JwtKeyUtils {

  private JwtKeyUtils() {}

  public static SecretKey hmacKeyFromSecret(String secretKey) {
    byte[] keyBytes = secretKey != null ? secretKey.getBytes(StandardCharsets.UTF_8) : new byte[0];
    if (keyBytes.length < 32) {
      throw new IllegalStateException("jwt.secret must be set and at least 32 bytes (HS256)");
    }
    return Keys.hmacShaKeyFor(keyBytes);
  }
}
