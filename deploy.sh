#!/usr/bin/env bash
# =============================================================================
# deploy.sh — build, push, and deploy eventhub-outbox to AKS
#
# Prerequisites (all must be installed and already authenticated):
#   docker     – Docker Desktop or Docker Engine
#   az         – Azure CLI  (run "az login" first)
#   kubectl    – merged with the target AKS cluster
#                (run "az aks get-credentials" first, or let this script do it)
#
# Usage:
#   ./deploy.sh                            # uses env-var defaults
#   ./deploy.sh --acr myacr                # override ACR name
#   ./deploy.sh --tag v1.2.3               # explicit image tag (default: git SHA or timestamp)
#   ./deploy.sh --cluster myaks --rg myRG  # override cluster / resource-group
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults — override via flags or environment variables
# ---------------------------------------------------------------------------
ACR="${ACR:-YOUR_ACR}"                          # Azure Container Registry login name
CLUSTER="${CLUSTER:-your-aks-cluster}"          # AKS cluster name
RESOURCE_GROUP="${RESOURCE_GROUP:-your-rg}"     # Azure resource group
IMAGE_NAME="eventhub-outbox"
NAMESPACE="outbox-svc"

# Tag defaults to short git SHA if inside a git repo, otherwise a timestamp.
if git rev-parse --short HEAD >/dev/null 2>&1; then
  DEFAULT_TAG="$(git rev-parse --short HEAD)"
else
  DEFAULT_TAG="$(date +%Y%m%d%H%M%S)"
fi
TAG="${TAG:-$DEFAULT_TAG}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}"      # adjust if deploy/ is a sub-directory

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --acr)           ACR="$2";            shift 2 ;;
    --tag)           TAG="$2";            shift 2 ;;
    --cluster)       CLUSTER="$2";        shift 2 ;;
    --rg)            RESOURCE_GROUP="$2"; shift 2 ;;
    --help|-h)
      echo "Usage: $0 [--acr ACR] [--tag TAG] [--cluster CLUSTER] [--rg RG]"
      exit 0 ;;
    *)  echo "Unknown flag: $1"; exit 1 ;;
  esac
done

FULL_IMAGE="${ACR}.azurecr.io/${IMAGE_NAME}:${TAG}"

echo "============================================================"
echo "  eventhub-outbox — Docker + AKS deployment"
echo "============================================================"
echo "  Image          : ${FULL_IMAGE}"
echo "  AKS Cluster    : ${CLUSTER}"
echo "  Resource Group : ${RESOURCE_GROUP}"
echo "  Namespace      : ${NAMESPACE}"
echo "============================================================"
echo ""

# ---------------------------------------------------------------------------
# Step 1 — Docker build
# ---------------------------------------------------------------------------
echo ">>> [1/7] Building Docker image..."
cd "${PROJECT_ROOT}"
docker build -t "${FULL_IMAGE}" .
echo "    ✓ image built"

# ---------------------------------------------------------------------------
# Step 2 — ACR login
# ---------------------------------------------------------------------------
echo ""
echo ">>> [2/7] Logging in to ACR (${ACR})..."
az acr login --name "${ACR}"
echo "    ✓ logged in"

# ---------------------------------------------------------------------------
# Step 3 — Push
# ---------------------------------------------------------------------------
echo ""
echo ">>> [3/7] Pushing image..."
docker push "${FULL_IMAGE}"
echo "    ✓ pushed"

# ---------------------------------------------------------------------------
# Step 4 — Patch image tag into deployment.yaml
# ---------------------------------------------------------------------------
echo ""
echo ">>> [4/7] Patching deployment.yaml with new image tag..."
DEPLOY_YAML="${SCRIPT_DIR}/k8s/03-deployment.yaml"
# Replace either the placeholder or any previously patched tag
sed -i "s|${ACR}.azurecr.io/${IMAGE_NAME}:.*|${FULL_IMAGE}\"|g" "${DEPLOY_YAML}"
echo "    ✓ patched: ${FULL_IMAGE}"

# ---------------------------------------------------------------------------
# Step 5 — Ensure kubectl is pointing at the right cluster
# ---------------------------------------------------------------------------
echo ""
echo ">>> [5/7] Fetching AKS credentials..."
az aks get-credentials \
  --resource-group "${RESOURCE_GROUP}" \
  --name            "${CLUSTER}" \
  --overwrite-existing
echo "    ✓ credentials merged"

# ---------------------------------------------------------------------------
# Step 6 — Apply manifests in order (file names are numbered 00-06)
# ---------------------------------------------------------------------------
echo ""
echo ">>> [6/7] Applying Kubernetes manifests..."
for f in "${SCRIPT_DIR}"/k8s/*.yaml; do
  echo "    applying $(basename "$f") ..."
  kubectl apply -f "$f"
done
echo "    ✓ all manifests applied"

# ---------------------------------------------------------------------------
# Step 7 — Wait for rollout, print status
# ---------------------------------------------------------------------------
echo ""
echo ">>> [7/7] Waiting for rollout to finish..."
kubectl -n "${NAMESPACE}" rollout status deployment/${IMAGE_NAME} --timeout=180s
echo ""
echo "============================================================"
echo "  DONE — deployment is live"
echo "============================================================"
kubectl -n "${NAMESPACE}" get pods -o wide
echo ""
kubectl -n "${NAMESPACE}" get svc