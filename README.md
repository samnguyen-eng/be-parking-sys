# Parking Space Reservation System — Backend

A cloud-based parking reservation system handling 1,000 concurrent users competing for 80 parking spaces at 8 PM daily. Built on GCP with Spring Boot 3, Redis, Cloud SQL PostgreSQL, and Google Pub/Sub.

---

## Project Structure

```
be-parking-sys/
├── parking-api/      # REST API — handles reservations, auth, deposits
├── parking-worker/   # Async worker — confirms/cancels reservations via Pub/Sub
├── parking-outbox/   # (deprecated — removed after architecture simplification)
└── docs/             # Architecture, deployment, and code flow documentation
```

---

## Prerequisites

- Java 21
- Maven 3.9+
- Docker
- Redis (local or cloud)
- PostgreSQL 16

---

## Running Locally

### 1. Start dependencies

```bash
# Redis
docker run -d -p 6379:6379 redis:7

# PostgreSQL
docker run -d -p 5432:5432 \
  -e POSTGRES_DB=parking_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=root \
  postgres:16
```

### 2. parking-api

```bash
cd parking-api
mvn spring-boot:run
```

Runs on **http://localhost:8080**

Default profile: `local` — uses mock KMS and local DB/Redis.

**Key endpoints:**
```
POST /api/auth/register    — register user
POST /api/auth/login       — login, returns JWT token
POST /api/parking/reserve  — reserve a parking space
GET  /api/parking/spaces   — list all 80 spaces with status
POST /api/deposit          — deposit balance
```

### 3. parking-worker

```bash
cd parking-worker
mvn spring-boot:run
```

Runs on **http://localhost:8082**

Worker processes reservation confirmations asynchronously. In local mode it uses Pub/Sub PULL via `PubSubSubscriberConfig`. In production it uses PUSH mode via `/internal/pubsub/push`.

---

## Environment Variables

### parking-api (local)

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | parking_db | Database name |
| `DB_USER` | postgres | DB username |
| `DB_PASSWORD` | root | DB password |
| `REDIS_HOST` | localhost | Redis host |
| `REDIS_PORT` | 6379 | Redis port |
| `JWT_SECRET_KEY` | (default in yml) | JWT signing secret |
| `APP_LOCAL_MODE` | true | Use mock GCP services |
| `APP_PUBSUB_ENABLED` | true | Enable Pub/Sub publish |

### parking-worker (local)

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | parking_db | Database name |
| `DB_USER` | postgres | DB username |
| `DB_PASSWORD` | root | DB password |
| `REDIS_HOST` | localhost | Redis host |
| `REDIS_PORT` | 6379 | Redis port |
| `PUBSUB_PULL_ENABLED` | false | true = PULL mode, false = PUSH mode |

---

## Building Docker Images

```bash
# parking-api
docker build --platform linux/amd64 \
  -f parking-api/Dockerfile \
  -t parking-api:latest .

# parking-worker
docker build --platform linux/amd64 \
  -f parking-worker/Dockerfile \
  -t parking-worker:latest .
```

---

## Running with Docker Compose (local)

```bash
docker compose up -d
```

Services:
- `parking-api` → http://localhost:8080
- `parking-worker` → http://localhost:8082
- `postgres` → localhost:5432
- `redis` → localhost:6379

---

## Production (GCP Cloud Run)

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for full GCP deployment guide.

**Quick deploy:**
```bash
export TAG="release-$(date +%Y%m%d-%H%M%S)"
docker build --platform linux/amd64 -f parking-api/Dockerfile -t ${IMAGE_PREFIX}/parking-api:${TAG} .
docker push ${IMAGE_PREFIX}/parking-api:${TAG}
gcloud run deploy parking-api --image=${IMAGE_PREFIX}/parking-api:${TAG} --region=asia-southeast1
```

---

## Architecture

See [docs/CODE_FLOW.md](docs/CODE_FLOW.md) for detailed code flow documentation.

```
Client → parking-api → Redis (gate) → Cloud SQL (INSERT)
                     → Pub/Sub → parking-worker → Cloud SQL (CONFIRM/CANCEL)
```

---

## Stress Testing

Uses k6 — see the [k6-stress-test](https://github.com/samnguyen-eng/k6-stress-test) repository.

Results: **P95 < 1s, P99 < 2s, 0% real error rate** at 1,500 TPS × 3 minutes.
