# Thực tế Deploy be-parking-sys lên GCP — Log đầy đủ

> **Project ID:** parking-system-497603
> **Region:** asia-southeast1 (Singapore)
> **Ngày thực hiện:** 27/05/2026

---

## Tổng quan kết quả cuối cùng

| Service | URL | Status |
|---------|-----|--------|
| parking-api | https://parking-api-763182684658.asia-southeast1.run.app | ✅ Running |
| parking-outbox | https://parking-outbox-763182684658.asia-southeast1.run.app | ✅ Running |
| parking-worker | https://parking-worker-763182684658.asia-southeast1.run.app | ✅ Running |
| Cloud SQL | 10.20.1.2:5432 | ✅ Running |
| Redis | 10.239.31.4:6379 | ✅ Running |
| Pub/Sub | reservation.created | ✅ Configured |

---

## Bước 1 — GCP Setup

### 1.1 Cấu hình project

```bash
export PROJECT_ID="parking-system-497603"
export REGION="asia-southeast1"

gcloud config set project $PROJECT_ID
gcloud config set compute/region $REGION
gcloud auth application-default login
```

### 1.2 Enable APIs

```bash
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

### 1.3 Tạo Artifact Registry

```bash
gcloud artifacts repositories create parking-repo \
  --repository-format=docker \
  --location=asia-southeast1 \
  --description="Parking System Docker images"

gcloud auth configure-docker asia-southeast1-docker.pkg.dev
```

---

## Bước 2 — VPC & Network

### 2.1 Tạo VPC + Subnet

```bash
gcloud compute networks create parking-vpc \
  --subnet-mode=custom \
  --bgp-routing-mode=regional

gcloud compute networks subnets create parking-subnet \
  --network=parking-vpc \
  --region=asia-southeast1 \
  --range=10.20.0.0/24
```

### 2.2 Private Services Connection (VPC Peering)

```bash
gcloud compute addresses create google-managed-services-parking-vpc \
  --global \
  --purpose=VPC_PEERING \
  --addresses=10.20.1.0 \
  --prefix-length=24 \
  --network=parking-vpc

gcloud services vpc-peerings connect \
  --service=servicenetworking.googleapis.com \
  --ranges=google-managed-services-parking-vpc \
  --network=parking-vpc \
  --project=$PROJECT_ID
```

### 2.3 VPC Access Connector (e2-standard-4)

> ⚠️ Ban đầu tạo e2-micro, sau đó xoá và tạo lại e2-standard-4 để đảm bảo throughput cho stress test.

```bash
# Tạo lại với e2-standard-4
gcloud compute networks vpc-access connectors create parking-connector \
  --region=asia-southeast1 \
  --network=parking-vpc \
  --range=10.20.2.0/28 \
  --min-instances=2 \
  --max-instances=10 \
  --machine-type=e2-standard-4
```

---

## Bước 3 — Cloud SQL

```bash
# Tạo instance
# ⚠️ Lỗi gặp phải: tier db-custom-4-15360 cần thêm --edition=ENTERPRISE
# ⚠️ Lỗi gặp phải: work_mem=16MB → phải đổi thành work_mem=16384 (đơn vị kB)
gcloud sql instances create parking-db \
  --database-version=POSTGRES_16 \
  --region=asia-southeast1 \
  --tier=db-custom-4-15360 \
  --edition=ENTERPRISE \
  --availability-type=REGIONAL \
  --no-assign-ip \
  --network=projects/$PROJECT_ID/global/networks/parking-vpc \
  --require-ssl \
  --storage-type=SSD \
  --storage-size=10GB \
  --storage-auto-increase \
  --database-flags=max_connections=300,work_mem=16384 \
  --backup-start-time=03:00

# Kết quả: PRIVATE_ADDRESS = 10.20.1.2

# Tạo database và user
gcloud sql databases create parking_db --instance=parking-db

export DB_PASS=$(openssl rand -base64 24)
gcloud sql users create parking_user \
  --instance=parking-db \
  --password="$DB_PASS"

export DB_PRIVATE_IP="10.20.1.2"
```

### Nâng max_connections sau (tối ưu cho stress test)

```bash
gcloud sql instances patch parking-db \
  --database-flags=max_connections=400,work_mem=16384
