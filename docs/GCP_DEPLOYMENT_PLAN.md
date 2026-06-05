# GCP Deployment Plan — be-parking-sys

> **Mục tiêu:** Deploy 3 Cloud Run services (`parking-api`, `parking-outbox`, `parking-worker`) cùng toàn bộ infrastructure GCP lên môi trường production, đảm bảo chịu tải 1.500 TPS trong 3 phút liên tục.

---

## Tổng quan kiến trúc sau khi deploy

```
Internet → Cloud Run: parking-api (autoscale, port 8080)
               │
               ├── Cloud SQL PostgreSQL (Private IP, SSL only)
               ├── Cloud Memorystore Redis (TLS + AUTH)
               ├── Cloud KMS (AES-256 + RSA-2048)
               └── Secret Manager
               │
               │ [writes outbox → DB]
               ▼
Cloud Run: parking-outbox (min=1 max=1, port 8081)
               │ [poll DB → publish]
               ▼
Cloud Pub/Sub topic: reservation.created
               │ [push subscription → HTTP POST]
               ▼
Cloud Run: parking-worker (min=1 max=5, port 8082)
               └── Cloud SQL + Redis
```

---

## Biến môi trường cần thiết (set trước khi chạy)

```bash
export PROJECT_ID="your-gcp-project-id"          # GCP Project ID
export REGION="asia-southeast1"                   # Region (Singapore)
export ZONE="asia-southeast1-a"
export DB_INSTANCE="parking-db"
export DB_NAME="parking_db"
export DB_USER="parking_user"
export REDIS_INSTANCE="parking-redis"
export VPC_NETWORK="parking-vpc"
export VPC_SUBNET="parking-subnet"
export AR_REPO="parking-repo"                     # Artifact Registry repo name
export KMS_KEYRING="parking-keyring"
export KMS_JWT_KEY="parking-jwt-key"
export KMS_PASSWORD_KEY="parking-password-key"
export PUBSUB_TOPIC="reservation.created"
export PUBSUB_SUBSCRIPTION="reservation-worker-sub"
export SA_API="parking-api-sa"
export SA_OUTBOX="parking-outbox-sa"
export SA_WORKER="parking-worker-sa"
```

---

## Bước 1 — Prerequisites & GCP Setup

### 1.1 Xác nhận project & enable APIs

```bash
gcloud config set project $PROJECT_ID
gcloud config set compute/region $REGION

# Enable tất cả APIs cần thiết
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  redis.googleapis.com \
  cloudkms.googleapis.com \
  secretmanager.googleapis.com \
  pubsub.googleapis.com \
  artifactregistry.googleapis.com \
  compute.googleapis.com \
  vpcaccess.googleapis.com \
  servicenetworking.googleapis.com \
  iam.googleapis.com
```

### 1.2 Tạo Artifact Registry repository

```bash
gcloud artifacts repositories create $AR_REPO \
  --repository-format=docker \
  --location=$REGION \
  --description="Parking System Docker images"

# Configure Docker auth
gcloud auth configure-docker ${REGION}-docker.pkg.dev
```

---

## Bước 2 — Network (VPC + Private Services)

> Cloud SQL và Memorystore dùng Private IP — bắt buộc phải có VPC riêng và private service connection.

### 2.1 Tạo VPC và Subnet

```bash
# Tạo VPC
gcloud compute networks create $VPC_NETWORK \
  --subnet-mode=custom \
  --bgp-routing-mode=regional

# Tạo subnet cho Cloud Run (Serverless VPC Connector cần /28 trở lên)
gcloud compute networks subnets create $VPC_SUBNET \
  --network=$VPC_NETWORK \
  --region=$REGION \
  --range=10.20.0.0/24
```

### 2.2 Cấp phát IP cho Private Services (Cloud SQL & Memorystore)

