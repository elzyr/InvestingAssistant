package pl.investassist.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration
public class CacheConfig implements CachingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

  public static final String CACHE_EXCHANGE_RATES_SPOT = "exchangeRatesSpot";
  public static final String CACHE_EXCHANGE_RATES_HISTORICAL = "exchangeRatesHistorical";
  public static final String CACHE_INSTRUMENT_SEARCH = "instrumentSearch";
  public static final String KEY_PREFIX = "investassist:java:";

  @Bean
  @ConditionalOnBean(RedisConnectionFactory.class)
  public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration baseConfig =
        RedisCacheConfiguration.defaultCacheConfig()
            .prefixCacheNameWith(KEY_PREFIX)
            .disableCachingNullValues()
            .entryTtl(Duration.ofHours(24));

    Map<String, RedisCacheConfiguration> cacheConfigs =
        Map.of(
            CACHE_EXCHANGE_RATES_SPOT, baseConfig.entryTtl(Duration.ofMinutes(10)),
            CACHE_EXCHANGE_RATES_HISTORICAL, baseConfig.entryTtl(Duration.ofDays(7)),
            CACHE_INSTRUMENT_SEARCH, baseConfig.entryTtl(Duration.ofMinutes(15)));

    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(baseConfig)
        .withInitialCacheConfigurations(cacheConfigs)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(CacheManager.class)
  public CacheManager fallbackCacheManager() {
    return new ConcurrentMapCacheManager(
        CACHE_EXCHANGE_RATES_SPOT, CACHE_EXCHANGE_RATES_HISTORICAL, CACHE_INSTRUMENT_SEARCH);
  }

  @Bean
  public CacheResolver fxCacheResolver(CacheManager cacheManager) {
    return context -> {
      Object[] args = context.getArgs();
      boolean isSpot = args.length < 3 || args[2] == null;
      String cacheName = isSpot ? CACHE_EXCHANGE_RATES_SPOT : CACHE_EXCHANGE_RATES_HISTORICAL;
      Cache cache = cacheManager.getCache(cacheName);
      return cache != null ? List.of(cache) : List.of();
    };
  }

  @Override
  public CacheErrorHandler errorHandler() {
    return new CacheErrorHandler() {
      @Override
      public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache get failed for cache={}, key={}: {}", cache.getName(), key, exception.getMessage());
      }

      @Override
      public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("Cache put failed for cache={}, key={}: {}", cache.getName(), key, exception.getMessage());
      }

      @Override
      public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache evict failed for cache={}, key={}: {}", cache.getName(), key, exception.getMessage());
      }

      @Override
      public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("Cache clear failed for cache={}: {}", cache.getName(), exception.getMessage());
      }
    };
  }
}
