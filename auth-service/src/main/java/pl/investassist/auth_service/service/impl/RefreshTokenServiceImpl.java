package pl.investassist.auth_service.service.impl;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import pl.investassist.auth_service.service.RefreshTokenService;

@Service
public class RefreshTokenServiceImpl implements RefreshTokenService {

  private static final String REDIS_PREFIX = "refresh_token:";

  private final StringRedisTemplate redisTemplate;
  private final long refreshExpirationMs;

  public RefreshTokenServiceImpl(
      StringRedisTemplate redisTemplate,
      @Value("${jwt.refresh-expiration-ms}") long refreshExpirationMs) {
    this.redisTemplate = redisTemplate;
    this.refreshExpirationMs = refreshExpirationMs;
  }

  @Override
  public String createRefreshToken(UUID userId) {
    String token = UUID.randomUUID().toString();
    String key = REDIS_PREFIX + token;
    redisTemplate
        .opsForValue()
        .set(key, userId.toString(), refreshExpirationMs, TimeUnit.MILLISECONDS);
    return token;
  }

  @Override
  public Optional<UUID> validateAndGetUserId(String token) {
    String key = REDIS_PREFIX + token;
    String userIdStr = redisTemplate.opsForValue().get(key);
    if (userIdStr == null) {
      return Optional.empty();
    }
    return Optional.of(UUID.fromString(userIdStr));
  }

  @Override
  public void deleteRefreshToken(String token) {
    String key = REDIS_PREFIX + token;
    redisTemplate.delete(key);
  }
}