```bash
gcloud compute addresses create google-managed-services-$VPC_NETWORK \
  --global \
  --purpose=VPC_PEERING \
  --addresses=10.20.1.0 \
  --prefix-length=24 \
  --network=$VPC_NETWORK

gcloud services vpc-peerings connect \
  --service=servicenetworking.googleapis.com \
  --ranges=google-managed-services-$VPC_NETWORK \
  --network=$VPC_NETWORK \
  --project=$PROJECT_ID
```

### 2.3 Tạo Serverless VPC Access Connector

> Cloud Run cần connector này để reach Private IP của Cloud SQL và Memorystore.

```bash
gcloud compute networks vpc-access connectors create parking-connector \
  --region=$REGION \
  --network=$VPC_NETWORK \
  --range=10.20.2.0/28 \
  --min-instances=2 \
  --max-instances=10 \
  --machine-type=e2-micro

# Verify
gcloud compute networks vpc-access connectors describe parking-connector \
  --region=$REGION
```

---

## Bước 3 — Cloud SQL (PostgreSQL)

### 3.1 Tạo Cloud SQL instance

```bash
gcloud sql instances create $DB_INSTANCE \
  --database-version=POSTGRES_16 \
  --region=$REGION \
  --tier=db-custom-4-15360 \
  --availability-type=REGIONAL \
  --no-assign-ip \
  --network=projects/$PROJECT_ID/global/networks/$VPC_NETWORK \
  --require-ssl \
  --storage-type=SSD \
  --storage-size=50GB \
  --storage-auto-increase \
  --database-flags=max_connections=300,work_mem=16MB \
  --backup-start-time=03:00 \
  --enable-bin-log
```

> **Lưu ý tier:** `db-custom-4-15360` = 4 vCPU, 15GB RAM. Điều chỉnh tuỳ budget.
> `--no-assign-ip` + `--network` = Private IP only, không có public IP.

### 3.2 Tạo database và user

```bash
gcloud sql databases create $DB_NAME \
  --instance=$DB_INSTANCE

gcloud sql users create $DB_USER \
  --instance=$DB_INSTANCE \
  --password=$(openssl rand -base64 24)

# Lấy Private IP của Cloud SQL
DB_PRIVATE_IP=$(gcloud sql instances describe $DB_INSTANCE \
  --format='value(ipAddresses[0].ipAddress)')
echo "Cloud SQL Private IP: $DB_PRIVATE_IP"
# → Ghi nhớ giá trị này, dùng ở bước Secret Manager
```

### 3.3 Lưu DB password vào Secret Manager

```bash
DB_PASS=$(openssl rand -base64 24)

echo -n "$DB_PASS" | gcloud secrets create db-password \
  --data-file=- \
  --replication-policy=user-managed \
  --locations=$REGION

gcloud sql users set-password $DB_USER \
  --instance=$DB_INSTANCE \
  --password="$DB_PASS"
```

---

## Bước 4 — Cloud Memorystore (Redis)

### 4.1 Tạo Redis instance

```bash
gcloud redis instances create $REDIS_INSTANCE \
  --size=2 \
  --region=$REGION \
  --redis-version=redis_7_0 \
  --network=projects/$PROJECT_ID/global/networks/$VPC_NETWORK \
  --tier=STANDARD_HA \
  --transit-encryption-mode=SERVER_AUTHENTICATION \
  --auth-enabled

# Lấy Private IP và AUTH string
REDIS_HOST=$(gcloud redis instances describe $REDIS_INSTANCE \
  --region=$REGION \
  --format='value(host)')
REDIS_PASS=$(gcloud redis instances get-auth-string $REDIS_INSTANCE \
  --region=$REGION \
  --format='value(authString)')

echo "Redis Host: $REDIS_HOST"
```

### 4.2 Lưu Redis password vào Secret Manager

```bash
echo -n "$REDIS_PASS" | gcloud secrets create redis-password \
  --data-file=- \
  --replication-policy=user-managed \
  --locations=$REGION
```

---

## Bước 5 — Cloud KMS

### 5.1 Tạo Key Ring

```bash
gcloud kms keyrings create $KMS_KEYRING \
  --location=$REGION
```

### 5.2 Tạo JWT Signing Key (RSA-2048 asymmetric)

