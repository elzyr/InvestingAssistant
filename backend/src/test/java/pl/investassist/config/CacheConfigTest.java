package pl.investassist.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import pl.investassist.common.enums.Currency;
import pl.investassist.portfolio.client.MarketDataClient;

class CacheConfigTest {

  private final CacheConfig cacheConfig = new CacheConfig();

  @Test
  void redisCacheManager_configuresKeyPrefixAndDistinctTtls() {
    RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
    CacheManager manager = cacheConfig.redisCacheManager(connectionFactory);

    assertThat(manager).isInstanceOf(RedisCacheManager.class);
    RedisCacheManager redisManager = (RedisCacheManager) manager;
    redisManager.afterPropertiesSet();

    RedisCacheConfiguration spotConfig =
        redisManager.getCacheConfigurations().get(CacheConfig.CACHE_EXCHANGE_RATES_SPOT);
    RedisCacheConfiguration historicalConfig =
        redisManager.getCacheConfigurations().get(CacheConfig.CACHE_EXCHANGE_RATES_HISTORICAL);
    RedisCacheConfiguration searchConfig =
        redisManager.getCacheConfigurations().get(CacheConfig.CACHE_INSTRUMENT_SEARCH);

    assertThat(spotConfig).isNotNull();
    assertThat(spotConfig.getTtlFunction().getTimeToLive("k", null)).isEqualTo(Duration.ofMinutes(10));
    assertThat(spotConfig.getKeyPrefixFor(CacheConfig.CACHE_EXCHANGE_RATES_SPOT))
        .startsWith(CacheConfig.KEY_PREFIX);

    assertThat(historicalConfig).isNotNull();
    assertThat(historicalConfig.getTtlFunction().getTimeToLive("k", null)).isEqualTo(Duration.ofDays(7));

    assertThat(searchConfig).isNotNull();
    assertThat(searchConfig.getTtlFunction().getTimeToLive("k", null)).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void fxCacheResolver_routesSpotAndHistoricalSeparately() throws NoSuchMethodException {
    CacheManager manager = cacheConfig.fallbackCacheManager();
    assertThat(manager).isInstanceOf(ConcurrentMapCacheManager.class);

    CacheResolver resolver = cacheConfig.fxCacheResolver(manager);
    Method method = MarketDataClient.class.getMethod("getExchangeRate", Currency.class, Currency.class, LocalDate.class);

    CacheOperationInvocationContext<?> spotContext = mockContext(method, Currency.USD, Currency.PLN, null);
    CacheOperationInvocationContext<?> historicalContext =
        mockContext(method, Currency.USD, Currency.PLN, LocalDate.of(2025, 1, 15));

    assertThat(resolver.resolveCaches(spotContext))
        .extracting(Cache::getName)
        .containsExactly(CacheConfig.CACHE_EXCHANGE_RATES_SPOT);
    assertThat(resolver.resolveCaches(historicalContext))
        .extracting(Cache::getName)
        .containsExactly(CacheConfig.CACHE_EXCHANGE_RATES_HISTORICAL);
  }

  private CacheOperationInvocationContext<?> mockContext(Method method, Object... args) {
    return new CacheOperationInvocationContext<>() {
      @Override
      public org.springframework.cache.interceptor.BasicOperation getOperation() {
        return null;
      }

      @Override
      public Object getTarget() {
        return null;
      }

      @Override
      public Method getMethod() {
        return method;
      }

      @Override
      public Object[] getArgs() {
        return args;
      }
    };
  }
}
