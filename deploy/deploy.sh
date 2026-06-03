#!/usr/bin/env bash
# =============================================================================
# deploy.sh — Build, push, and deploy parking-api and parking-worker to Cloud Run
#
# Usage:
#   ./deploy/deploy.sh [api|worker|all]   (default: all)
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
SA_WORKER_EMAIL="parking-worker-sa@${PROJECT_ID}.iam.gserviceaccount.com"

TAG="${IMAGE_TAG:-latest}"
TARGET="${1:-all}"

# Stress profile: DEPLOY_PROFILE=stress ./deploy/deploy.sh all
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ "${DEPLOY_PROFILE:-}" == "stress" && -f "${SCRIPT_DIR}/stress-profile.env" ]]; then
  # shellcheck disable=SC1091
  source "${SCRIPT_DIR}/stress-profile.env"
  info "Using DEPLOY_PROFILE=stress (see deploy/stress-profile.env)"
fi

API_MIN_INSTANCES="${API_MIN_INSTANCES:-2}"
API_MAX_INSTANCES="${API_MAX_INSTANCES:-5}"
API_CPU="${API_CPU:-2}"
API_MEMORY="${API_MEMORY:-4Gi}"
API_CONCURRENCY="${API_CONCURRENCY:-500}"
API_TIMEOUT="${API_TIMEOUT:-30}"

WORKER_MIN_INSTANCES="${WORKER_MIN_INSTANCES:-2}"
WORKER_MAX_INSTANCES="${WORKER_MAX_INSTANCES:-4}"
WORKER_CPU="${WORKER_CPU:-1}"
WORKER_MEMORY="${WORKER_MEMORY:-2Gi}"
WORKER_CONCURRENCY="${WORKER_CONCURRENCY:-80}"
WORKER_TIMEOUT="${WORKER_TIMEOUT:-300}"

APP_PUBSUB_ORDERING_ENABLED="${APP_PUBSUB_ORDERING_ENABLED:-true}"

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
    --min-instances="${API_MIN_INSTANCES}" \
    --max-instances="${API_MAX_INSTANCES}" \
    --cpu="${API_CPU}" \
    --memory="${API_MEMORY}" \
    --concurrency="${API_CONCURRENCY}" \
    --timeout="${API_TIMEOUT}" \
    --execution-environment=gen2 \
    --no-cpu-throttling \
    --cpu-boost \
    --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID},APP_LOCAL_MODE=false,APP_PUBSUB_ENABLED=true,APP_PUBSUB_BOOTSTRAP_ENABLED=false,APP_PUBSUB_ORDERING_ENABLED=${APP_PUBSUB_ORDERING_ENABLED},PUBSUB_TOPIC=reservation.created" \
    --set-env-vars="KMS_LOCATION=${REGION},KMS_KEY_RING=parking-keyring,KMS_JWT_KEY=parking-jwt-key,KMS_PASSWORD_KEY=parking-password-key" \
    --set-secrets="DB_HOST=db-host:latest,DB_PASSWORD=db-password:latest,REDIS_HOST=redis-host:latest,REDIS_PASSWORD=redis-password:latest" \
    --set-env-vars="DB_PORT=5432,DB_NAME=parking_db,DB_USER=parking_user,REDIS_PORT=6379"
  success "parking-api deployed ✓"
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
    --min-instances="${WORKER_MIN_INSTANCES}" \
    --max-instances="${WORKER_MAX_INSTANCES}" \
    --cpu="${WORKER_CPU}" \
    --memory="${WORKER_MEMORY}" \
    --concurrency="${WORKER_CONCURRENCY}" \
    --timeout="${WORKER_TIMEOUT}" \
    --execution-environment=gen2 \
    --no-cpu-throttling \
    --set-env-vars="SPRING_PROFILES_ACTIVE=cloudrun,GCP_PROJECT_ID=${PROJECT_ID}" \
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
  worker)
    build_push parking-worker
    deploy_worker
    ;;
  all)
    build_push parking-api
    build_push parking-worker
    deploy_worker
    deploy_api
    ;;
  *)
    error "Unknown target: ${TARGET}. Use: api | worker | all"
    ;;
esac

echo ""
success "=== Deploy hoàn tất ==="
echo ""
echo "  parking-api    → https://parking-api-763182684658.asia-southeast1.run.app"
echo "  parking-worker → https://parking-worker-763182684658.asia-southeast1.run.app"