```bash
gcloud kms keys create $KMS_JWT_KEY \
  --location=$REGION \
  --keyring=$KMS_KEYRING \
  --purpose=asymmetric-signing \
  --default-algorithm=rsa-sign-pkcs1-2048-sha256 \
  --protection-level=software
```

### 5.3 Tạo Password Encryption Key (AES-256 symmetric)

```bash
gcloud kms keys create $KMS_PASSWORD_KEY \
  --location=$REGION \
  --keyring=$KMS_KEYRING \
  --purpose=encryption \
  --default-algorithm=google-symmetric-encryption \
  --protection-level=software
```

---

## Bước 6 — Secret Manager (tổng hợp)

```bash
# DB Host (Private IP)
echo -n "$DB_PRIVATE_IP" | gcloud secrets create db-host \
  --data-file=- \
  --replication-policy=user-managed \
  --locations=$REGION

# Redis Host
echo -n "$REDIS_HOST" | gcloud secrets create redis-host \
  --data-file=- \
  --replication-policy=user-managed \
  --locations=$REGION

# Kiểm tra tất cả secrets đã tạo
gcloud secrets list
# Expected: db-password, redis-password, db-host, redis-host
```

---

## Bước 7 — Pub/Sub

### 7.1 Tạo Topic

```bash
gcloud pubsub topics create $PUBSUB_TOPIC
```

### 7.2 Tạo Push Subscription (sẽ cập nhật endpoint sau khi deploy worker)

> Tạm thời tạo với placeholder endpoint — sẽ update sau khi có Cloud Run URL của worker.

```bash
# Placeholder — sẽ update ở Bước 10
gcloud pubsub subscriptions create $PUBSUB_SUBSCRIPTION \
  --topic=$PUBSUB_TOPIC \
  --push-endpoint=https://placeholder.run.app/internal/pubsub/push \
  --ack-deadline=60 \
  --min-retry-delay=10s \
  --max-retry-delay=60s \
  --message-retention-duration=1d
```

---

## Bước 8 — Service Accounts & IAM

### 8.1 Tạo Service Account cho từng service

```bash
# parking-api SA
gcloud iam service-accounts create $SA_API \
  --display-name="Parking API Service Account"

# parking-outbox SA
gcloud iam service-accounts create $SA_OUTBOX \
  --display-name="Parking Outbox Service Account"

# parking-worker SA
gcloud iam service-accounts create $SA_WORKER \
  --display-name="Parking Worker Service Account"
```

### 8.2 Gán IAM roles

```bash
SA_API_EMAIL="${SA_API}@${PROJECT_ID}.iam.gserviceaccount.com"
SA_OUTBOX_EMAIL="${SA_OUTBOX}@${PROJECT_ID}.iam.gserviceaccount.com"
SA_WORKER_EMAIL="${SA_WORKER}@${PROJECT_ID}.iam.gserviceaccount.com"

# ===== parking-api: cần Cloud SQL, KMS, Secret Manager, Redis (qua VPC) =====
for role in \
  roles/cloudsql.client \
  roles/cloudkms.cryptoKeyEncrypterDecrypter \
  roles/cloudkms.signerVerifier \
  roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${SA_API_EMAIL}" \
    --role="$role"
done

# ===== parking-outbox: cần Cloud SQL + Pub/Sub publisher =====
for role in \
  roles/cloudsql.client \
  roles/pubsub.publisher \
  roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${SA_OUTBOX_EMAIL}" \
    --role="$role"
done

# ===== parking-worker: cần Cloud SQL, Redis, Pub/Sub subscriber =====
for role in \
  roles/cloudsql.client \
  roles/pubsub.subscriber \
  roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${SA_WORKER_EMAIL}" \
    --role="$role"
done

# ===== Pub/Sub cần quyền invoke worker Cloud Run (để push subscription) =====
PUBSUB_SA="service-$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')@gcp-sa-pubsub.iam.gserviceaccount.com"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${PUBSUB_SA}" \
  --role="roles/iam.serviceAccountTokenCreator"

gcloud run services add-iam-policy-binding parking-worker \
  --member="serviceAccount:${SA_WORKER_EMAIL}" \
  --role="roles/run.invoker" \
  --region=$REGION 2>/dev/null || true
# (Sẽ chạy lại sau khi deploy worker xong ở Bước 10)
```

