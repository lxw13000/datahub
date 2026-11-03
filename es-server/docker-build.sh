#!/usr/bin/env sh
set -eu

# ============================================================
# es-server Docker build script for Linux/macOS
#
# Usage:
#   ./docker-build.sh
#   ./docker-build.sh v1.0.0
#   ./docker-build.sh v1.0.0 --push
#
# Default:
#   Build local single-arch image lxw13000/sano-es-server:latest
#
# With --push:
#   Build and push linux/amd64 + linux/arm64 image by docker buildx
# ============================================================

IMAGE_NAME="lxw13000/sano-es-server"
IMAGE_TAG="${1:-latest}"
PUSH_FLAG="${2:-}"

if [ "$IMAGE_TAG" = "--push" ]; then
  IMAGE_TAG="latest"
  PUSH_FLAG="--push"
fi

FULL_IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"
LATEST_IMAGE="${IMAGE_NAME}:latest"

echo
echo "[INFO] Image: ${FULL_IMAGE}"
if [ "$IMAGE_TAG" != "latest" ]; then
  echo "[INFO] Latest alias: ${LATEST_IMAGE}"
fi
echo

echo "[INFO] Build Spring Boot jar by local Maven..."
mvn -q -DskipTests clean package

if [ "$PUSH_FLAG" = "--push" ]; then
  echo "[INFO] Build multi-arch image and push to Docker Hub..."
  if [ "$IMAGE_TAG" = "latest" ]; then
    docker buildx build --platform linux/amd64,linux/arm64 -t "${FULL_IMAGE}" --push .
  else
    docker buildx build --platform linux/amd64,linux/arm64 -t "${FULL_IMAGE}" -t "${LATEST_IMAGE}" --push .
  fi
else
  echo "[INFO] Build local image..."
  if [ "$IMAGE_TAG" = "latest" ]; then
    docker build -t "${FULL_IMAGE}" .
  else
    docker build -t "${FULL_IMAGE}" -t "${LATEST_IMAGE}" .
  fi
fi

echo
echo "[INFO] Docker build success: ${FULL_IMAGE}"
if [ "$IMAGE_TAG" != "latest" ]; then
  echo "[INFO] Docker latest updated: ${LATEST_IMAGE}"
fi
