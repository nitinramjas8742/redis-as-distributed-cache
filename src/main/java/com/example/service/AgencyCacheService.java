package com.example.service;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Cross-pod coherence markers backed by Redis.
 * Stores ONLY DB @Version per agencyCode (agency:ver:CODE), not full data.
 * Fail-open everywhere: Redis errors degrade to DB reads, never 500s.
 */
@ApplicationScoped
public class AgencyCacheService {

    private static final Logger LOG = Logger.getLogger(AgencyCacheService.class);
    private static final String VER_PREFIX = "agency:ver:";

    @Inject
    RedisDataSource redisDataSource;

    @ConfigProperty(name = "app.cache.agency.ttl-seconds", defaultValue = "3600")
    long ttlSeconds;

    private ValueCommands<String, Long> versionCommands;
    private KeyCommands<String> keyCommands;

    @PostConstruct
    void init() {
        this.versionCommands = redisDataSource.value(Long.class);
        this.keyCommands = redisDataSource.key();
    }

    private String buildVersionKey(String agencyCode) {
        return VER_PREFIX + agencyCode;
    }

    /**
     * Version marker for cross-pod coherence. Stores DB @Version per agencyCode.
     * Small payload (8 bytes) instead of full DTO. Fail-open: null on miss/error.
     */
    public Long getVersion(String agencyCode) {
        if (agencyCode == null || agencyCode.isBlank()) {
            return null;
        }
        try {
            return versionCommands.get(buildVersionKey(agencyCode));
        } catch (Exception e) {
            LOG.warnf("[VER Warning] Redis GETVER failed for %s: %s", agencyCode, e.getMessage());
            return null;
        }
    }

    public void putVersion(String agencyCode, Long version) {
        if (agencyCode == null) {
            return;
        }
        // Coerce NULL (pre-backfill rows) -> 0 so Redis always warms.
        // Without this every GET sees MISS and forces DB REFRESH in a loop.
        long v = (version == null) ? 0L : version;
        try {
            versionCommands.setex(buildVersionKey(agencyCode), ttlSeconds, v);
        } catch (Exception e) {
            LOG.warnf("[VER Warning] Redis PUTVER failed for %s: %s", agencyCode, e.getMessage());
        }
    }

    public void evictVersion(String agencyCode) {
        if (agencyCode == null) {
            return;
        }
        try {
            keyCommands.del(buildVersionKey(agencyCode));
            LOG.infof("[VER EVICT] Evicted version key for agencyCode: %s", agencyCode);
        } catch (Exception e) {
            LOG.warnf("[VER Warning] Redis DELVER failed for %s: %s", agencyCode, e.getMessage());
        }
    }
}