---

## Bước 9 — Build & Push Docker Images

> Thực hiện từ thư mục gốc `be-parking-sys/` — Dockerfile của mỗi module đã dùng multi-stage build, copy toàn bộ source từ parent.

```bash
cd /path/to/be-parking-sys

IMAGE_PREFIX="${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}"

# ===== Build & Push parking-api =====
docker build \
  -f parking-api/Dockerfile \
  -t ${IMAGE_PREFIX}/parking-api:latest \
  -t ${IMAGE_PREFIX}/parking-api:$(git rev-parse --short HEAD) \
  .

docker push ${IMAGE_PREFIX}/parking-api:latest
docker push ${IMAGE_PREFIX}/parking-api:$(git rev-parse --short HEAD)

# ===== Build & Push parking-outbox =====
docker build \
  -f parking-outbox/Dockerfile \
  -t ${IMAGE_PREFIX}/parking-outbox:latest \
  -t ${IMAGE_PREFIX}/parking-outbox:$(git rev-parse --short HEAD) \
  .

docker push ${IMAGE_PREFIX}/parking-outbox:latest
docker push ${IMAGE_PREFIX}/parking-outbox:$(git rev-parse --short HEAD)

# ===== Build & Push parking-worker =====
docker build \
  -f parking-worker/Dockerfile \
  -t ${IMAGE_PREFIX}/parking-worker:latest \
  -t ${IMAGE_PREFIX}/parking-worker:$(git rev-parse --short HEAD) \
  .

docker push ${IMAGE_PREFIX}/parking-worker:latest
docker push ${IMAGE_PREFIX}/parking-worker:$(git rev-parse --short HEAD)
```

> **Tip:** Dùng Cloud Build thay docker local nếu build chậm:
> ```bash
> gcloud builds submit --config cloudbuild.yaml .
> ```

---

## Bước 10 — Deploy Cloud Run Services

### Biến chung cho deploy

```bash
IMAGE_PREFIX="${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}"
VPC_CONNECTOR="projects/${PROJECT_ID}/locations/${REGION}/connectors/parking-connector"
```

### 10.1 Deploy `parking-worker` (deploy trước để lấy URL cho Pub/Sub)

> **Scaling config:** min=2 · idle=2 · max=4
> `--min-instances=2` đảm bảo luôn có 2 instance khởi động sẵn nhận push từ Pub/Sub, không bị cold start khi burst traffic lúc 20:00. `--max-instances=4` giới hạn tài nguyên phía worker do workload đến qua queue (không cần scale nhiều như api).

```bash
gcloud run deploy parking-worker \
  --image=${IMAGE_PREFIX}/parking-worker:latest \
  --region=$REGION \
  --platform=managed \
  --service-account="${SA_WORKER_EMAIL}" \
  --vpc-connector=$VPC_CONNECTOR \
  --vpc-egress=all-traffic \
  --no-allow-unauthenticated \
  --port=8082 \
  --min-instances=2 \
  --max-instances=4 \
  --cpu=1 \
  --memory=1Gi \
  --concurrency=80 \
  --timeout=300 \
  --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID}" \
  --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest,REDIS_HOST=redis-host:latest,REDIS_PASSWORD=redis-password:latest" \
  --set-env-vars="DB_PORT=5432,DB_NAME=${DB_NAME},DB_USER=${DB_USER},REDIS_PORT=6379"
```

### 10.2 Cập nhật Pub/Sub push endpoint với URL thực của worker

