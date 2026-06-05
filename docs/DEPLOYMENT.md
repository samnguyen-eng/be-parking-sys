# Parking System — Deployment Guide

> **Project ID:** parking-system-497603
> **Region:** asia-southeast1 (Singapore)
> **Last updated:** June 2026

---

## Architecture

```
Internet → GFE → Cloud Run Load Balancer
                        │
              ┌─────────┴─────────┐
              ▼                   ▼
        parking-fe          parking-api
        (min=1,max=1)       (min=3,max=6)
                                  │
                    ┌─────────────┼────────────┐
                    ▼             ▼            ▼
              Cloud SQL       Redis        Pub/Sub
              (Private IP)  (Private IP)  (PUSH mode)
                                                │
                                                ▼
                                        parking-worker
                                        (min=1,max=2)
```

---

## Services Configuration

| Service | CPU | Memory | Min | Max | Concurrency | Port |
|---------|-----|--------|-----|-----|-------------|------|
| parking-api | 1 | 2Gi | 3 | 6 | 300 | 8080 |
| parking-worker | 1 | 1Gi | 1 | 2 | 80 | 8082 |
| parking-fe | 1 | 512Mi | 1 | 1 | 80 | 3000 |

---

## Infrastructure

| Resource | Spec | Private IP |
|----------|------|-----------|
| Cloud SQL PostgreSQL 16 | db-custom-2-7680 | 10.20.1.2 |
| Redis Memorystore 7.0 | 1GB Standard HA | 10.239.31.4 |
| VPC | parking-vpc | 10.20.0.0/24 |
| VPC Connector | parking-connector | 10.8.0.0/28 |

---

## Environment Variables

### parking-api
```bash
GCP_PROJECT_ID=parking-system-497603
APP_LOCAL_MODE=false
APP_PUBSUB_ENABLED=true
APP_PUBSUB_ORDERING_ENABLED=true
APP_PUBSUB_WARMUP_ENABLED=false
APP_PUBSUB_BOOTSTRAP_ENABLED=false
PUBSUB_TOPIC=reservation.created
KMS_LOCATION=asia-southeast1
KMS_KEY_RING=parking-keyring
KMS_JWT_KEY=parking-jwt-key
KMS_PASSWORD_KEY=parking-password-key
DB_PORT=5432
DB_NAME=parking_db
DB_USER=parking_user
REDIS_PORT=6379
```

### parking-worker
```bash
SPRING_PROFILES_ACTIVE=cloudrun
GCP_PROJECT_ID=parking-system-497603
PUBSUB_SUBSCRIPTION=reservation-created-sub
DB_PORT=5432
DB_NAME=parking_db
DB_USER=parking_user
REDIS_PORT=6379
```

**Secrets** (via Secret Manager):
`DB_HOST`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PASSWORD`, `JWT_SECRET_KEY`

---

## Deploy Commands

### Setup environment variables
```bash
export PROJECT_ID="parking-system-497603"
export REGION="asia-southeast1"
export IMAGE_PREFIX="asia-southeast1-docker.pkg.dev/${PROJECT_ID}/parking-repo"
export TAG="release-$(date +%Y%m%d-%H%M%S)"
export SA_API_EMAIL="parking-api-sa@${PROJECT_ID}.iam.gserviceaccount.com"
export SA_WORKER_EMAIL="parking-worker-sa@${PROJECT_ID}.iam.gserviceaccount.com"
export VPC_CONNECTOR="projects/${PROJECT_ID}/locations/${REGION}/connectors/parking-connector"
```

### Build & push parking-api
```bash
docker build --platform linux/amd64 \
  -f parking-api/Dockerfile \
  -t "${IMAGE_PREFIX}/parking-api:${TAG}" . && \
docker push "${IMAGE_PREFIX}/parking-api:${TAG}"
```

### Deploy parking-api
```bash
gcloud run deploy parking-api \
  --image="${IMAGE_PREFIX}/parking-api:${TAG}" \
  --region="${REGION}" \
  --project="${PROJECT_ID}" \
  --platform=managed \
  --service-account="${SA_API_EMAIL}" \
  --vpc-connector="${VPC_CONNECTOR}" \
  --vpc-egress=private-ranges-only \
  --allow-unauthenticated \
  --port=8080 \
  --min-instances=3 \
  --max-instances=6 \
  --cpu=1 \
  --memory=2Gi \
  --concurrency=300 \
  --timeout=30 \
  --execution-environment=gen2 \
  --no-cpu-throttling \
  --cpu-boost \
  --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID},APP_LOCAL_MODE=false,APP_PUBSUB_ENABLED=true,APP_PUBSUB_ORDERING_ENABLED=true,APP_PUBSUB_WARMUP_ENABLED=false,APP_PUBSUB_BOOTSTRAP_ENABLED=false,PUBSUB_TOPIC=reservation.created,KMS_LOCATION=${REGION},KMS_KEY_RING=parking-keyring,KMS_JWT_KEY=parking-jwt-key,KMS_PASSWORD_KEY=parking-password-key,DB_PORT=5432,DB_NAME=parking_db,DB_USER=parking_user,REDIS_PORT=6379" \
  --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest,REDIS_HOST=redis-host:latest,REDIS_PASSWORD=redis-password:latest"
```

### Build & push parking-worker
```bash
docker build --platform linux/amd64 \
  -f parking-worker/Dockerfile \
  -t "${IMAGE_PREFIX}/parking-worker:${TAG}" . && \
