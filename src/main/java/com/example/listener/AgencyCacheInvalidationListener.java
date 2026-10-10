package com.example.listener;

import com.example.event.AgencyEvictionEvent;
import com.example.service.AgencyCacheService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Transactional CDI Event Observer.
 *
 * It listens for AgencyEvictionEvent fired by AgencyEntityListener and executes
 * cache eviction ONLY after the database transaction successfully commits.
 */
@ApplicationScoped
public class AgencyCacheInvalidationListener {

    private static final Logger LOG = Logger.getLogger(AgencyCacheInvalidationListener.class);

    @Inject
    AgencyCacheService agencyCacheService;

    public void onAgencyEviction(@Observes(during = TransactionPhase.AFTER_SUCCESS) AgencyEvictionEvent event) {
        LOG.debugf("[Transactional Observer] DB transaction committed. Evicting Redis cache for agency: %s", event.agencyCode());
        agencyCacheService.evict(event.agencyCode());
    }
}