```bash
WORKER_URL=$(gcloud run services describe parking-worker \
  --region=$REGION \
  --format='value(status.url)')

echo "Worker URL: $WORKER_URL"

gcloud pubsub subscriptions modify-push-config $PUBSUB_SUBSCRIPTION \
  --push-endpoint="${WORKER_URL}/internal/pubsub/push" \
  --push-auth-service-account="${SA_WORKER_EMAIL}"

# Cấp quyền invoke cho Pub/Sub SA
gcloud run services add-iam-policy-binding parking-worker \
  --member="serviceAccount:${PUBSUB_SA}" \
  --role="roles/run.invoker" \
  --region=$REGION
```

### 10.3 Deploy `parking-outbox` (always-on, fixed min=max=1)

> **Scaling config:** min=1 · max=1 (fixed single instance)
> Outbox poller chạy scheduler nội bộ mỗi 500ms — không cần scale, không cần idle warm-up. Singleton tránh nhiều instance poll cùng 1 batch outbox.

```bash
gcloud run deploy parking-outbox \
  --image=${IMAGE_PREFIX}/parking-outbox:latest \
  --region=$REGION \
  --platform=managed \
  --service-account="${SA_OUTBOX_EMAIL}" \
  --vpc-connector=$VPC_CONNECTOR \
  --vpc-egress=all-traffic \
  --no-allow-unauthenticated \
  --port=8081 \
  --min-instances=1 \
  --max-instances=1 \
  --cpu=0.5 \
  --memory=512Mi \
  --concurrency=1 \
  --timeout=60 \
  --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID},PUBSUB_TOPIC=${PUBSUB_TOPIC}" \
  --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest" \
  --set-env-vars="DB_PORT=5432,DB_NAME=${DB_NAME},DB_USER=${DB_USER}"
```

### 10.4 Deploy `parking-api` (public-facing, autoscale)

> **Scaling config:** min=2 · idle=2 · max=5
> `--min-instances=2` giữ 2 instance luôn warm — quan trọng để không bị cold start đúng lúc 20:00 khi 1.000 user đổ vào cùng lúc. `--max-instances=5` đủ để đáp ứng 1.500 TPS với `--concurrency=500` (5 × 500 = 2.500 concurrent slots).

```bash
gcloud run deploy parking-api \
  --image=${IMAGE_PREFIX}/parking-api:latest \
  --region=$REGION \
  --platform=managed \
  --service-account="${SA_API_EMAIL}" \
  --vpc-connector=$VPC_CONNECTOR \
  --vpc-egress=all-traffic \
  --allow-unauthenticated \
  --port=8080 \
  --min-instances=2 \
  --max-instances=5 \
  --cpu=2 \
  --memory=2Gi \
  --concurrency=500 \
  --timeout=30 \
  --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID}" \
  --set-env-vars="KMS_LOCATION=${REGION},KMS_KEY_RING=${KMS_KEYRING},KMS_JWT_KEY=${KMS_JWT_KEY},KMS_PASSWORD_KEY=${KMS_PASSWORD_KEY}" \
  --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest,REDIS_HOST=redis-host:latest,REDIS_PASSWORD=redis-password:latest" \
  --set-env-vars="DB_PORT=5432,DB_NAME=${DB_NAME},DB_USER=${DB_USER},REDIS_PORT=6379" \
  --cpu-boost
```

> **`--cpu-boost`**: dùng full 2 CPU trong giai đoạn startup → giảm cold start latency.
> **`--concurrency=500`**: Java 21 virtual threads cho phép xử lý nhiều concurrent request trên 1 instance mà không block OS thread.

---

## Bước 11 — Verification

### 11.1 Kiểm tra health của từng service

```bash
API_URL=$(gcloud run services describe parking-api \
  --region=$REGION --format='value(status.url)')

# Health check parking-api
curl -s "${API_URL}/actuator/health" | jq .

# Health check parking-outbox (internal, cần token)
OUTBOX_URL=$(gcloud run services describe parking-outbox \
  --region=$REGION --format='value(status.url)')
curl -s -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
  "${OUTBOX_URL}/actuator/health" | jq .

# Health check parking-worker (internal)
WORKER_URL=$(gcloud run services describe parking-worker \
  --region=$REGION --format='value(status.url)')
curl -s -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
  "${WORKER_URL}/actuator/health" | jq .
```

