package com.example.resource;

import com.example.dto.AgencyDto;
import com.example.dto.BulkQueryRequest;
import com.example.entity.Agency;
import com.example.repository.AgencyQueryRepository;
import com.example.repository.AgencyRepository;
import com.example.service.AgencyCacheService;
import jakarta.inject.Inject;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

/**
 * REST API separating Queries (via AgencyQueryRepository + Distributed L2 Cache)
 * from Commands (via AgencyRepository + PostgreSQL persistence).
 */
@Path("/api/agencies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AgencyResource {

    private static final Logger LOG = Logger.getLogger(AgencyResource.class);

    @Inject
    AgencyQueryRepository agencyQueryRepository;

    @Inject
    AgencyRepository agencyRepository;

    @Inject
    AgencyCacheService versionCache;

    @Inject
    TransactionSynchronizationRegistry tsr;

    /**
     * Runs eviction only after DB commit succeeds. If no Tx, runs immediately.
     * Pre-evict (inside Tx) + post-commit evict = double-delete, closes the
     * race where a reader re-caches stale data between DEL and commit.
     */
    private void evictVersionAfterCommit(String agencyCode) {
        try {
            tsr.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        versionCache.evictVersion(agencyCode);
                    }
                }
            });
        } catch (Exception e) {
            LOG.warnf("TSR not available for %s, evicting inline: %s", agencyCode, e.getMessage());
            versionCache.evictVersion(agencyCode);
        }
    }

    /**
     * 1. GET Single Agency: near (local L2) + far (Redis version marker) coherence.
     */
    @GET
    @Path("/{code}")
    public Response getByCode(@PathParam("code") String code) {
        Agency agency = agencyQueryRepository.findByAgencyCode(code);
        if (agency == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Agency not found with code: " + code)
                    .build();
        }
        return Response.ok(agency).build();
    }

    /**
     * 2. POST Bulk Query: same coherence check per code via query repository.
     */
    @POST
    @Path("/bulk")
    public Response getBulk(BulkQueryRequest request) {
        if (request == null || request.getAgencyCodes() == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("agencyCodes list is required").build();
        }
        List<Agency> results = agencyQueryRepository.findByAgencyCodes(request.getAgencyCodes());
        return Response.ok(results).build();
    }

    /**
     * 3. POST Create Agency: persists to DB, warms Redis version marker post-commit.
     */
    @POST
    @Transactional
    public Response create(AgencyDto dto) {
        if (dto.getAgencyCode() == null || dto.getName() == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("agencyCode and name are required").build();
        }

        Agency existing = agencyRepository.findByAgencyCode(dto.getAgencyCode());
        if (existing != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Agency already exists with code: " + dto.getAgencyCode())
                    .build();
        }

        Agency agency = new Agency(dto.getAgencyCode(), dto.getName(), dto.getEmail(), dto.getStatus());
        agencyRepository.saveOrUpdate(agency);
        // Warm far marker post-commit so first GET is coherent (no extra REFRESH).
        try {
            agencyRepository.getEntityManager().flush();
            Long v = agency.getVersion();
            String code = agency.getAgencyCode();
            tsr.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        versionCache.putVersion(code, v);
                    }
                }
            });
        } catch (Exception e) {
            LOG.warnf("Create warm failed, first GET will DB-load: %s", e.getMessage());
        }

        LOG.infof("Agency created: %s. (No cache eviction event fired)", agency.getAgencyCode());
        return Response.created(URI.create("/api/agencies/" + agency.getAgencyCode()))
                .entity(agency)
                .build();
    }

    /**
     * 4. PUT Update Agency: updates DB, double-deletes Redis version marker
     * (pre + post-commit) so other pods force DB REFRESH on next GET.
     */
    @PUT
    @Path("/{code}")
    @Transactional
    public Response update(@PathParam("code") String code, AgencyDto dto) {
        Agency agency = agencyRepository.findByAgencyCode(code);
        if (agency == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Agency not found: " + code)
                    .build();
        }

        agency.setName(dto.getName());
        agency.setEmail(dto.getEmail());
        agency.setStatus(dto.getStatus());

        agency = agencyRepository.saveOrUpdate(agency);

        // Double-delete: pre-evict now + post-commit evict. Next GET on any pod
        // sees Redis MISS -> forces DB REFRESH -> re-warms with new version.
        versionCache.evictVersion(code);
        evictVersionAfterCommit(code);

        LOG.infof("Agency updated: %s. (Eviction event will trigger post-commit)", code);
        return Response.ok(agency).build();
    }

    /**
     * 5. DELETE Agency: removes from DB, double-deletes Redis version marker.
     */
    @DELETE
    @Path("/{code}")
    @Transactional
    public Response delete(@PathParam("code") String code) {
        Agency agency = agencyRepository.findByAgencyCode(code);
        if (agency == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        agencyRepository.delete(agency);
        // Delete must also invalidate far marker on all pods.
        versionCache.evictVersion(code);
        evictVersionAfterCommit(code);
        LOG.infof("Agency deleted: %s. (Eviction event will trigger post-commit)", code);
        return Response.noContent().build();
    }
}
