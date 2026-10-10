package com.example.repository;

import com.example.dto.AgencyDto;
import com.example.entity.Agency;
import com.example.service.AgencyCacheService;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AgencyQueryRepository (Read / Query Model)
 *
 * Implements transparent Distributed L2 Caching:
 * - Read operations query Redis (Distributed L2 Cache) first.
 * - Cache misses fall back to PostgreSQL and populate Redis L2 with configured TTL.
 * - Supports single and batch lookup (Redis MGET + batch DB lookup + Redis MSET).
 */
@ApplicationScoped
public class AgencyQueryRepository implements PanacheRepository<Agency> {

    private static final Logger LOG = Logger.getLogger(AgencyQueryRepository.class);

    @Inject
    AgencyCacheService l2Cache;

    /**
     * Finds single agency by agencyCode using Distributed L2 Cache.
     * 1. Check Redis L2
     * 2. On miss, query PostgreSQL and populate Redis L2
     */
    public Agency findByAgencyCode(String agencyCode) {
        if (agencyCode == null || agencyCode.isBlank()) {
            return null;
        }

        // 1. Check Redis L2 Cache
        AgencyDto cached = l2Cache.get(agencyCode);
        if (cached != null) {
            LOG.debugf("[L2 Cache HIT] Serving from Redis for agencyCode: %s", agencyCode);
            return Agency.fromDto(cached);
        }

        // 2. L2 Cache Miss -> Query PostgreSQL
        LOG.debugf("[L2 Cache MISS] Querying DB for agencyCode: %s", agencyCode);
        Agency entity = find("agencyCode", agencyCode).firstResult();

        // 3. Populate Redis L2 Cache
        if (entity != null) {
            l2Cache.put(entity);
        }

        return entity;
    }

    /**
     * Batch finds agencies by list of agencyCodes using Distributed L2 Cache:
     * 1. Redis batch MGET
     * 2. DB lookup only for missing keys
     * 3. Redis batch MSET for missing keys with TTL
     */
    public List<Agency> findByAgencyCodes(List<String> agencyCodes) {
        if (agencyCodes == null || agencyCodes.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> distinctCodes = agencyCodes.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (distinctCodes.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Agency> foundMap = new HashMap<>();
        List<String> missingCodes = new ArrayList<>();

        // Step 1: Query Redis L2 Cache via MGET
        Map<String, AgencyDto> cachedMap = l2Cache.mget(distinctCodes);
        for (String code : distinctCodes) {
            String key = l2Cache.buildKey(code);
            AgencyDto dto = cachedMap.get(key);
            if (dto != null) {
                foundMap.put(code, Agency.fromDto(dto));
            } else {
                missingCodes.add(code);
            }
        }

        LOG.debugf("[L2 Batch Cache] Found %d in L2, %d missing in L2", foundMap.size(), missingCodes.size());

        // Step 2: Query DB only for L2 misses
        if (!missingCodes.isEmpty()) {
            List<Agency> dbEntities = list("agencyCode in ?1", missingCodes);
            Map<String, AgencyDto> toCache = new HashMap<>();

            for (Agency entity : dbEntities) {
                foundMap.put(entity.getAgencyCode(), entity);
                toCache.put(l2Cache.buildKey(entity.getAgencyCode()), l2Cache.toDto(entity));
            }

            // Step 3: Populate L2 cache for missing entries via MSET
            if (!toCache.isEmpty()) {
                l2Cache.mput(toCache);
            }
        }

        // Return ordered result list matching input list
        return agencyCodes.stream()
                .map(foundMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
