package pl.investassist.auth.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenService {

  private static final String REDIS_PREFIX = "refresh_token:";

  private final StringRedisTemplate redisTemplate;
  private final long refreshExpirationMs;

  public RefreshTokenService(
      StringRedisTemplate redisTemplate,
      @Value("${jwt.refresh-expiration-ms}") long refreshExpirationMs) {
    this.redisTemplate = redisTemplate;
    this.refreshExpirationMs = refreshExpirationMs;
  }

  public String createRefreshToken(UUID userId) {
    String token = UUID.randomUUID().toString();
    String key = REDIS_PREFIX + token;
    redisTemplate
        .opsForValue()
        .set(key, userId.toString(), Duration.ofMillis(refreshExpirationMs));
    return token;
  }

  public Optional<UUID> validateAndConsumeUserId(String token) {
    String key = REDIS_PREFIX + token;
    String userIdStr = redisTemplate.opsForValue().getAndDelete(key);
    if (userIdStr == null) {
      return Optional.empty();
    }
    return Optional.of(UUID.fromString(userIdStr));
  }

  public void deleteRefreshToken(String token) {
    String key = REDIS_PREFIX + token;
    redisTemplate.delete(key);
  }
}
