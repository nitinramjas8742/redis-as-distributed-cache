package com.example.listener;

import com.example.entity.Agency;
import com.example.event.AgencyEvictionEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.jboss.logging.Logger;

/**
 * JPA Entity Listener attached to the Agency entity.
 *
 * It intercepts entity updates and deletions to fire an AgencyEvictionEvent.
 * Notice: We intentionally do NOT define @PostPersist, ensuring that brand new
 * agency creations never waste calls on Redis eviction!
 */
@ApplicationScoped
public class AgencyEntityListener {

    private static final Logger LOG = Logger.getLogger(AgencyEntityListener.class);

    @Inject
    Event<AgencyEvictionEvent> evictionEvent;

    /**
     * Triggered ONLY when an existing entity is updated in the database.
     */
    @PostUpdate
    public void onPostUpdate(Agency agency) {
        if (agency != null && agency.getAgencyCode() != null) {
            LOG.debugf("[JPA Listener] @PostUpdate detected for agencyCode: %s. Firing eviction event.", agency.getAgencyCode());
            evictionEvent.fire(new AgencyEvictionEvent(agency.getAgencyCode()));
        }
    }

    /**
     * Triggered ONLY when an entity is deleted from the database.
     */
    @PostRemove
    public void onPostRemove(Agency agency) {
        if (agency != null && agency.getAgencyCode() != null) {
            LOG.debugf("[JPA Listener] @PostRemove detected for agencyCode: %s. Firing eviction event.", agency.getAgencyCode());
            evictionEvent.fire(new AgencyEvictionEvent(agency.getAgencyCode()));
        }
    }

    // @PostPersist is intentionally omitted. Creating a new agency triggers zero cache evictions!
}
