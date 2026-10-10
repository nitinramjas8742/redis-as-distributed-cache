package com.example.resource;

import com.example.dto.AgencyDto;
import com.example.dto.BulkQueryRequest;
import com.example.entity.Agency;
import com.example.repository.AgencyQueryRepository;
import com.example.repository.AgencyRepository;
import jakarta.inject.Inject;
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

    /**
     * 1. GET Single Agency: Query path served via Distributed L2 Cache (Redis -> DB fallback)
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
     * 2. POST Bulk Query: Query path served via Distributed L2 Cache (Redis MGET + DB missing lookup)
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
     * 3. POST Create Agency: Command path persisting to DB via AgencyRepository.
     * Newly created entities trigger @PostPersist, NOT @PostUpdate.
     * Result: ZERO wasted Redis eviction calls!
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

        LOG.infof("Agency created: %s. (No cache eviction event fired)", agency.getAgencyCode());
        return Response.created(URI.create("/api/agencies/" + agency.getAgencyCode()))
                .entity(agency)
                .build();
    }

    /**
     * 4. PUT Update Agency: Command path updating DB via AgencyRepository.
     * Modifying managed entity triggers @PostUpdate in Hibernate!
     * Result: Invalidation event fired and processed post-commit!
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

        LOG.infof("Agency updated: %s. (Eviction event will trigger post-commit)", code);
        return Response.ok(agency).build();
    }

    /**
     * 5. DELETE Agency: Command path deleting entity via AgencyRepository.
     * Triggers @PostRemove -> Eviction event fired and processed post-commit!
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
        LOG.infof("Agency deleted: %s. (Eviction event will trigger post-commit)", code);
        return Response.noContent().build();
    }
}
