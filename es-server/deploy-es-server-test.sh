#!/usr/bin/env bash
set -Eeuo pipefail

# es-server 测试环境部署脚本。
# 仅重建测试容器并拉取目标镜像，绝不删除本地镜像，避免影响正式服务。

PROJECT_NAME="${PROJECT_NAME:-sano-es-server-test}"
SERVICE_NAME="${SERVICE_NAME:-es-server-test}"
CONTAINER_NAME="${CONTAINER_NAME:-sano-es-server-test}"
IMAGE_NAME="${ES_SERVER_IMAGE:-lxw13000/sano-es-server}"
IMAGE_TAG="${ES_SERVER_IMAGE_TAG:-latest}"
IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"
LATEST_TAG="latest"
NETWORK_NAME="${NETWORK_NAME:-sano-net}"
SERVER_PORT="${SERVER_PORT:-9003}"
START_TIMEOUT="${START_TIMEOUT:-180}"
STABLE_SECONDS="${STABLE_SECONDS:-20}"
LOG_TAIL_LINES="${LOG_TAIL_LINES:-120}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 测试 Compose 文件默认与正式文件分开，避免误操作正式服务。
COMPOSE_FILE="${COMPOSE_FILE:-${SCRIPT_DIR}/docker-compose-test.yml}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    log "ERROR: 未找到 docker compose 或 docker-compose。"
    exit 1
  fi
}

fail() {
  log "ERROR: $*"
  print_diagnostics
  exit 1
}

container_exists() {
  docker ps -a --format '{{.Names}}' | grep -Fxq "$1"
}

inspect_field() {
  docker inspect -f "$1" "${CONTAINER_NAME}" 2>/dev/null || true
}

print_diagnostics() {
  log "诊断信息："
  docker ps -a --filter "name=^/${CONTAINER_NAME}$" || true
  docker inspect "${CONTAINER_NAME}" \
    --format 'status={{.State.Status}} running={{.State.Running}} restarting={{.State.Restarting}} exitCode={{.State.ExitCode}} oomKilled={{.State.OOMKilled}} restartCount={{.RestartCount}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true
  docker logs --tail "${LOG_TAIL_LINES}" "${CONTAINER_NAME}" 2>/dev/null || true
}

ensure_prerequisites() {
  command -v docker >/dev/null 2>&1 || fail "未安装 docker。"
  docker info >/dev/null 2>&1 || fail "Docker daemon 不可用。"
  [ -f "${COMPOSE_FILE}" ] || fail "未找到测试 compose 文件：${COMPOSE_FILE}"

  if ! docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1; then
    log "Docker 网络 ${NETWORK_NAME} 不存在，开始创建。"
    docker network create "${NETWORK_NAME}" >/dev/null
  fi

  mkdir -p "${SCRIPT_DIR}/logs"
  # 容器内应用用户固定为 10001:10001，授权失败时仅提示，兼容无 sudo 环境。
  if command -v sudo >/dev/null 2>&1; then
    sudo chown -R 10001:10001 "${SCRIPT_DIR}/logs" || log "WARN: logs 目录授权失败，请手动执行 sudo chown -R 10001:10001 logs"
    sudo chmod 755 "${SCRIPT_DIR}/logs" || log "WARN: logs 目录权限修改失败，请手动执行 sudo chmod 755 logs"
  else
    chown -R 10001:10001 "${SCRIPT_DIR}/logs" || log "WARN: logs 目录授权失败，请手动执行 chown -R 10001:10001 logs"
    chmod 755 "${SCRIPT_DIR}/logs" || log "WARN: logs 目录权限修改失败，请手动执行 chmod 755 logs"
  fi
}

stop_and_remove_test_container() {
  log "停止测试 compose 服务：project=${PROJECT_NAME}, service=${SERVICE_NAME}"
  ES_SERVER_IMAGE="${IMAGE_NAME}" ES_SERVER_IMAGE_TAG="${IMAGE_TAG}" \
    compose_cmd -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" stop "${SERVICE_NAME}" || true

  log "删除测试 compose 服务容器。"
  ES_SERVER_IMAGE="${IMAGE_NAME}" ES_SERVER_IMAGE_TAG="${IMAGE_TAG}" \
    compose_cmd -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" rm -f "${SERVICE_NAME}" || true

  if container_exists "${CONTAINER_NAME}"; then
    log "发现残留测试容器 ${CONTAINER_NAME}，强制删除。"
    docker rm -f "${CONTAINER_NAME}" >/dev/null
  fi
}

