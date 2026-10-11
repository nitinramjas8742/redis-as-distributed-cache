package com.example.repository;

import com.example.entity.Agency;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * AgencyRepository (Command / Write Model)
 *
 * Dedicated strictly to persistence operations (PostgreSQL / Hibernate ORM).
 * Write-path reads bypass L2 on purpose (strong consistency for mutations).
 * Cross-pod coherence is handled via Redis version markers, see AgencyCacheService.
 */
@ApplicationScoped
public class AgencyRepository implements PanacheRepository<Agency> {

    /**
     * Direct database lookup by agencyCode within current persistence context.
     * Used for existence checks and transactional entity loading during mutations.
     */
    public Agency findByAgencyCode(String agencyCode) {
        if (agencyCode == null) return null;
        return find("agencyCode", agencyCode).firstResult();
    }

    /**
     * Standard saveOrUpdate implementation.
     * If agency is not persistent or id is null, persist it.
     * Otherwise, merge / update it.
     */
    public Agency saveOrUpdate(Agency agency) {
        if (agency.getId() == null) {
            persist(agency);
            return agency;
        } else {
            return getEntityManager().merge(agency);
        }
    }

    /**
     * Deletes agency from DB.
     * Safely handles detached entities.
     */
    @Override
    public void delete(Agency agency) {
        if (agency == null) return;
        Agency managed = isPersistent(agency) ? agency : (agency.getId() != null ? findById(agency.getId()) : null);
        if (managed != null) {
            getEntityManager().remove(managed);
        }
    }
}