### 11.2 Kiểm tra end-to-end flow

```bash
# 1. Đăng ký tài khoản
curl -X POST "${API_URL}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"Test@1234","licensePlate":"51A-12345"}'

# 2. Đăng nhập lấy JWT
TOKEN=$(curl -s -X POST "${API_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"Test@1234"}' \
  | jq -r '.token')
echo "JWT: $TOKEN"

# 3. Nạp tiền
curl -X POST "${API_URL}/api/deposit" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount":50}'

# 4. Xem trạng thái bãi xe
curl -s "${API_URL}/api/parking/spaces" | jq .
```

### 11.3 Kiểm tra Pub/Sub flow

```bash
# Xem messages trong topic
gcloud pubsub subscriptions pull $PUBSUB_SUBSCRIPTION \
  --max-messages=5 \
  --auto-ack
```

---

## Bước 12 — Stress Test (K6)

> Yêu cầu: **1.500 TPS trong 3 phút liên tục**.

### 12.1 Cài K6

```bash
# macOS
brew install k6

# Ubuntu/Debian
sudo gpg -k
sudo gpg --no-default-keyring \
  --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 \
  --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6
```

### 12.2 Script stress test

Tạo file `stress-test.js`:

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

const API_URL = __ENV.API_URL;
const TOKEN   = __ENV.TOKEN;   // pre-generated JWT token

export const options = {
  stages: [
    { duration: '30s', target: 500  },  // ramp-up
    { duration: '3m',  target: 1500 },  // steady 1500 TPS
    { duration: '30s', target: 0    },  // ramp-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],   // 95% requests < 500ms
    http_req_failed:   ['rate<0.01'],   // error rate < 1%
  },
};

