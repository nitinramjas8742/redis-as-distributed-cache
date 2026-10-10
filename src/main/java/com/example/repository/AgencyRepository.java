package com.example.repository;

import com.example.entity.Agency;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.List;

/**
 * AgencyRepository (Command / Write Model)
 *
 * Dedicated strictly to persistence operations (PostgreSQL / Hibernate ORM).
 * Contains zero caching logic.
 * Mutations trigger @PostUpdate and @PostRemove JPA entity listeners for
 * post-commit L2 cache invalidation.
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
     * Direct database batch lookup by agencyCodes.
     */
    public List<Agency> findByAgencyCodes(List<String> agencyCodes) {
        if (agencyCodes == null || agencyCodes.isEmpty()) {
            return Collections.emptyList();
        }
        return list("agencyCode in ?1", agencyCodes);
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
