package com.example;

import com.example.dto.AgencyDto;
import com.example.dto.BulkQueryRequest;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AgencyCacheResourceTest {

    @Inject
    RedisDataSource redisDataSource;

    private static final String CODE_1 = "AGY001";
    private static final String CODE_2 = "AGY002";
    private static final String REDIS_KEY_1 = "agency:code:" + CODE_1;

    @Test
    @Order(1)
    public void testCreateAgencyDoesNotPopulateOrEvictCache() {
        ValueCommands<String, AgencyDto> valueCommands = redisDataSource.value(AgencyDto.class);

        // Before creation, Redis key is null
        Assertions.assertNull(valueCommands.get(REDIS_KEY_1));

        AgencyDto newAgency = new AgencyDto(null, CODE_1, "Apollo Travel", "apollo@travel.com", "ACTIVE");

        // 1. Create agency via API (uses saveOrUpdate)
        given()
                .contentType(ContentType.JSON)
                .body(newAgency)
                .when()
                .post("/api/agencies")
                .then()
                .statusCode(201)
                .body("agencyCode", equalTo(CODE_1))
                .body("name", equalTo("Apollo Travel"));

        // 2. Verify that creation did NOT write to or wastefully call eviction on Redis
        // Key should still be null because creation doesn't populate cache until read
        Assertions.assertNull(valueCommands.get(REDIS_KEY_1), "Key should not exist in cache right after creation");
    }

    @Test
    @Order(2)
    public void testGetAgencyPopulatesRedisCache() {
        ValueCommands<String, AgencyDto> valueCommands = redisDataSource.value(AgencyDto.class);

        // 1. GET Agency by code (triggers Cache MISS -> DB read -> Cache WRITE)
        given()
                .when()
                .get("/api/agencies/" + CODE_1)
                .then()
                .statusCode(200)
                .body("agencyCode", equalTo(CODE_1))
                .body("name", equalTo("Apollo Travel"));

        // 2. Verify that Redis is NOW populated!
        AgencyDto cached = valueCommands.get(REDIS_KEY_1);
        Assertions.assertNotNull(cached, "Entity must now be cached in Redis");
        Assertions.assertEquals("Apollo Travel", cached.getName());
    }

    @Test
    @Order(3)
    public void testUpdateAgencyEvictsRedisCachePostCommit() {
        ValueCommands<String, AgencyDto> valueCommands = redisDataSource.value(AgencyDto.class);

        // Precondition: key is present in Redis
        Assertions.assertNotNull(valueCommands.get(REDIS_KEY_1));

        // 1. Update agency name to "Apollo Global Travel"
        AgencyDto updateDto = new AgencyDto(null, CODE_1, "Apollo Global Travel", "apollo@travel.com", "ACTIVE");

        given()
                .contentType(ContentType.JSON)
                .body(updateDto)
                .when()
                .put("/api/agencies/" + CODE_1)
                .then()
                .statusCode(200)
                .body("name", equalTo("Apollo Global Travel"));

        // 2. Verify that JPA @PostUpdate and TransactionPhase.AFTER_SUCCESS evicted the Redis key!
        AgencyDto afterUpdateCached = valueCommands.get(REDIS_KEY_1);
        Assertions.assertNull(afterUpdateCached, "Redis key must be evicted post-commit upon update!");

        // 3. Next GET reads updated value from DB and re-populates cache
        given()
                .when()
                .get("/api/agencies/" + CODE_1)
                .then()
                .statusCode(200)
                .body("name", equalTo("Apollo Global Travel"));

        // Now cache has the updated value
        AgencyDto refreshedCached = valueCommands.get(REDIS_KEY_1);
        Assertions.assertNotNull(refreshedCached);
        Assertions.assertEquals("Apollo Global Travel", refreshedCached.getName());
    }

    @Test
    @Order(4)
    public void testBulkQueryBatchCaching() {
        // Create second agency
        AgencyDto agency2 = new AgencyDto(null, CODE_2, "Zeus Voyages", "zeus@voyages.com", "ACTIVE");
        given()
                .contentType(ContentType.JSON)
                .body(agency2)
                .when()
                .post("/api/agencies")
                .then()
                .statusCode(201);

        // Bulk query with AGY001 (already cached) and AGY002 (not yet cached)
        BulkQueryRequest request = new BulkQueryRequest(List.of(CODE_1, CODE_2));

        given()
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/agencies/bulk")
                .then()
                .statusCode(200)
                .body("$", hasSize(2));

        // Both keys should now be in Redis
        ValueCommands<String, AgencyDto> valueCommands = redisDataSource.value(AgencyDto.class);
        Assertions.assertNotNull(valueCommands.get("agency:code:" + CODE_1));
        Assertions.assertNotNull(valueCommands.get("agency:code:" + CODE_2));
    }

    @Test
    @Order(5)
    public void testDeleteAgencyEvictsRedisCache() {
        ValueCommands<String, AgencyDto> valueCommands = redisDataSource.value(AgencyDto.class);

        // Delete agency
        given()
                .when()
                .delete("/api/agencies/" + CODE_1)
                .then()
                .statusCode(204);

        // Verify key is evicted from Redis
        Assertions.assertNull(valueCommands.get(REDIS_KEY_1), "Redis key must be deleted when entity is removed");
    }
}