docker push "${IMAGE_PREFIX}/parking-worker:${TAG}"
```

### Deploy parking-worker
```bash
gcloud run deploy parking-worker \
  --image="${IMAGE_PREFIX}/parking-worker:${TAG}" \
  --region="${REGION}" \
  --project="${PROJECT_ID}" \
  --platform=managed \
  --service-account="${SA_WORKER_EMAIL}" \
  --vpc-connector="${VPC_CONNECTOR}" \
  --vpc-egress=all-traffic \
  --no-allow-unauthenticated \
  --port=8082 \
  --min-instances=1 \
  --max-instances=2 \
  --cpu=1 \
  --memory=1Gi \
  --concurrency=80 \
  --timeout=100 \
  --no-cpu-throttling \
  --execution-environment=gen2 \
  --set-env-vars="SPRING_PROFILES_ACTIVE=cloudrun,GCP_PROJECT_ID=${PROJECT_ID},PUBSUB_SUBSCRIPTION=reservation-created-sub,DB_PORT=5432,DB_NAME=parking_db,DB_USER=parking_user,REDIS_PORT=6379" \
  --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest,REDIS_HOST=redis-host:latest,REDIS_PASSWORD=redis-password:latest"
```

---

## Pub/Sub Configuration

```bash
# Create subscription with PUSH mode and ordering
gcloud pubsub subscriptions create reservation-created-sub \
  --topic=reservation.created \
  --enable-message-ordering \
  --ack-deadline=60 \
  --push-endpoint=https://parking-worker-763182684658.asia-southeast1.run.app/internal/pubsub/push \
  --push-auth-service-account=parking-worker-sa@${PROJECT_ID}.iam.gserviceaccount.com \
  --project=${PROJECT_ID}

# Create Pub/Sub service identity
gcloud beta services identity create \
  --service=pubsub.googleapis.com \
  --project=${PROJECT_ID}

# Grant Pub/Sub SA permission to create tokens
PROJECT_NUMBER=$(gcloud projects describe ${PROJECT_ID} --format="value(projectNumber)")
gcloud iam service-accounts add-iam-policy-binding \
  parking-worker-sa@${PROJECT_ID}.iam.gserviceaccount.com \
  --member="serviceAccount:service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountTokenCreator" \
  --project=${PROJECT_ID}

# Grant worker SA run.invoker permission
gcloud run services add-iam-policy-binding parking-worker \
  --region=${REGION} \
  --member="serviceAccount:parking-worker-sa@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/run.invoker" \
  --project=${PROJECT_ID}
```

---

## Connection Pool Summary

| Service | Pool/instance | Max instances | Total DB connections |
|---------|--------------|---------------|---------------------|
| parking-api | 10 | 6 | 60 |
| parking-worker | 30 | 2 | 60 |
| **Total** | | | **120 / 150** |

---

## Cost Saving — Stop/Start

### Stop (before leaving)
```bash
# Stop Cloud SQL
gcloud sql instances patch parking-db \
  --activation-policy=NEVER \
  --project=${PROJECT_ID}

# Stop bastion VM
gcloud compute instances stop bastion \
  --zone=asia-southeast1-a \
  --project=${PROJECT_ID}

# Scale Cloud Run to 0 (after DB is running)
gcloud run services update parking-api --min-instances=0 --region=${REGION} --project=${PROJECT_ID}
gcloud run services update parking-worker --min-instances=0 --region=${REGION} --project=${PROJECT_ID}
```

### Start (next day)
```bash
# Start Cloud SQL first
gcloud sql instances patch parking-db \
  --activation-policy=ALWAYS \
  --project=${PROJECT_ID}

# Wait ~2 minutes, then scale up
gcloud run services update parking-api --min-instances=3 --region=${REGION} --project=${PROJECT_ID}
gcloud run services update parking-worker --min-instances=1 --region=${REGION} --project=${PROJECT_ID}

# Start bastion (for stress testing)
gcloud compute instances start bastion \
  --zone=asia-southeast1-a \
  --project=${PROJECT_ID}
```

---

## Monitoring & Debugging

### Check service health
```bash
curl https://parking-api-763182684658.asia-southeast1.run.app/actuator/health
```

### View logs
```bash
# API errors
gcloud logging read \
  'resource.type="cloud_run_revision" resource.labels.service_name="parking-api" severity>=ERROR' \
  --limit=20 --format="table(timestamp,textPayload)"

# Worker processing
gcloud logging read \
  'resource.type="cloud_run_revision" resource.labels.service_name="parking-worker"' \
  --limit=20 --format="table(timestamp,textPayload)" --freshness=10m

# Pub/Sub push status
gcloud logging read \
  'resource.type="cloud_run_revision" resource.labels.service_name="parking-worker" httpRequest.requestUrl=~"pubsub/push"' \
  --limit=10 --format="table(timestamp,httpRequest.status)"
```

### Check Cloud SQL connections
```bash
gcloud sql instances describe parking-db \
  --project=${PROJECT_ID} \
  --format="value(settings.tier,settings.databaseFlags)"
```

---

## Stress Testing

See [STRESS_TEST.md](STRESS_TEST.md) for k6 setup and execution guide.

**Quick run (from bastion):**
```bash
gcloud compute ssh bastion --zone=asia-southeast1-a --project=${PROJECT_ID}
cd ~/k6/k6
./run-stress-1500.sh
```

**GitHub Actions:**
Trigger workflow at `https://github.com/samnguyen-eng/k6-stress-test/actions`
