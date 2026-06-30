#!/usr/bin/env bash
set -Eeuo pipefail

# es-server Linux 运维部署脚本。
# 流程：停止旧容器 -> 删除旧容器 -> 删除旧镜像 -> 拉取新镜像 -> 重新启动 -> 校验真实启动状态。

PROJECT_NAME="${PROJECT_NAME:-sano-es-server}"
SERVICE_NAME="${SERVICE_NAME:-es-server}"
CONTAINER_NAME="${CONTAINER_NAME:-sano-es-server}"
IMAGE_NAME="${ES_SERVER_IMAGE:-lxw13000/sano-es-server}"
IMAGE_TAG="${ES_SERVER_IMAGE_TAG:-latest}"
IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"
NETWORK_NAME="${NETWORK_NAME:-sano-net}"
SERVER_PORT="${SERVER_PORT:-8002}"
START_TIMEOUT="${START_TIMEOUT:-180}"
STABLE_SECONDS="${STABLE_SECONDS:-20}"
LOG_TAIL_LINES="${LOG_TAIL_LINES:-120}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-${SCRIPT_DIR}/docker-compose.yml}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

fail() {
  log "ERROR: $*"
  print_diagnostics
  exit 1
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

container_exists() {
  docker ps -a --format '{{.Names}}' | grep -Fxq "$1"
}

container_id() {
  docker ps -aq --filter "name=^/${CONTAINER_NAME}$" | head -n 1
}

inspect_field() {
  local field="$1"
  docker inspect -f "$field" "${CONTAINER_NAME}" 2>/dev/null || true
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
  [ -f "${COMPOSE_FILE}" ] || fail "未找到 compose 文件：${COMPOSE_FILE}"

  if ! docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1; then
    log "Docker 网络 ${NETWORK_NAME} 不存在，开始创建。"
    docker network create "${NETWORK_NAME}" >/dev/null
  fi

  mkdir -p "${SCRIPT_DIR}/logs"
  # 容器内应用用户固定为 10001:10001，授权失败时给出提示但不中断，兼容无 sudo 环境。
  if command -v sudo >/dev/null 2>&1; then
    sudo chown -R 10001:10001 "${SCRIPT_DIR}/logs" || log "WARN: logs 目录授权失败，请手动执行 sudo chown -R 10001:10001 logs"
  else
    chown -R 10001:10001 "${SCRIPT_DIR}/logs" || log "WARN: logs 目录授权失败，请手动执行 chown -R 10001:10001 logs"
  fi
  chmod 755 "${SCRIPT_DIR}/logs" || true
}

stop_and_remove_old_container() {
  log "停止 compose 服务：project=${PROJECT_NAME}, service=${SERVICE_NAME}"
  ES_SERVER_IMAGE="${IMAGE_NAME}" ES_SERVER_IMAGE_TAG="${IMAGE_TAG}" \
    compose_cmd -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" stop "${SERVICE_NAME}" || true

  log "删除 compose 服务容器。"
  ES_SERVER_IMAGE="${IMAGE_NAME}" ES_SERVER_IMAGE_TAG="${IMAGE_TAG}" \
    compose_cmd -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" rm -f "${SERVICE_NAME}" || true

  if container_exists "${CONTAINER_NAME}"; then
    log "发现残留容器 ${CONTAINER_NAME}，强制删除。"
    docker rm -f "${CONTAINER_NAME}" >/dev/null
  fi

  # 兼容旧版本曾经使用过的容器名。
  if [ "${CONTAINER_NAME}" != "es-server" ] && container_exists "es-server"; then
    log "发现历史残留容器 es-server，强制删除。"
    docker rm -f "es-server" >/dev/null
  fi
}

remove_old_image() {
  if docker image inspect "${IMAGE}" >/dev/null 2>&1; then
    log "删除旧镜像：${IMAGE}"
    docker rmi -f "${IMAGE}" >/dev/null || log "WARN: 镜像 ${IMAGE} 删除失败，继续尝试拉取新镜像。"
  else
    log "本地不存在旧镜像：${IMAGE}"
  fi
}

pull_image() {
  log "拉取镜像：${IMAGE}"
  docker pull "${IMAGE}"
}

start_service() {
  log "启动服务：${CONTAINER_NAME}"
  ES_SERVER_IMAGE="${IMAGE_NAME}" ES_SERVER_IMAGE_TAG="${IMAGE_TAG}" \
    compose_cmd -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" up -d --no-build --force-recreate "${SERVICE_NAME}"
}

wait_until_started() {
  local start_ts now elapsed current_id status running restarting exit_code health restart_count last_restart_count
  start_ts="$(date +%s)"
  last_restart_count="-1"

  log "等待容器进入真实可用状态，超时时间 ${START_TIMEOUT}s。"
  while true; do
    current_id="$(container_id)"
    [ -n "${current_id}" ] || fail "容器 ${CONTAINER_NAME} 未创建。"

    status="$(inspect_field '{{.State.Status}}')"
    running="$(inspect_field '{{.State.Running}}')"
    restarting="$(inspect_field '{{.State.Restarting}}')"
    exit_code="$(inspect_field '{{.State.ExitCode}}')"
    restart_count="$(inspect_field '{{.RestartCount}}')"
    health="$(inspect_field '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}')"

    if [ "${running}" = "false" ] || [ "${status}" = "exited" ] || [ "${status}" = "dead" ]; then
      fail "容器未运行：status=${status}, exitCode=${exit_code}"
    fi

    if [ "${restarting}" = "true" ]; then
      log "容器正在重启中：restartCount=${restart_count}"
    elif [ "${health}" = "healthy" ]; then
      ensure_stable_restart_count "${restart_count}"
      log "启动成功：容器健康检查为 healthy，restartCount=${restart_count}"
      return 0
    elif [ "${health}" = "none" ] && [ "${status}" = "running" ]; then
      ensure_http_available "${restart_count}"
      log "启动成功：容器运行稳定，HTTP 端口 ${SERVER_PORT} 可访问，restartCount=${restart_count}"
      return 0
    else
      log "等待健康检查：status=${status}, health=${health}, restartCount=${restart_count}"
    fi

    now="$(date +%s)"
    elapsed=$((now - start_ts))
    [ "${elapsed}" -le "${START_TIMEOUT}" ] || fail "等待启动超时：status=${status}, health=${health}, restartCount=${restart_count}"

    last_restart_count="${restart_count}"
    sleep 5
  done
}

ensure_stable_restart_count() {
  local before after
  before="$1"
  log "确认 ${STABLE_SECONDS}s 内没有再次重启。"
  sleep "${STABLE_SECONDS}"
  after="$(inspect_field '{{.RestartCount}}')"
  [ "${before}" = "${after}" ] || fail "容器启动后仍发生重启：before=${before}, after=${after}"
}

ensure_http_available() {
  local restart_count_before restart_count_after
  restart_count_before="$1"
  log "容器未定义健康检查，改用 HTTP 端口和重启次数判断。"
  sleep "${STABLE_SECONDS}"
  restart_count_after="$(inspect_field '{{.RestartCount}}')"
  [ "${restart_count_before}" = "${restart_count_after}" ] || fail "容器启动后仍发生重启：before=${restart_count_before}, after=${restart_count_after}"
  curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health" >/dev/null 2>&1 \
    || curl -fsS "http://127.0.0.1:${SERVER_PORT}/" >/dev/null 2>&1 \
    || fail "容器运行但 HTTP 端口 ${SERVER_PORT} 不可访问。"
}

print_success() {
  log "部署完成。"
  docker ps --filter "name=^/${CONTAINER_NAME}$"
  docker inspect "${CONTAINER_NAME}" \
    --format 'status={{.State.Status}} running={{.State.Running}} restarting={{.State.Restarting}} restartCount={{.RestartCount}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} image={{.Config.Image}}'
}

main() {
  log "开始部署 es-server。image=${IMAGE}, compose=${COMPOSE_FILE}"
  ensure_prerequisites
  stop_and_remove_old_container
  remove_old_image
  pull_image
  start_service
  wait_until_started
  print_success
}

main "$@"
