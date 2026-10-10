# Quarkus Agency Distributed Cache (Redis + Panache ORM)

A production-grade reference architecture for a multi-pod Quarkus application on OpenShift, implementing **Solution 1 (JPA Lifecycle Listeners + Transactional Post-Commit Eviction with Redis)**.

---

## 🎯 Architecture Summary

* **Framework:** Quarkus 3.15 + Gradle
* **ORM:** Hibernate ORM with Panache
* **Cache Provider:** Redis via `quarkus-redis-client` (`RedisDataSource`)
* **Environment:** Multi-pod deployment on OpenShift (No cache drift between pods)
* **Creation Optimization:** `@PostPersist` is ignored; **zero wasted Redis eviction calls on INSERTs**.
* **Update Consistency:** `@PostUpdate` fires CDI event; cache is evicted **after DB commit succeeds** (`TransactionPhase.AFTER_SUCCESS`).
* **Batch Optimization:** `findByAgencyCodes` executes Redis `MGET`, fetches only cache misses from DB via `IN (:missingCodes)`, and updates Redis via `MSET`.

---

## 📂 Project Structure

```
quarkus-agency-cache/
├── build.gradle                                    # Gradle dependencies & Quarkus plugin
├── settings.gradle                                 # Project metadata
├── gradle.properties                               # Quarkus BOM & plugin versions
├── README.md                                       # Documentation & OpenShift manifests
├── src/
│   ├── main/
│   │   ├── java/com/example/
│   │   │   ├── dto/
│   │   │   │   ├── AgencyDto.java                  # Redis-serializable DTO
│   │   │   │   └── BulkQueryRequest.java           # Bulk lookup request payload
│   │   │   ├── entity/
│   │   │   │   └── Agency.java                     # Entity with @EntityListeners
│   │   │   ├── event/
│   │   │   │   └── AgencyEvictionEvent.java        # Eviction event record
│   │   │   ├── listener/
│   │   │   │   ├── AgencyEntityListener.java       # JPA @PostUpdate & @PostRemove listener
│   │   │   │   └── AgencyCacheInvalidationListener.java # CDI @Observes(AFTER_SUCCESS)
│   │   │   ├── repository/
│   │   │   │   ├── AgencyQueryRepository.java      # Query model with transparent Redis L2 Cache & batch MGET
│   │   │   │   └── AgencyRepository.java           # Command model for transactional DB persistence
│   │   │   ├── service/
│   │   │   │   └── AgencyCacheService.java         # Dedicated Redis L2 cache provider & fail-open delegate
│   │   │   └── resource/
│   │   │       └── AgencyResource.java             # REST API separating queries and commands
│   │   └── resources/
│   │       └── application.properties              # Config for DB, Redis & TTL
│   └── test/
│       └── java/com/example/
│           └── AgencyCacheResourceTest.java        # Integration tests for all scenarios
```

---

## 🚀 Running the Project

### 1. Prerequisites
* Java 17 or higher
* Gradle (or use `gradle wrapper` to generate wrapper scripts)
* Docker or Podman (if using Quarkus Dev Services for automatic Redis & Postgres containers)

### 2. Start in Quarkus Dev Mode
```bash
gradle quarkusDev
```
*Quarkus Dev Services will automatically start a PostgreSQL and Redis container in the background if Docker/Podman is running.*

### 3. Run the Automated Tests
```bash
gradle test
```

The test suite in [`AgencyCacheResourceTest.java`](src/test/java/com/example/AgencyCacheResourceTest.java) tests and proves:
1. **Creation:** Calling `saveOrUpdate` for a new agency persists to DB and makes **0 calls to Redis eviction**.
2. **Read:** Single GET fetches from DB and populates `agency:code:AGY001` in Redis.
3. **Update:** Calling `saveOrUpdate` on an existing entity triggers `@PostUpdate`, waits for DB commit, and **evicts the key from Redis**.
4. **Batch Read:** Bulk query checks Redis via `MGET`, fetches only missing agencies from DB, and populates Redis via `MSET`.
5. **Delete:** Deletion evicts the key from Redis post-commit.

---

## 🧪 Manual API Testing (cURL)

### 1. Create a New Agency (Zero Redis calls)
```bash
curl -X POST http://localhost:8080/api/agencies \
  -H "Content-Type: application/json" \
  -d '{"agencyCode": "AGY001", "name": "Delta Travel", "email": "info@delta.com", "status": "ACTIVE"}'
```

### 2. Query Agency (Caches in Redis)
```bash
curl http://localhost:8080/api/agencies/AGY001
```

### 3. Update Agency (Evicts from Redis post-commit)
```bash
curl -X PUT http://localhost:8080/api/agencies/AGY001 \
  -H "Content-Type: application/json" \
  -d '{"name": "Delta Global Travel", "email": "info@delta.com", "status": "ACTIVE"}'
```

### 4. Bulk Query (Redis MGET + missing DB fetch + MSET)
```bash
curl -X POST http://localhost:8080/api/agencies/bulk \
  -H "Content-Type: application/json" \
  -d '{"agencyCodes": ["AGY001", "AGY002", "AGY003"]}'
```

---

## ☸️ OpenShift Deployment Guide

In OpenShift, inject Redis connection details via environment variables:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: agency-service
  namespace: your-namespace
spec:
  replicas: 3
  selector:
    matchLabels:
      app: agency-service
  template:
    metadata:
      labels:
        app: agency-service
    spec:
      containers:
        - name: agency-service
          image: image-registry.openshift-image-registry.svc:5000/your-namespace/agency-service:latest
          env:
            - name: REDIS_HOST
              value: "redis-service.your-namespace.svc.cluster.local"
            - name: REDIS_PORT
              value: "6379"
            - name: DB_JDBC_URL
              value: "jdbc:postgresql://postgres-service.your-namespace.svc.cluster.local:5432/agencydb"
            - name: DB_USERNAME
              valueFrom:
                secretKeyRef:
                  name: db-secret
                  key: username
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: db-secret
                  key: password
          ports:
            - containerPort: 8080
          resources:
            limits:
              cpu: "1"
              memory: 512Mi
            requests:
              cpu: 200m
              memory: 256Mi
```
