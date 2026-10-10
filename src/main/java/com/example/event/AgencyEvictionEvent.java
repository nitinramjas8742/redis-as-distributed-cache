package com.example.event;

/**
 * Event fired when an Agency is updated or removed from the database.
 * Observed during TransactionPhase.AFTER_SUCCESS to guarantee post-commit eviction.
 */
public record AgencyEvictionEvent(String agencyCode) {
}