pull_image() {
  # 不删除任何本地镜像。docker pull 会更新目标标签，其他运行容器继续引用原镜像。
  log "拉取测试镜像：${IMAGE}"
  if docker pull "${IMAGE}"; then
    return 0
  fi

  if [ "${IMAGE_TAG}" = "${LATEST_TAG}" ]; then
    fail "latest 镜像拉取失败：${IMAGE}"
  fi

  log "WARN: 指定测试镜像 ${IMAGE} 拉取失败，回退到 ${IMAGE_NAME}:${LATEST_TAG}。"
  IMAGE_TAG="${LATEST_TAG}"
  IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"
  docker pull "${IMAGE}" || fail "fallback 镜像拉取失败：${IMAGE}"
}

start_test_service() {
  log "启动测试服务：${CONTAINER_NAME}"
  ES_SERVER_IMAGE="${IMAGE_NAME}" ES_SERVER_IMAGE_TAG="${IMAGE_TAG}" \
    compose_cmd -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" up -d --no-build --force-recreate "${SERVICE_NAME}"
}

ensure_stable_restart_count() {
  local before="$1"
  local after

  log "确认 ${STABLE_SECONDS}s 内没有再次重启。"
  sleep "${STABLE_SECONDS}"
  after="$(inspect_field '{{.RestartCount}}')"
  [ "${before}" = "${after}" ] || fail "测试容器启动后仍发生重启：before=${before}, after=${after}"
}

wait_until_started() {
  local start_ts now elapsed status running restarting exit_code health restart_count
  start_ts="$(date +%s)"

  log "等待测试容器进入真实可用状态，超时时间 ${START_TIMEOUT}s。"
  while true; do
    container_exists "${CONTAINER_NAME}" || fail "测试容器 ${CONTAINER_NAME} 未创建。"

    status="$(inspect_field '{{.State.Status}}')"
    running="$(inspect_field '{{.State.Running}}')"
    restarting="$(inspect_field '{{.State.Restarting}}')"
    exit_code="$(inspect_field '{{.State.ExitCode}}')"
    restart_count="$(inspect_field '{{.RestartCount}}')"
    health="$(inspect_field '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}')"

    if [ "${running}" = "false" ] || [ "${status}" = "exited" ] || [ "${status}" = "dead" ]; then
      fail "测试容器未运行：status=${status}, exitCode=${exit_code}"
    fi

    if [ "${restarting}" = "true" ]; then
      log "测试容器正在重启中：restartCount=${restart_count}"
    elif [ "${health}" = "healthy" ]; then
      ensure_stable_restart_count "${restart_count}"
      log "启动成功：测试容器健康检查为 healthy，restartCount=${restart_count}"
      return 0
    elif [ "${health}" = "none" ] && [ "${status}" = "running" ]; then
      ensure_stable_restart_count "${restart_count}"
      curl -fsS "http://127.0.0.1:${SERVER_PORT}/health" >/dev/null 2>&1 \
        || fail "测试容器运行但 HTTP 端口 ${SERVER_PORT} 不可访问。"
      log "启动成功：测试容器运行稳定，HTTP 端口 ${SERVER_PORT} 可访问，restartCount=${restart_count}"
      return 0
    else
      log "等待测试健康检查：status=${status}, health=${health}, restartCount=${restart_count}"
    fi

    now="$(date +%s)"
    elapsed=$((now - start_ts))
    [ "${elapsed}" -le "${START_TIMEOUT}" ] || fail "等待测试容器启动超时：status=${status}, health=${health}, restartCount=${restart_count}"
    sleep 5
  done
}

print_success() {
  log "测试服务部署完成。"
  docker ps --filter "name=^/${CONTAINER_NAME}$"
  docker inspect "${CONTAINER_NAME}" \
    --format 'status={{.State.Status}} running={{.State.Running}} restarting={{.State.Restarting}} restartCount={{.RestartCount}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} image={{.Config.Image}}'
}

main() {
  log "开始部署 es-server 测试服务。image=${IMAGE}, compose=${COMPOSE_FILE}"
  ensure_prerequisites
  stop_and_remove_test_container
  pull_image
  start_test_service
  wait_until_started
  print_success
}

main "$@"
