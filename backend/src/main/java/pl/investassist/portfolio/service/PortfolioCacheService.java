package pl.investassist.portfolio.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.investassist.config.CacheConfig;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PortfolioCacheService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioCacheService.class);
    private static final String REDIS_VERSION_PREFIX = CacheConfig.KEY_PREFIX + "portfolioVersion::";
    private static final Duration VERSION_TTL = Duration.ofHours(24);
    private static final long REDIS_FALLBACK_BYPASS_NANOS = Duration.ofMinutes(15).toNanos();
    private static final RedisScript<Long> BUMP_TO_MIN_SCRIPT = RedisScript.of(
            "local cur = tonumber(redis.call('GET', KEYS[1]) or '0')\n"
                    + "local target = tonumber(ARGV[1])\n"
                    + "local ttl = tonumber(ARGV[2])\n"
                    + "local val = math.max(cur, target)\n"
                    + "redis.call('SET', KEYS[1], tostring(val), 'EX', ttl)\n"
                    + "return val",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ConcurrentHashMap<UUID, Long> localVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> bypassedUntilNanos = new ConcurrentHashMap<>();

    public PortfolioCacheService(@Autowired(required = false) StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean isCacheEnabled(UUID portfolioId) {
        if (portfolioId == null) {
            return false;
        }
        Long until = bypassedUntilNanos.get(portfolioId);
        if (until == null) {
            return true;
        }
        if (System.nanoTime() - until >= 0) {
            bypassedUntilNanos.remove(portfolioId, until);
            return true;
        }
        return false;
    }

    public String versionOf(UUID portfolioId) {
        if (portfolioId == null) {
            return "0";
        }
        Long remote = readRemoteVersion(portfolioId);
        if (remote != null) {
            long resolved = localVersions.merge(portfolioId, remote, Math::max);
            return Long.toString(resolved);
        }
        return Long.toString(localVersions.getOrDefault(portfolioId, 0L));
    }

    public void evictPortfolioAfterCommit(UUID portfolioId) {
        if (portfolioId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictPortfolio(portfolioId);
                }
            });
            return;
        }
        evictPortfolio(portfolioId);
    }

    public void evictPortfolio(UUID portfolioId) {
        if (portfolioId == null) {
            return;
        }
        if (stringRedisTemplate != null) {
            try {
                String key = REDIS_VERSION_PREFIX + portfolioId;
                Long next = stringRedisTemplate.opsForValue().increment(key);
                if (next != null) {
                    long localNext = localVersions.getOrDefault(portfolioId, 0L) + 1L;
                    long resolved = Math.max(next, localNext);
                    if (resolved > next) {
                        Long luaResult = stringRedisTemplate.execute(
                                BUMP_TO_MIN_SCRIPT,
                                List.of(key),
                                Long.toString(resolved),
                                Long.toString(VERSION_TTL.toSeconds()));
                        if (luaResult != null) {
                            resolved = luaResult;
                        }
                    } else {
                        stringRedisTemplate.expire(key, VERSION_TTL);
                    }
                    bypassedUntilNanos.remove(portfolioId);
                    localVersions.merge(portfolioId, resolved, Math::max);
                    return;
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to atomically increment portfolio cache version for portfolioId={}: {}", portfolioId, ex.getMessage());
            }
            bypassedUntilNanos.put(portfolioId, System.nanoTime() + REDIS_FALLBACK_BYPASS_NANOS);
            localVersions.compute(portfolioId, (id, curr) -> (curr == null ? 0L : curr) + 1L);
            return;
        }
        localVersions.compute(
                portfolioId,
                (id, local) -> (local != null ? local : 0L) + 1L);
    }

    private Long readRemoteVersion(UUID portfolioId) {
        if (stringRedisTemplate != null) {
            try {
                String raw = stringRedisTemplate.opsForValue().get(REDIS_VERSION_PREFIX + portfolioId);
                if (raw != null && !raw.isBlank()) {
                    return Long.parseLong(raw.trim());
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to read atomic Redis portfolio cache version for portfolioId={}: {}", portfolioId, ex.getMessage());
            }
        }
        return null;
    }
}