```

---

## Bước 4 — Memorystore Redis

> ⚠️ Lỗi gặp phải: `--tier=STANDARD_HA` → phải đổi thành `--tier=standard`
> ⚠️ Lỗi gặp phải: `--transit-encryption-mode` không update được sau khi tạo → xoá tạo lại không TLS

```bash
# Lần đầu tạo có TLS → gặp lỗi Redisson không connect được
# Xoá và tạo lại không TLS (vẫn an toàn vì trong VPC private)
gcloud redis instances create parking-redis \
  --size=1 \
  --region=asia-southeast1 \
  --redis-version=redis_7_0 \
  --network=projects/$PROJECT_ID/global/networks/parking-vpc \
  --tier=standard \
  --enable-auth

# Lấy thông tin kết nối
export REDIS_HOST=$(gcloud redis instances describe parking-redis \
  --region=asia-southeast1 --format='value(host)')
export REDIS_PASS=$(gcloud redis instances get-auth-string parking-redis \
  --region=asia-southeast1 --format='value(authString)')

# Kết quả:
# REDIS_HOST = 10.239.31.4
# REDIS_PASS = a44d8169-4bc0-47b1-878b-dab04bc71c35
```

---

## Bước 5 — Cloud KMS

```bash
gcloud kms keyrings create parking-keyring --location=asia-southeast1

# JWT Signing Key (RSA-2048)
gcloud kms keys create parking-jwt-key \
  --location=asia-southeast1 \
  --keyring=parking-keyring \
  --purpose=asymmetric-signing \
  --default-algorithm=rsa-sign-pkcs1-2048-sha256 \
  --protection-level=software

# Password Encryption Key (AES-256)
gcloud kms keys create parking-password-key \
  --location=asia-southeast1 \
  --keyring=parking-keyring \
  --purpose=encryption \
  --default-algorithm=google-symmetric-encryption \
  --protection-level=software
```

---

## Bước 6 — Secret Manager

```bash
echo -n "$DB_PRIVATE_IP" | gcloud secrets create db-host \
  --data-file=- --replication-policy=user-managed --locations=asia-southeast1

echo -n "$DB_PASS" | gcloud secrets create db-password \
  --data-file=- --replication-policy=user-managed --locations=asia-southeast1

echo -n "$REDIS_HOST" | gcloud secrets create redis-host \
  --data-file=- --replication-policy=user-managed --locations=asia-southeast1

echo -n "$REDIS_PASS" | gcloud secrets create redis-password \
  --data-file=- --replication-policy=user-managed --locations=asia-southeast1

# Sau khi tạo lại Redis (IP/pass thay đổi) → update secret
echo -n "$REDIS_HOST" | gcloud secrets versions add redis-host --data-file=-
echo -n "$REDIS_PASS" | gcloud secrets versions add redis-password --data-file=-
```

---

## Bước 7 — Pub/Sub

```bash
gcloud pubsub topics create reservation.created

gcloud pubsub subscriptions create reservation-worker-sub \
  --topic=reservation.created \
  --push-endpoint=https://placeholder.run.app/internal/pubsub/push \
  --ack-deadline=60 \
  --min-retry-delay=10s \
  --max-retry-delay=60s \
  --message-retention-duration=1d
```

---

## Bước 8 — Service Accounts & IAM

```bash
gcloud iam service-accounts create parking-api-sa \
  --display-name="Parking API Service Account"
gcloud iam service-accounts create parking-outbox-sa \
  --display-name="Parking Outbox Service Account"
gcloud iam service-accounts create parking-worker-sa \
  --display-name="Parking Worker Service Account"

export SA_API_EMAIL="parking-api-sa@${PROJECT_ID}.iam.gserviceaccount.com"
export SA_OUTBOX_EMAIL="parking-outbox-sa@${PROJECT_ID}.iam.gserviceaccount.com"
export SA_WORKER_EMAIL="parking-worker-sa@${PROJECT_ID}.iam.gserviceaccount.com"

# parking-api roles
for role in roles/cloudsql.client roles/cloudkms.cryptoKeyEncrypterDecrypter \
  roles/cloudkms.signerVerifier roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${SA_API_EMAIL}" --role="$role"
done

# parking-outbox roles
for role in roles/cloudsql.client roles/pubsub.publisher \
  roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${SA_OUTBOX_EMAIL}" --role="$role"
done

# parking-worker roles
for role in roles/cloudsql.client roles/pubsub.subscriber \
  roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:${SA_WORKER_EMAIL}" --role="$role"
done
```

---

## Bước 9 — Build & Push Docker Images

> ⚠️ Lỗi gặp phải: Build trên Mac Apple Silicon (ARM) → Cloud Run cần `--platform linux/amd64`

```bash
cd /path/to/be-parking-sys
export IMAGE_PREFIX="asia-southeast1-docker.pkg.dev/${PROJECT_ID}/parking-repo"

