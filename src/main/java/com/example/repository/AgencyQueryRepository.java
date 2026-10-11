package com.example.repository;

import com.example.entity.Agency;
import com.example.service.AgencyCacheService;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Reads: local Hibernate L2 + shared Redis version marker.
 * Redis holds ONLY version per agencyCode. Equal = trust L2 (0 SQL),
 * else JPA-evict + clear L1 + DB reload + re-warm both.
 */
@ApplicationScoped
public class AgencyQueryRepository implements PanacheRepository<Agency> {

    private static final Logger LOG = Logger.getLogger(AgencyQueryRepository.class);

    @Inject
    EntityManager em;

    @Inject
    AgencyCacheService versionCache;

    public Agency findByAgencyCode(String agencyCode) {
        if (agencyCode == null || agencyCode.isBlank()) {
            return null;
        }

        Long redisVer = versionCache.getVersion(agencyCode);

        Agency result = em.unwrap(org.hibernate.Session.class)
                .byNaturalId(Agency.class)
                .using("agencyCode", agencyCode)
                .load();

        if (result == null) {
            if (redisVer != null) {
                versionCache.evictVersion(agencyCode);
            }
            return null;
        }

        if (redisVer == null) {
            em.getEntityManagerFactory().getCache().evict(Agency.class, result.getId());
            em.clear();
            Agency fresh = em.unwrap(org.hibernate.Session.class)
                    .byNaturalId(Agency.class)
                    .using("agencyCode", agencyCode)
                    .load();
            if (fresh != null) {
                result = fresh;
                versionCache.putVersion(agencyCode, fresh.getVersion());
                LOG.infof("[L2 MISS] No marker for %s, DB refresh v=%s", agencyCode, fresh.getVersion());
            }
        } else if (coerce(result.getVersion()) != coerce(redisVer)) {
            Long staleVer = result.getVersion();
            Object staleId = result.getId();
            em.getEntityManagerFactory().getCache().evict(Agency.class, staleId);
            em.clear();
            Agency fresh = em.unwrap(org.hibernate.Session.class)
                    .byNaturalId(Agency.class)
                    .using("agencyCode", agencyCode)
                    .load();
            if (fresh != null) {
                result = fresh;
                versionCache.putVersion(agencyCode, fresh.getVersion());
                LOG.infof("[L2 STALE] Local v=%s vs Redis v=%s for %s, DB refresh v=%s",
                        staleVer, redisVer, agencyCode, fresh.getVersion());
            }
        } else {
            LOG.debugf("[L2 HIT] %s v=%s", agencyCode, result.getVersion());
        }

        return result;
    }

    private static long coerce(Long v) {
        return v == null ? 0L : v;
    }

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

        return distinctCodes.stream()
                .map(this::findByAgencyCode)
                .filter(Objects::nonNull)
                .toList();
    }
}