// Parking spaces read (heaviest read endpoint)
export default function () {
  const res = http.get(`${API_URL}/api/parking/spaces`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
  sleep(0.001); // ~1ms think time
}
```

### 12.3 Chạy stress test

```bash
export API_URL="https://parking-api-xxxx-as.a.run.app"
export TOKEN="eyJhbGciOiJS..."   # JWT từ bước 11.2

k6 run \
  -e API_URL=$API_URL \
  -e TOKEN=$TOKEN \
  --out json=stress-results.json \
  stress-test.js
```

### 12.4 Theo dõi trong lúc test

```bash
# Cloud Run metrics
gcloud monitoring metrics list \
  --filter="metric.type=run.googleapis.com/request_count" \
  --project=$PROJECT_ID

# Xem logs real-time
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="parking-api"' \
  --freshness=2m \
  --format="value(textPayload)" \
  --limit=100
```

---

## Bước 13 — (Optional) Cloud Load Balancer + Custom Domain

> Nếu muốn domain riêng hoặc cần Cloud Armor WAF:

```bash
# Tạo serverless NEG cho parking-api
gcloud compute network-endpoint-groups create parking-api-neg \
  --region=$REGION \
  --network-endpoint-type=serverless \
  --cloud-run-service=parking-api

# Tạo backend service
gcloud compute backend-services create parking-api-backend \
  --load-balancing-scheme=EXTERNAL_MANAGED \
  --global

gcloud compute backend-services add-backend parking-api-backend \
  --network-endpoint-group=parking-api-neg \
  --network-endpoint-group-region=$REGION \
  --global

# Tạo URL map, HTTPS proxy, forwarding rule...
# (chi tiết tuỳ domain & SSL cert)
```

---

## Tóm tắt thứ tự thực hiện

| # | Bước | Chi tiết | Ước tính thời gian |
|---|------|----------|-------------------|
| 1 | Enable APIs, Artifact Registry | Bật 11 GCP APIs + tạo Docker repo | 2 phút |
| 2 | Tạo VPC, Subnet, VPC Connector | Private network + connector cho Cloud Run | 5 phút |
| 3 | Tạo Cloud SQL (REGIONAL HA) | PostgreSQL 16, Private IP, SSL only | 10–15 phút |
| 4 | Tạo Memorystore Redis (HA) | Redis 7, TLS + AUTH, STANDARD_HA | 5–10 phút |
| 5 | Tạo KMS Key Ring + 2 keys | RSA-2048 (JWT) + AES-256 (password) | 2 phút |
| 6 | Tạo Secret Manager secrets | db-host, db-password, redis-host, redis-password | 2 phút |
| 7 | Tạo Pub/Sub topic + subscription | Placeholder endpoint, sẽ update ở bước 10 | 1 phút |
| 8 | Tạo Service Accounts + IAM | 3 SA với least-privilege roles | 3 phút |
| 9 | Build & push 3 Docker images | Multi-stage build từ thư mục gốc | 10–20 phút |
| 10a | Deploy **parking-worker** | min=2 · idle=2 · max=4 | 3–5 phút |
| 10b | Update Pub/Sub push endpoint | Gán URL thực của worker | 1 phút |
| 10c | Deploy **parking-outbox** | min=1 · max=1 (singleton) | 2–3 phút |
| 10d | Deploy **parking-api** | min=2 · idle=2 · max=5 | 3–5 phút |
| 11 | Verification (health + e2e) | Health check + register/login/deposit/reserve | 5 phút |
| 12 | Stress test K6 | 1.500 TPS × 3 phút, threshold p95 < 500ms | 10 phút |
| **Tổng** | | | **~65–85 phút** |

### Tóm tắt Cloud Run scaling

| Service | min | idle | max | Lý do |
|---------|-----|------|-----|-------|
| parking-api | 2 | 2 | 5 | Tránh cold start lúc 20:00, đủ slot cho 1.500 TPS (5 × 500 concurrency) |
| parking-outbox | 1 | — | 1 | Singleton scheduler, không cần scale |
| parking-worker | 2 | 2 | 4 | Luôn sẵn nhận Pub/Sub push, workload đến qua queue nên không cần nhiều |

---

## Checklist trước khi go-live

- [ ] Cloud SQL `--require-ssl` đã bật, không có public IP
- [ ] Memorystore `--transit-encryption-mode=SERVER_AUTHENTICATION` đã bật
- [ ] Tất cả secrets được lấy từ Secret Manager, không hardcode trong env
- [ ] Service Account mỗi service chỉ có quyền tối thiểu cần thiết (least privilege)
- [ ] `parking-outbox` và `parking-worker` có `--no-allow-unauthenticated`
- [ ] Pub/Sub push subscription dùng SA authentication
- [ ] VPC Connector `min-instances=2` để tránh cold start khi traffic tăng đột biến
- [ ] `parking-api` `--min-instances=2 --max-instances=5` để không bị cold start lúc 20:00
- [ ] `parking-worker` `--min-instances=2 --max-instances=4` luôn sẵn nhận Pub/Sub push
- [ ] Stress test đạt 1.500 TPS trong 3 phút, error rate < 1%, p95 < 500ms
- [ ] Flyway migration chạy thành công (kiểm tra logs lần deploy đầu tiên)
- [ ] KMS keys đã có ít nhất 1 enabled version

---

## Troubleshooting thường gặp

| Lỗi | Nguyên nhân | Giải pháp |
|-----|------------|-----------|
| `Connection refused` to Cloud SQL | VPC Connector chưa đúng | Kiểm tra `--vpc-egress=all-traffic` |
| `SSL connection required` | DB URL thiếu `?sslmode=require` | Đã có trong application.yml |
| `KMS permission denied` | SA thiếu role `cloudkms.signerVerifier` | Thêm IAM role ở Bước 8 |
| `Secret not found` | Secret name sai hoặc version | Dùng `secret-name:latest` |
| Pub/Sub không trigger worker | Push endpoint sai hoặc SA thiếu invoker | Kiểm tra lại Bước 10.2 |
| Cold start timeout lúc 20:00 | `min-instances=0` | Set `--min-instances=2` cho parking-api |
| Redis `NOAUTH` error | AUTH không được cấu hình | Kiểm tra `REDIS_PASSWORD` trong secret |