# parking-api (rebuild sau khi fix HikariCP pool size 20→30)
docker build --platform linux/amd64 -f parking-api/Dockerfile \
  -t ${IMAGE_PREFIX}/parking-api:latest .
docker push ${IMAGE_PREFIX}/parking-api:latest

# parking-outbox (rebuild sau khi xoá emulator-host)
docker build --platform linux/amd64 -f parking-outbox/Dockerfile \
  -t ${IMAGE_PREFIX}/parking-outbox:latest .
docker push ${IMAGE_PREFIX}/parking-outbox:latest

# parking-worker (rebuild sau khi xoá emulator-host + xoá Redis SSL config)
docker build --platform linux/amd64 -f parking-worker/Dockerfile \
  -t ${IMAGE_PREFIX}/parking-worker:latest .
docker push ${IMAGE_PREFIX}/parking-worker:latest
```

---

## Bước 10 — Các fix code trước khi deploy thành công

### Fix 1: Xoá emulator-host trong application.yml

Cả `parking-worker` và `parking-outbox` có dòng này gây crash:
```yaml
# XOÁ 2 dòng này:
pubsub:
  emulator-host: # leave empty for production
```

### Fix 2: Cloud SQL SSL mode

```bash
# --require-ssl mặc định yêu cầu client certificate → đổi xuống ENCRYPTED_ONLY
gcloud sql instances patch parking-db --ssl-mode=ENCRYPTED_ONLY
```

### Fix 3: Redis TLS

Redisson không connect được Memorystore TLS → xoá SSL config trong application.yml:
```yaml
# XOÁ 2 dòng này trong parking-worker và parking-api:
ssl:
  enabled: true
```
Đồng thời xoá và tạo lại Redis instance không có TLS.

### Fix 4: HikariCP pool size (tối ưu stress test)

```yaml
# parking-api/application.yml
hikari:
  maximum-pool-size: 30  # tăng từ 20 → 30
```

---

## Bước 11 — Deploy Cloud Run

```bash
export VPC_CONNECTOR="projects/${PROJECT_ID}/locations/asia-southeast1/connectors/parking-connector"

# 10a. Deploy parking-worker TRƯỚC (lấy URL cho Pub/Sub)
gcloud run deploy parking-worker \
  --image=${IMAGE_PREFIX}/parking-worker:latest \
  --region=asia-southeast1 --platform=managed \
  --service-account="${SA_WORKER_EMAIL}" \
  --vpc-connector=$VPC_CONNECTOR --vpc-egress=all-traffic \
  --no-allow-unauthenticated --port=8082 \
  --min-instances=2 --max-instances=4 \
  --cpu=1 --memory=2Gi --concurrency=80 --timeout=300 \
  --execution-environment=gen2 \
  --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID}" \
  --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest,REDIS_HOST=redis-host:latest,REDIS_PASSWORD=redis-password:latest" \
  --set-env-vars="DB_PORT=5432,DB_NAME=parking_db,DB_USER=parking_user,REDIS_PORT=6379"

# → Service URL: https://parking-worker-763182684658.asia-southeast1.run.app

# 10b. Update Pub/Sub push endpoint
export WORKER_URL="https://parking-worker-763182684658.asia-southeast1.run.app"
export PUBSUB_SA="service-763182684658@gcp-sa-pubsub.iam.gserviceaccount.com"

gcloud pubsub subscriptions modify-push-config reservation-worker-sub \
  --push-endpoint="${WORKER_URL}/internal/pubsub/push" \
  --push-auth-service-account="${SA_WORKER_EMAIL}"

gcloud run services add-iam-policy-binding parking-worker \
  --member="serviceAccount:${PUBSUB_SA}" \
  --role="roles/run.invoker" --region=asia-southeast1

# 10c. Deploy parking-outbox
# ⚠️ Lỗi: gen2 không hỗ trợ cpu < 1 → đổi --cpu=0.5 thành --cpu=1
gcloud run deploy parking-outbox \
  --image=${IMAGE_PREFIX}/parking-outbox:latest \
  --region=asia-southeast1 --platform=managed \
  --service-account="${SA_OUTBOX_EMAIL}" \
  --vpc-connector=$VPC_CONNECTOR --vpc-egress=all-traffic \
  --no-allow-unauthenticated --port=8081 \
  --min-instances=1 --max-instances=1 \
  --cpu=1 --memory=2Gi --concurrency=1 --timeout=60 \
  --execution-environment=gen2 \
  --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID},PUBSUB_TOPIC=reservation.created" \
  --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest" \
  --set-env-vars="DB_PORT=5432,DB_NAME=parking_db,DB_USER=parking_user"

