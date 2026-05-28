#!/usr/bin/env bash
# =============================================================================
# deploy.sh — Build, push, and deploy all 3 parking services to Cloud Run
#
# Usage:
#   ./deploy/deploy.sh [api|outbox|worker|all]   (default: all)
#
# Prerequisites:
#   - gcloud CLI authenticated: gcloud auth login
#   - Docker authenticated:     gcloud auth configure-docker asia-southeast1-docker.pkg.dev
# =============================================================================

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
PROJECT_ID="parking-system-497603"
REGION="asia-southeast1"
REGISTRY="asia-southeast1-docker.pkg.dev"
REPO="parking-repo"
IMAGE_PREFIX="${REGISTRY}/${PROJECT_ID}/${REPO}"

VPC_CONNECTOR="projects/${PROJECT_ID}/locations/${REGION}/connectors/parking-connector"

SA_API_EMAIL="parking-api-sa@${PROJECT_ID}.iam.gserviceaccount.com"
SA_OUTBOX_EMAIL="parking-outbox-sa@${PROJECT_ID}.iam.gserviceaccount.com"
SA_WORKER_EMAIL="parking-worker-sa@${PROJECT_ID}.iam.gserviceaccount.com"

TAG="${IMAGE_TAG:-latest}"
TARGET="${1:-all}"

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Validate ──────────────────────────────────────────────────────────────────
command -v gcloud &>/dev/null || error "gcloud CLI not found."
command -v docker  &>/dev/null || error "docker not found."

info "Project   : ${PROJECT_ID}"
info "Region    : ${REGION}"
info "Registry  : ${IMAGE_PREFIX}"
info "Tag       : ${TAG}"
info "Target    : ${TARGET}"
echo ""

# =============================================================================
# BUILD & PUSH  (--platform linux/amd64 required on Apple Silicon)
# =============================================================================
build_push() {
  local SERVICE="$1"
  local IMAGE="${IMAGE_PREFIX}/${SERVICE}:${TAG}"

  info "Building  → ${IMAGE}"
  docker build \
    --platform linux/amd64 \
    -f "${SERVICE}/Dockerfile" \
    -t "${IMAGE}" \
    .

  info "Pushing   → ${IMAGE}"
  docker push "${IMAGE}"
  success "Image ready: ${IMAGE}"
}

# =============================================================================
# DEPLOY FUNCTIONS
# =============================================================================

# parking-api
# --allow-unauthenticated  (public endpoint)
# --no-cpu-throttling --cpu-boost  (latency-sensitive)
# KMS keys for JWT signing & password encryption
deploy_api() {
  local IMAGE="${IMAGE_PREFIX}/parking-api:${TAG}"
  info "Deploying parking-api …"
  gcloud run deploy parking-api \
    --image="${IMAGE}" \
    --region="${REGION}" \
    --platform=managed \
    --service-account="${SA_API_EMAIL}" \
    --vpc-connector="${VPC_CONNECTOR}" \
    --vpc-egress=all-traffic \
    --allow-unauthenticated \
    --port=8080 \
    --min-instances=2 \
    --max-instances=5 \
    --cpu=2 \
    --memory=4Gi \
    --concurrency=500 \
    --timeout=30 \
    --execution-environment=gen2 \
    --no-cpu-throttling \
    --cpu-boost \
    --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID}" \
    --set-env-vars="KMS_LOCATION=${REGION},KMS_KEY_RING=parking-keyring,KMS_JWT_KEY=parking-jwt-key,KMS_PASSWORD_KEY=parking-password-key" \
    --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest,REDIS_HOST=redis-host:latest,REDIS_PASSWORD=redis-password:latest" \
    --set-env-vars="DB_PORT=5432,DB_NAME=parking_db,DB_USER=parking_user,REDIS_PORT=6379"
  success "parking-api deployed ✓"
}

# parking-outbox
# --concurrency=1      (single-threaded outbox poller — no parallelism)
# --vpc-egress=private-ranges-only
#   Only private IPs (Cloud SQL 10.20.1.2, Redis 10.239.31.4) go through VPC connector.
#   Google API calls (Pub/Sub pubsub.googleapis.com) go directly via Google's network
#   → eliminates VPC cold-start latency on first Pub/Sub publish.
deploy_outbox() {
  local IMAGE="${IMAGE_PREFIX}/parking-outbox:${TAG}"
  info "Deploying parking-outbox …"
  gcloud run deploy parking-outbox \
    --image="${IMAGE}" \
    --region="${REGION}" \
    --platform=managed \
    --service-account="${SA_OUTBOX_EMAIL}" \
    --vpc-connector="${VPC_CONNECTOR}" \
    --vpc-egress=private-ranges-only \
    --no-allow-unauthenticated \
    --port=8081 \
    --min-instances=1 \
    --max-instances=1 \
    --cpu=1 \
    --memory=2Gi \
    --concurrency=1 \
    --timeout=60 \
    --execution-environment=gen2 \
    --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID},PUBSUB_TOPIC=reservation.created" \
    --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest" \
    --set-env-vars="DB_PORT=5432,DB_NAME=parking_db,DB_USER=parking_user"
  success "parking-outbox deployed ✓"
}

# parking-worker
# --timeout=300  (long-running Pub/Sub message processing)
deploy_worker() {
  local IMAGE="${IMAGE_PREFIX}/parking-worker:${TAG}"
  info "Deploying parking-worker …"
  gcloud run deploy parking-worker \
    --image="${IMAGE}" \
    --region="${REGION}" \
    --platform=managed \
    --service-account="${SA_WORKER_EMAIL}" \
    --vpc-connector="${VPC_CONNECTOR}" \
    --vpc-egress=all-traffic \
    --no-allow-unauthenticated \
    --port=8082 \
    --min-instances=2 \
    --max-instances=4 \
    --cpu=1 \
    --memory=2Gi \
    --concurrency=80 \
    --timeout=300 \
    --execution-environment=gen2 \
    --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID}" \
    --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest,REDIS_HOST=redis-host:latest,REDIS_PASSWORD=redis-password:latest" \
    --set-env-vars="DB_PORT=5432,DB_NAME=parking_db,DB_USER=parking_user,REDIS_PORT=6379"
  success "parking-worker deployed ✓"
}

# =============================================================================
# MAIN
# =============================================================================
case "$TARGET" in
  api)
    build_push parking-api
    deploy_api
    ;;
  outbox)
    build_push parking-outbox
    deploy_outbox
    ;;
  worker)
    build_push parking-worker
    deploy_worker
    ;;
  all)
    # Build tất cả trước
    build_push parking-api
    build_push parking-outbox
    build_push parking-worker
    # Deploy worker trước → lấy URL cho Pub/Sub
    deploy_worker
    deploy_outbox
    deploy_api
    ;;
  *)
    error "Unknown target: ${TARGET}. Use: api | outbox | worker | all"
    ;;
esac

echo ""
success "=== Deploy hoàn tất ==="
echo ""
echo "  parking-api    → https://parking-api-763182684658.asia-southeast1.run.app"
echo "  parking-outbox → https://parking-outbox-763182684658.asia-southeast1.run.app"
echo "  parking-worker → https://parking-worker-763182684658.asia-southeast1.run.app"
