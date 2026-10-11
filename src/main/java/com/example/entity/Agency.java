package com.example.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.NaturalIdCache;

/**
 * Agency JPA Entity.
 *
 * Hibernate L2 Caching:
 * - @Cacheable: Enables Hibernate Second-Level (L2) Cache for this entity (caches by primary key).
 * - @Cache(READ_WRITE): Transactional cache strategy. Supports read & write with proper locking.
 * - @NaturalId: Marks 'agencyCode' as the business/natural key (used for lookups without id).
 * - @NaturalIdCache: Maintains a separate L2 cache region that maps agencyCode -> id.
 *   This enables byNaturalId() lookups to resolve the id from L2, then retrieve the entity
 *   from L2 entity cache — resulting in ZERO SQL queries on a full double-cache hit.
 */
@Entity
@Table(name = "agency")
@JsonIgnoreProperties(value = {"persistent"}, ignoreUnknown = true)
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@NaturalIdCache
public class Agency extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Business / Natural Key for this entity.
     * Hibernate maintains a dedicated Natural ID cache region mapping:
     *   agencyCode (String) -> id (Long)
     * So lookups by agencyCode NEVER hit the database when both cache regions are warm.
     */
    @NaturalId
    @Column(name = "agency_code", nullable = false, unique = true, length = 50)
    private String agencyCode;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 100)
    private String email;

    @Column(length = 30)
    private String status;

    /**
     * DB optimistic-lock + coherence version. Entity-only, never exposed in DTO.
     * Kept as Long (nullable) so pre-existing rows with NULL version can still
     * be read. Backfill once: UPDATE agency SET version=0 WHERE version IS NULL.
     * New rows start at 0 via Hibernate. Redis marker coerces null -> 0.
     */
    @Version
    @Column(name = "version")
    private Long version;

    public Agency() {
    }

    public Agency(String agencyCode, String name, String email, String status) {
        this.agencyCode = agencyCode;
        this.name = name;
        this.email = email;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAgencyCode() {
        return agencyCode;
    }

    // Note: @NaturalId fields are immutable by default in Hibernate.
    // Setter kept for framework use only; avoid calling after persist.
    public void setAgencyCode(String agencyCode) {
        this.agencyCode = agencyCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }
}