# 10d. Deploy parking-api
gcloud run deploy parking-api \
  --image=${IMAGE_PREFIX}/parking-api:latest \
  --region=asia-southeast1 --platform=managed \
  --service-account="${SA_API_EMAIL}" \
  --vpc-connector=$VPC_CONNECTOR --vpc-egress=all-traffic \
  --allow-unauthenticated --port=8080 \
  --min-instances=2 --max-instances=5 \
  --cpu=2 --memory=4Gi --concurrency=500 --timeout=30 \
  --execution-environment=gen2 \
  --no-cpu-throttling --cpu-boost \
  --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID}" \
  --set-env-vars="KMS_LOCATION=asia-southeast1,KMS_KEY_RING=parking-keyring,KMS_JWT_KEY=parking-jwt-key,KMS_PASSWORD_KEY=parking-password-key" \
  --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest,REDIS_HOST=redis-host:latest,REDIS_PASSWORD=redis-password:latest" \
  --set-env-vars="DB_PORT=5432,DB_NAME=parking_db,DB_USER=parking_user,REDIS_PORT=6379"
```

---

## Bước 12 — Verification

```bash
export API_URL="https://parking-api-763182684658.asia-southeast1.run.app"

curl -s "${API_URL}/actuator/health" | jq .
# Kết quả:
# status: UP
# db (PostgreSQL): UP
# redis (7.0.15): UP
```

---

## Bước 13 — Local Debug Setup

### Cloud SQL tunnel qua Bastion VM

```bash
# Tạo bastion VM
gcloud compute instances create bastion \
  --zone=asia-southeast1-a --machine-type=e2-micro \
  --network=parking-vpc --subnet=parking-subnet \
  --no-address --scopes=cloud-platform

# Firewall cho IAP SSH
gcloud compute firewall-rules create allow-iap-ssh \
  --network=parking-vpc --allow=tcp:22 \
  --source-ranges=35.235.240.0/20 --target-tags=bastion

gcloud compute instances add-tags bastion \
  --tags=bastion --zone=asia-southeast1-a

# SSH tunnel: DB port 5433, Redis port 6379
gcloud compute ssh bastion \
  --zone=asia-southeast1-a \
  --tunnel-through-iap \
  -- -L 5433:10.20.1.2:5432 -L 6379:10.239.31.4:6379 -N
```

### Thông tin kết nối local

**Cloud SQL:**
```
Host:     127.0.0.1
Port:     5433
Database: parking_db
Username: parking_user
Password: (lấy từ: gcloud secrets versions access latest --secret=db-password)
```

**Redis:**
```
Host:     127.0.0.1
Port:     6379
Password: a44d8169-4bc0-47b1-878b-dab04bc71c35
```

---

## Các lỗi gặp phải & cách fix

| Lỗi | Nguyên nhân | Fix |
|-----|------------|-----|
| `Invalid Tier db-custom-4-15360` | Thiếu `--edition=ENTERPRISE` | Thêm `--edition=ENTERPRISE` |
| `16MB is not a number` | work_mem phải là số kB | Đổi thành `work_mem=16384` |
| `Container manifest must support amd64` | Build trên Mac ARM | Thêm `--platform linux/amd64` |
| `Expected authority at index 2: //` | `emulator-host:` trống trong yml | Xoá dòng `emulator-host` |
| `connection requires a valid client certificate` | Cloud SQL SSL mode | Patch `--ssl-mode=ENCRYPTED_ONLY` |
| `Unable to connect to Redis: timeout` | Memorystore TLS + Redisson config phức tạp | Tạo lại Redis không TLS |
| `Total cpu < 1 not supported with gen2` | Gen2 cần CPU ≥ 1 | Đổi `--cpu=0.5` thành `--cpu=1` |
| `address already in use :5433` | cloud-sql-proxy cũ vẫn chạy | `lsof -ti:5433 \| xargs kill -9` |

---

## Cloud Run Scaling Config cuối cùng

| Service | min | max | CPU | Memory | Concurrency |
|---------|-----|-----|-----|--------|-------------|
| parking-api | 2 | 5 | 2 | 4Gi | 500 |
| parking-outbox | 1 | 1 | 1 | 2Gi | 1 |
| parking-worker | 2 | 4 | 1 | 2Gi | 80 |
