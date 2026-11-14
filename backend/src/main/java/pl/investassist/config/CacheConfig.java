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
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

@Configuration
public class CacheConfig implements CachingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

  public static final String CACHE_EXCHANGE_RATES_SPOT = "exchangeRatesSpot";
  public static final String CACHE_EXCHANGE_RATES_HISTORICAL = "exchangeRatesHistorical";
  public static final String CACHE_INSTRUMENT_SEARCH = "instrumentSearch";
  public static final String CACHE_PORTFOLIO_VALUATION = "portfolioValuation";
  public static final String CACHE_PORTFOLIO_TIMELINE = "portfolioTimeline";
  public static final String CACHE_PORTFOLIO_REPORT = "portfolioReport";
  public static final String CACHE_PORTFOLIO_PROJECTION = "portfolioProjection";
  public static final String KEY_PREFIX = "investassist:java:v2:";

  static RedisSerializer<Object> jsonSerializer() {
    BasicPolymorphicTypeValidator ptv =
        BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("pl.investassist.")
            .allowIfSubType("java.")
            .build();
    return GenericJacksonJsonRedisSerializer.builder()
        .enableSpringCacheNullValueSupport()
        .enableDefaultTyping(ptv)
        .build();
  }

  @Bean
  @ConditionalOnBean(RedisConnectionFactory.class)
  public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration baseConfig =
        RedisCacheConfiguration.defaultCacheConfig()
            .prefixCacheNameWith(KEY_PREFIX)
            .disableCachingNullValues()
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer()))
            .entryTtl(Duration.ofHours(24));

    Map<String, RedisCacheConfiguration> cacheConfigs =
        Map.of(
            CACHE_EXCHANGE_RATES_SPOT, baseConfig.entryTtl(Duration.ofMinutes(10)),
            CACHE_EXCHANGE_RATES_HISTORICAL, baseConfig.entryTtl(Duration.ofDays(7)),
            CACHE_INSTRUMENT_SEARCH, baseConfig.entryTtl(Duration.ofMinutes(15)),
            CACHE_PORTFOLIO_VALUATION, baseConfig.entryTtl(Duration.ofMinutes(2)),
            CACHE_PORTFOLIO_TIMELINE, baseConfig.entryTtl(Duration.ofMinutes(5)),
            CACHE_PORTFOLIO_REPORT, baseConfig.entryTtl(Duration.ofMinutes(10)),
            CACHE_PORTFOLIO_PROJECTION, baseConfig.entryTtl(Duration.ofMinutes(5)));

    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(baseConfig)
        .withInitialCacheConfigurations(cacheConfigs)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(CacheManager.class)
  public CacheManager fallbackCacheManager() {
    return new ConcurrentMapCacheManager(
        CACHE_EXCHANGE_RATES_SPOT,
        CACHE_EXCHANGE_RATES_HISTORICAL,
        CACHE_INSTRUMENT_SEARCH,
        CACHE_PORTFOLIO_VALUATION,
        CACHE_PORTFOLIO_TIMELINE,
        CACHE_PORTFOLIO_REPORT,
        CACHE_PORTFOLIO_PROJECTION);
  }

  @Bean
  public CacheResolver fxCacheResolver(CacheManager cacheManager) {
    return context -> {
      Object[] args = context.getArgs();
      java.time.LocalDate dateArg = args.length >= 3 && args[2] instanceof java.time.LocalDate d ? d : null;
      java.time.LocalDate todayWarsaw = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Warsaw"));
      boolean isSpotOrToday = dateArg == null || !dateArg.isBefore(todayWarsaw);
      String cacheName = isSpotOrToday ? CACHE_EXCHANGE_RATES_SPOT : CACHE_EXCHANGE_RATES_HISTORICAL;
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
