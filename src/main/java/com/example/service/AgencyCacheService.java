package com.example.service;

import com.example.dto.AgencyDto;
import com.example.entity.Agency;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Distributed L2 Cache Provider backed by Redis.
 * Responsible for Redis serialization, TTL management, batch MGET/MSET,
 * and fail-open error handling for Agency entities.
 */
@ApplicationScoped
public class AgencyCacheService {

    private static final Logger LOG = Logger.getLogger(AgencyCacheService.class);
    private static final String KEY_PREFIX = "agency:code:";

    @Inject
    RedisDataSource redisDataSource;

    @ConfigProperty(name = "app.cache.agency.ttl-seconds", defaultValue = "3600")
    long ttlSeconds;

    private ValueCommands<String, AgencyDto> valueCommands;
    private KeyCommands<String> keyCommands;

    @PostConstruct
    void init() {
        this.valueCommands = redisDataSource.value(AgencyDto.class);
        this.keyCommands = redisDataSource.key();
    }

    public String buildKey(String agencyCode) {
        return KEY_PREFIX + agencyCode;
    }

    /**
     * Reads a single entity DTO from Redis L2 cache.
     * Returns null on cache miss or when Redis fails (fail-open).
     */
    public AgencyDto get(String agencyCode) {
        if (agencyCode == null || agencyCode.isBlank()) {
            return null;
        }
        String key = buildKey(agencyCode);
        try {
            AgencyDto cached = valueCommands.get(key);
            if (cached != null) {
                LOG.debugf("[L2 Cache HIT] Found in Redis for key: %s", key);
                return cached;
            }
            LOG.debugf("[L2 Cache MISS] Key not in Redis: %s", key);
        } catch (Exception e) {
            LOG.warnf("[L2 Cache Warning] Redis GET failed for key %s, falling open. Error: %s", key, e.getMessage());
        }
        return null;
    }

    /**
     * Alias for get(agencyCode) for backwards compatibility.
     */
    public AgencyDto findByAgencyCode(String agencyCode) {
        return get(agencyCode);
    }

    /**
     * Writes an entity to Redis L2 cache with configured TTL.
     */
    public void put(Agency entity) {
        if (entity == null || entity.getAgencyCode() == null) {
            return;
        }
        String key = buildKey(entity.getAgencyCode());
        AgencyDto dto = toDto(entity);
        try {
            valueCommands.setex(key, ttlSeconds, dto);
            LOG.debugf("[L2 Cache WRITE] Stored key in Redis: %s with TTL: %d seconds", key, ttlSeconds);
        } catch (Exception e) {
            LOG.warnf("[L2 Cache Warning] Failed to write key %s to Redis: %s", key, e.getMessage());
        }
    }

    /**
     * Batch reads multiple keys from Redis L2 cache via MGET.
     */
    public Map<String, AgencyDto> mget(List<String> agencyCodes) {
        if (agencyCodes == null || agencyCodes.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> keys = agencyCodes.stream().map(this::buildKey).toList();
        try {
            Map<String, AgencyDto> results = valueCommands.mget(keys.toArray(new String[0]));
            return results != null ? results : Collections.emptyMap();
        } catch (Exception e) {
            LOG.warnf("[L2 Cache Warning] Redis MGET failed: %s", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Batch writes multiple entities to Redis L2 cache via MSET and sets TTL.
     */
    public void mput(Map<String, AgencyDto> toCache) {
        if (toCache == null || toCache.isEmpty()) {
            return;
        }
        try {
            valueCommands.mset(toCache);
            for (String key : toCache.keySet()) {
                keyCommands.expire(key, ttlSeconds);
            }
            LOG.debugf("[L2 Cache WRITE] Saved %d items to Redis", toCache.size());
        } catch (Exception e) {
            LOG.warnf("[L2 Cache Warning] Redis MSET failed: %s", e.getMessage());
        }
    }

    /**
     * Evicts a key from Redis L2 cache.
     */
    public void evict(String agencyCode) {
        if (agencyCode == null) return;
        String key = buildKey(agencyCode);
        try {
            keyCommands.del(key);
            LOG.infof("[L2 Cache EVICT] Successfully evicted key from Redis: %s", key);
        } catch (Exception e) {
            LOG.errorf("[L2 Cache Error] Failed to evict key %s from Redis: %s", key, e.getMessage());
        }
    }

    public AgencyDto toDto(Agency entity) {
        if (entity == null) return null;
        return new AgencyDto(
                entity.getId(),
                entity.getAgencyCode(),
                entity.getName(),
                entity.getEmail(),
                entity.getStatus()
        );
    }
}
