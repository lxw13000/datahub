#!/usr/bin/env bash
set -Eeuo pipefail

# es-server测试环境独立安全部署脚本。
# 本文件完整包含query接管、drain/cancel和回滚逻辑，不依赖正式环境部署脚本。
# safe：测试环境已运行版本A后使用；legacy：仅用于首次从旧镜像升级到版本A。

PROJECT_NAME="sano-es-server-test"
SERVICE_NAME="es-server"
QUERY_SERVICE_NAME="es-server-query"
CONTAINER_NAME="sano-es-server-test"
QUERY_CONTAINER_NAME="sano-es-server-test-query"
IMAGE_NAME="${ES_SERVER_IMAGE:-lxw13000/sano-es-server}"
IMAGE_TAG="${ES_SERVER_IMAGE_TAG:-latest}"
IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"
NETWORK_NAME="${NETWORK_NAME:-sano-net}"
SERVER_PORT="9003"
QUERY_PORT="9004"
START_TIMEOUT="${START_TIMEOUT:-180}"
# Compose detached启动命令的独立超时；防止客户端已启动容器却长期不退出，导致/ready检查无法执行。
COMPOSE_UP_TIMEOUT="${COMPOSE_UP_TIMEOUT:-30}"
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-600}"
POLL_INTERVAL="${POLL_INTERVAL:-2}"
STABLE_SECONDS="${STABLE_SECONDS:-20}"
LOG_TAIL_LINES="${LOG_TAIL_LINES:-120}"
DEPLOY_MODE="${DEPLOY_MODE:-safe}"
SYNC_API_TOKEN="${SYNC_API_TOKEN:-sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F}"
DEPLOY_LOCK_DIR="/tmp/sano-es-server-test-deploy.lock"
NGINX_HANDOFF_PRECONFIGURED="${NGINX_HANDOFF_PRECONFIGURED:-false}"
NGINX_TEST_COMMAND="${NGINX_TEST_COMMAND:-}"
NGINX_RELOAD_COMMAND="${NGINX_RELOAD_COMMAND:-}"
# 必须是真实业务查询，safe模式在仅query容器承接时和新主恢复后各执行一次。
PUBLIC_QUERY_BASE_URL="${PUBLIC_QUERY_BASE_URL:-http://es-server-test.fofunlive.net}"
INTERNAL_QUERY_BASE_URL="${INTERNAL_QUERY_BASE_URL:-http://127.0.0.1:9103}"
if [ -z "${NGINX_SMOKE_COMMAND:-}" ]; then
  NGINX_SMOKE_COMMAND="curl -fsS --connect-timeout 5 --max-time 30 -H 'token: ${SYNC_API_TOKEN}' '${PUBLIC_QUERY_BASE_URL}/ready' >/dev/null && curl -fsS --connect-timeout 5 --max-time 30 -H 'token: ${SYNC_API_TOKEN}' '${INTERNAL_QUERY_BASE_URL}/ready' >/dev/null"
fi

# 测试资源名和端口固定，防止与同机正式环境交叉操作。
SANO_SERVER_MODE="all"
export NETWORK_NAME CONTAINER_NAME QUERY_CONTAINER_NAME SERVER_PORT QUERY_PORT SANO_SERVER_MODE

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose-test.yml"
MAIN_BASE_URL="http://127.0.0.1:${SERVER_PORT}"
QUERY_BASE_URL="http://127.0.0.1:${QUERY_PORT}"

OLD_IMAGE_ID=""
# 旧容器启动时使用的镜像引用，例如lxw13000/sano-es-server:v1.0.7。
OLD_IMAGE_REFERENCE=""
# 从旧容器引用或同一镜像的RepoTags识别出的来源版本，用于回滚标签和部署日志。
SOURCE_IMAGE_REFERENCE=""
SOURCE_VERSION_TAG=""
ROLLBACK_TAG=""
ROLLBACK_IMAGE=""
# 实际用于query接管和回滚重建的原版本标签；原标签不可靠时回退到固定安全标签。
RESTORE_TAG=""
RESTORE_IMAGE=""
DRAIN_OPERATION_ID=""
QUERY_STARTED=0
DRAIN_ACTIVE=0
MAIN_REPLACED=0
DEPLOY_SUCCEEDED=0
LOCK_ACQUIRED=0

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    log "ERROR: 未找到docker compose或docker-compose。"
    return 1
  fi
}

# 为docker compose up -d增加独立边界。仅当超时后目标容器确实已运行时，
# 才允许转入后续/ready严格校验；其他退出码保持失败，由部署退出保护执行回滚。
compose_up_detached() {
  local container_name="$1"
  shift
  local exit_code

  if docker compose version >/dev/null 2>&1; then
    if timeout --signal=TERM --kill-after=5s "${COMPOSE_UP_TIMEOUT}s" docker compose "$@"; then
      return 0
    else
      exit_code=$?
    fi
  elif command -v docker-compose >/dev/null 2>&1; then
    if timeout --signal=TERM --kill-after=5s "${COMPOSE_UP_TIMEOUT}s" docker-compose "$@"; then
      return 0
    else
      exit_code=$?
    fi
  else
    log "ERROR: 未找到docker compose或docker-compose。"
    return 1
  fi

  if [ "${exit_code}" -eq 124 ] && container_running "${container_name}"; then
    log "WARN: Compose启动命令超过${COMPOSE_UP_TIMEOUT}s未退出，但容器${container_name}已运行；继续执行严格就绪检查。"
    return 0
  fi
  log "ERROR: Compose启动失败：container=${container_name}, exitCode=${exit_code}。"
  return "${exit_code}"
}

container_exists() {
  local container_names
  container_names="$(docker ps -a --format '{{.Names}}')" || {
    log "ERROR: Docker容器列表查询失败，不能据此判定容器不存在。"
    return 2
  }
  # 不使用grep -q提前关闭管道，避免pipefail把Docker的SIGPIPE误判成容器不存在。
  printf '%s\n' "${container_names}" | grep -Fx "$1" >/dev/null
}

container_running() {
  [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)" = "true" ]
}

inspect_field() {
  docker inspect -f "$2" "$1" 2>/dev/null || true
}

print_diagnostics() {
  local container_name
  for container_name in "${CONTAINER_NAME}" "${QUERY_CONTAINER_NAME}"; do
    container_exists "${container_name}" || continue
    log "诊断容器：${container_name}"
    docker inspect "${container_name}" \
      --format 'status={{.State.Status}} running={{.State.Running}} restarting={{.State.Restarting}} exitCode={{.State.ExitCode}} oomKilled={{.State.OOMKilled}} restartCount={{.RestartCount}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} image={{.Config.Image}}' 2>/dev/null || true
    docker logs --tail "${LOG_TAIL_LINES}" "${container_name}" 2>/dev/null || true
  done
}

fail() {
  log "ERROR: $*"
  print_diagnostics
  exit 1
}

api_curl() {
  curl -fsS --connect-timeout 5 --max-time 30 -H "token: ${SYNC_API_TOKEN}" "$@"
}

json_value() {
  printf '%s' "$1" | jq -er "$2"
}

acquire_deploy_lock() {
  if ! mkdir "${DEPLOY_LOCK_DIR}" 2>/dev/null; then
    fail "已有部署正在执行，或锁目录无法创建：${DEPLOY_LOCK_DIR}"
  fi
  LOCK_ACQUIRED=1
  printf '%s\n' "$$" >"${DEPLOY_LOCK_DIR}/pid"
}

release_deploy_lock() {
  [ "${LOCK_ACQUIRED}" -eq 1 ] || return 0
  rm -f "${DEPLOY_LOCK_DIR}/pid" 2>/dev/null || true
  rmdir "${DEPLOY_LOCK_DIR}" 2>/dev/null || true
  LOCK_ACQUIRED=0
}

ensure_prerequisites() {
  command -v docker >/dev/null 2>&1 || fail "未安装docker。"
  command -v curl >/dev/null 2>&1 || fail "未安装curl。"
  command -v jq >/dev/null 2>&1 || fail "未安装jq；部署脚本需要严格解析接口业务状态。"
  command -v timeout >/dev/null 2>&1 || fail "未安装timeout；部署脚本无法限制Compose启动等待时间。"
  docker info >/dev/null 2>&1 || fail "Docker daemon不可用。"
  [ -f "${COMPOSE_FILE}" ] || fail "未找到compose文件：${COMPOSE_FILE}"
  [ "${DEPLOY_MODE}" = "safe" ] || [ "${DEPLOY_MODE}" = "legacy" ] \
    || fail "DEPLOY_MODE只支持safe或legacy。"
  [ "${SERVICE_NAME}" = "es-server" ] && [ "${QUERY_SERVICE_NAME}" = "es-server-query" ] \
    || fail "compose服务名固定为es-server与es-server-query，不允许覆盖。"
  [ -n "${SYNC_API_TOKEN}" ] || fail "部署使用的内部接口Token不能为空。"

  if ! docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1; then
    log "Docker网络${NETWORK_NAME}不存在，开始创建。"
    docker network create "${NETWORK_NAME}" >/dev/null
  fi

  mkdir -p "${SCRIPT_DIR}/logs-test" "${SCRIPT_DIR}/logs-test-query"
  if command -v sudo >/dev/null 2>&1; then
    sudo chown -R 10001:10001 "${SCRIPT_DIR}/logs-test" "${SCRIPT_DIR}/logs-test-query" \
      || log "WARN: 日志目录授权失败，请手动修正10001:10001权限。"
    sudo chmod 755 "${SCRIPT_DIR}/logs-test" "${SCRIPT_DIR}/logs-test-query" \
      || log "WARN: 日志目录权限修改失败。"
  else
    chown -R 10001:10001 "${SCRIPT_DIR}/logs-test" "${SCRIPT_DIR}/logs-test-query" \
      || log "WARN: 日志目录授权失败，请手动修正10001:10001权限。"
    chmod 755 "${SCRIPT_DIR}/logs-test" "${SCRIPT_DIR}/logs-test-query" \
      || log "WARN: 日志目录权限修改失败。"
  fi
}

capture_stable_image() {
  local exists_result=0
  container_exists "${CONTAINER_NAME}" || exists_result=$?
  [ "${exists_result}" -ne 2 ] || fail "无法确认旧主是否存在，拒绝按首次安装替换。"
  [ "${exists_result}" -eq 0 ] || return 0
  OLD_IMAGE_ID="$(inspect_field "${CONTAINER_NAME}" '{{.Image}}')"
  [ -n "${OLD_IMAGE_ID}" ] || fail "无法取得当前稳定镜像ID。"
  OLD_IMAGE_REFERENCE="$(inspect_field "${CONTAINER_NAME}" '{{.Config.Image}}')"

  if ! container_running "${CONTAINER_NAME}"; then
    [ "${DEPLOY_MODE}" = "legacy" ] \
      || fail "测试主容器存在但未运行，safe模式拒绝替换；确认旧容器无需恢复后使用DEPLOY_MODE=legacy。"
    log "WARN: 测试主容器已停止；legacy模式将固定旧镜像后替换该容器。reference=${OLD_IMAGE_REFERENCE:-unknown}, id=${OLD_IMAGE_ID}"
    return 0
  fi
  log "已识别当前稳定镜像：reference=${OLD_IMAGE_REFERENCE:-unknown}, id=${OLD_IMAGE_ID}"
}

create_safety_rollback_tag() {
  # 已记录的安全标签也可能被外部删除或改指，只有镜像ID仍匹配时才能复用。
  if [ -n "${ROLLBACK_IMAGE}" ] \
    && [ "$(docker image inspect "${ROLLBACK_IMAGE}" --format '{{.Id}}' 2>/dev/null || true)" = "${OLD_IMAGE_ID}" ]; then
    return 0
  fi
  local safety_version safety_tag safety_image
  safety_version="${SOURCE_VERSION_TAG:-unknown}"
  safety_version="$(printf '%s' "${safety_version}" | sed 's/[^A-Za-z0-9_.-]/-/g')"
  safety_tag="test-rollback-${safety_version:0:80}-$(date '+%Y%m%d%H%M%S')"
  safety_image="${IMAGE_NAME}:${safety_tag}"
  # 回滚在退出保护中执行，不能依赖set -e；创建和确认失败时必须显式阻止替换主容器。
  if ! docker image tag "${OLD_IMAGE_ID}" "${safety_image}" \
    || [ "$(docker image inspect "${safety_image}" --format '{{.Id}}' 2>/dev/null || true)" != "${OLD_IMAGE_ID}" ]; then
    log "ERROR: 无法固定旧镜像，禁止继续回滚替换。image=${safety_image}, expectedId=${OLD_IMAGE_ID}"
    return 1
  fi
  ROLLBACK_TAG="${safety_tag}"
  ROLLBACK_IMAGE="${safety_image}"
}

prepare_rollback_image() {
  [ -n "${OLD_IMAGE_ID}" ] || return 0
  local original_tag referenced_image_id repo_version_reference

  SOURCE_IMAGE_REFERENCE="${OLD_IMAGE_REFERENCE}"
  SOURCE_VERSION_TAG=""
  # 历史脚本可能让容器显示rollback时间戳标签；此时从同一镜像的RepoTags恢复可读版本号。
  if [[ "${OLD_IMAGE_REFERENCE}" == "${IMAGE_NAME}:v"[0-9]* ]]; then
    SOURCE_VERSION_TAG="${OLD_IMAGE_REFERENCE#"${IMAGE_NAME}:"}"
  else
    repo_version_reference="$(
      docker image inspect "${OLD_IMAGE_ID}" --format '{{range .RepoTags}}{{println .}}{{end}}' 2>/dev/null \
        | awk -v prefix="${IMAGE_NAME}:v" 'index($0, prefix) == 1' \
        | sort -V | tail -n 1 || true
    )"
    if [ -n "${repo_version_reference}" ]; then
      SOURCE_IMAGE_REFERENCE="${repo_version_reference}"
      SOURCE_VERSION_TAG="${repo_version_reference#"${IMAGE_NAME}:"}"
    elif [[ "${OLD_IMAGE_REFERENCE}" == "${IMAGE_NAME}:"* ]]; then
      original_tag="${OLD_IMAGE_REFERENCE#"${IMAGE_NAME}:"}"
      if [[ "${original_tag}" != rollback-* ]] && [[ "${original_tag}" != test-rollback-* ]]; then
        SOURCE_VERSION_TAG="${original_tag}"
      fi
    fi
  fi

  ROLLBACK_TAG=""
  ROLLBACK_IMAGE=""
  RESTORE_TAG=""
  RESTORE_IMAGE=""
  # 可信版本标签仍指向旧镜像且不会被本次pull覆盖时直接复用，不额外创建时间戳标签。
  if [[ "${SOURCE_VERSION_TAG}" == v[0-9]* ]] \
    && [[ "${SOURCE_IMAGE_REFERENCE}" == "${IMAGE_NAME}:"* ]] \
    && [ "${SOURCE_IMAGE_REFERENCE}" != "${IMAGE}" ]; then
    original_tag="${SOURCE_IMAGE_REFERENCE#"${IMAGE_NAME}:"}"
    referenced_image_id="$(docker image inspect "${SOURCE_IMAGE_REFERENCE}" --format '{{.Id}}' 2>/dev/null || true)"
    if [ -n "${original_tag}" ] && [ "${referenced_image_id}" = "${OLD_IMAGE_ID}" ]; then
      RESTORE_TAG="${original_tag}"
      RESTORE_IMAGE="${SOURCE_IMAGE_REFERENCE}"
    fi
  fi
  if [ -z "${RESTORE_IMAGE}" ]; then
    create_safety_rollback_tag
    RESTORE_TAG="${ROLLBACK_TAG}"
    RESTORE_IMAGE="${ROLLBACK_IMAGE}"
  fi
  log "已准备当前稳定镜像：sourceVersion=${SOURCE_VERSION_TAG:-unknown}, source=${SOURCE_IMAGE_REFERENCE:-unknown}, restore=${RESTORE_IMAGE}, safety=${ROLLBACK_IMAGE:-未创建（复用原版本标签）}, id=${OLD_IMAGE_ID}"
}

# safe模式必须在启动旧镜像query容器前证明旧主已经具备版本A协议。
preflight_stable_capability() {
  local ready_response status_response ready_mode status_mode state code
  ready_response="$(api_curl "${MAIN_BASE_URL}/ready")" \
    || fail "当前稳定镜像没有严格/ready能力；首次升级请显式使用DEPLOY_MODE=legacy。"
  json_value "${ready_response}" '.ready == true' >/dev/null \
    || fail "当前稳定主实例未就绪，不能启动安全接管。"
  ready_mode="$(json_value "${ready_response}" '.serviceMode')" \
    || fail "当前稳定镜像/ready缺少serviceMode；首次升级请使用legacy。"
  [ "${ready_mode,,}" = "all" ] \
    || fail "当前稳定主实例serviceMode=${ready_mode}，安全升级要求all。"

  status_response="$(api_curl "${MAIN_BASE_URL}/internal/sync/drain/status")" \
    || fail "当前稳定镜像没有统一drain协议；首次升级请显式使用DEPLOY_MODE=legacy。"
  code="$(json_value "${status_response}" '.code')" || fail "drain/status响应格式不兼容版本A。"
  status_mode="$(json_value "${status_response}" '.data.serviceMode')" || fail "drain/status缺少serviceMode。"
  state="$(json_value "${status_response}" '.data.coordinatorState')" || fail "drain/status缺少coordinatorState。"
  [ "${code}" = "200" ] && [ "${status_mode,,}" = "all" ] && [ "${state}" = "RUNNING" ] \
    || fail "稳定主实例不满足安全升级前提：code=${code}, mode=${status_mode}, state=${state}。"
  log "稳定镜像已通过版本A能力预检。"
}

validate_safe_handoff_config() {
  [ -n "${NGINX_SMOKE_COMMAND}" ] \
    || fail "safe模式必须配置NGINX_SMOKE_COMMAND执行真实业务查询。"
  if [ "${NGINX_HANDOFF_PRECONFIGURED,,}" = "true" ]; then
    log "已确认Nginx的127.0.0.1:${QUERY_PORT} backup upstream预先生效。"
    return 0
  fi
  [ -n "${NGINX_TEST_COMMAND}" ] && [ -n "${NGINX_RELOAD_COMMAND}" ] \
    || fail "未声明Nginx已预配置；必须同时提供NGINX_TEST_COMMAND和NGINX_RELOAD_COMMAND。"
}

pull_target_image() {
  log "拉取目标镜像：${IMAGE}"
  docker pull "${IMAGE}" || fail "目标镜像拉取失败：${IMAGE}"
}

start_query_handoff() {
  [ -n "${RESTORE_IMAGE}" ] || fail "没有可用于query-only接管的稳定镜像。"
  log "启动临时query-only容器：${QUERY_CONTAINER_NAME}, image=${RESTORE_IMAGE}"
  # 启动请求可能已创建容器但客户端返回异常，调用前先标记，确保退出保护能够清理临时实例。
  QUERY_STARTED=1
  QUERY_ES_SERVER_IMAGE="${RESTORE_IMAGE}" compose_up_detached "${QUERY_CONTAINER_NAME}" \
    -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" --profile query-handoff \
    up -d --no-build --force-recreate "${QUERY_SERVICE_NAME}"
  wait_until_ready "${QUERY_CONTAINER_NAME}" "${QUERY_BASE_URL}" "query" \
    || fail "临时query-only实例未就绪，停止本次升级，旧主保持运行。"
  run_nginx_handoff_commands
}

run_nginx_handoff_commands() {
  if [ -n "${NGINX_TEST_COMMAND}" ]; then
    log "检查Nginx配置。"
    bash -c "${NGINX_TEST_COMMAND}" || fail "Nginx配置检查失败。"
  fi
  if [ -n "${NGINX_RELOAD_COMMAND}" ]; then
    log "重新加载Nginx，使query backup接管配置生效。"
    bash -c "${NGINX_RELOAD_COMMAND}" || fail "Nginx reload失败。"
  fi
}

run_nginx_smoke() {
  log "执行真实业务查询冒烟：$1"
  bash -c "${NGINX_SMOKE_COMMAND}" || fail "$1真实业务查询冒烟失败。"
}

# 统一等待主实例/QUERY就绪，只返回结果，不决定退出或回滚。
# expected_mode为空仅用于legacy旧镜像回滚：旧版本可能没有/ready，此时检查/health。
wait_until_ready() {
  local container_name="$1"
  local base_url="$2"
  local expected_mode="$3"
  local start_ts now response mode ready before after
  start_ts="$(date +%s)"

  log "等待${container_name}就绪，等待上限${START_TIMEOUT}s。"
  while true; do
    if ! container_running "${container_name}"; then
      log "ERROR: 容器${container_name}未运行或无法确认运行状态。"
      return 1
    fi
    ready=0
    mode=""
    if [ -z "${expected_mode}" ]; then
      curl -fsS --connect-timeout 5 --max-time 10 "${base_url}/health" >/dev/null 2>&1 && ready=1
    else
      response="$(api_curl "${base_url}/ready" 2>/dev/null || true)"
      mode="$(json_value "${response}" '.serviceMode' 2>/dev/null || true)"
      if json_value "${response}" '.ready == true' >/dev/null 2>&1 \
        && [ "${mode,,}" = "${expected_mode,,}" ]; then
        ready=1
      fi
    fi
    if [ "${ready}" -eq 1 ]; then
      # 就绪后保留原稳定观察，防止容器直接退出或重启却被当作启动成功。
      before="$(inspect_field "${container_name}" '{{.RestartCount}}')"
      sleep "${STABLE_SECONDS}"
      after="$(inspect_field "${container_name}" '{{.RestartCount}}')"
      if [ -z "${before}" ] || [ "${before}" != "${after}" ] || ! container_running "${container_name}"; then
        log "ERROR: 容器${container_name}在稳定观察期内退出、无法检查或再次重启。"
        return 1
      fi
      log "容器已就绪并通过稳定观察：${container_name}, serviceMode=${mode:-legacy-health}"
      return 0
    fi
    now="$(date +%s)"
    if [ $((now - start_ts)) -ge "${START_TIMEOUT}" ]; then
      log "ERROR: 等待${container_name}就绪超过${START_TIMEOUT}s。"
      return 1
    fi
    # 固定HTTP超时和轮询间隔；总等待允许多出最后一次请求及稳定观察的时间。
    sleep "${POLL_INTERVAL}"
  done
}

request_drain() {
  local response code result
  log "请求主实例进入drain。"
  # POST可能已被服务端接受但客户端丢失响应，因此发送前即标记为待cancel。
  DRAIN_ACTIVE=1
  response="$(api_curl -X POST "${MAIN_BASE_URL}/internal/sync/drain")" \
    || fail "drain请求结果不确定；退出保护将查询状态并尝试cancel，主容器保持运行。"
  code="$(json_value "${response}" '.code')" || fail "drain响应不是有效ResultVO：${response}"
  DRAIN_OPERATION_ID="$(json_value "${response}" '.data.operationId')" \
    || fail "drain响应缺少operationId：${response}"
  result="$(json_value "${response}" '.data.drainResult')" || fail "drain响应缺少drainResult。"
  [ "${code}" = "200" ] || fail "drain业务失败：${response}"
  log "drain已提交：operationId=${DRAIN_OPERATION_ID}, result=${result}"
}

wait_for_drain() {
  local start_ts now response code result operation_id
  start_ts="$(date +%s)"
  while true; do
    response="$(api_curl "${MAIN_BASE_URL}/internal/sync/drain/status")" \
      || { cancel_drain_best_effort; fail "drain/status不可用，部署已中止。"; }
    code="$(json_value "${response}" '.code')" \
      || { cancel_drain_best_effort; fail "drain/status响应格式错误。"; }
    [ "${code}" = "200" ] \
      || { cancel_drain_best_effort; fail "drain/status业务失败：${response}"; }
    operation_id="$(json_value "${response}" '.data.operationId')" \
      || { cancel_drain_best_effort; fail "drain/status缺少operationId。"; }
    [ "${operation_id}" = "${DRAIN_OPERATION_ID}" ] \
      || { cancel_drain_best_effort; fail "drain操作ID发生变化，拒绝停止主实例。"; }
    result="$(json_value "${response}" '.data.drainResult')" \
      || { cancel_drain_best_effort; fail "drain/status缺少drainResult。"; }

    case "${result}" in
      DRAINED)
        log "主实例已安全排空。"
        return 0
        ;;
      DRAINED_WITH_ERRORS)
        log "WARN: 主实例带业务错误安全排空；失败任务不会被伪装为成功，可以继续部署。"
        return 0
        ;;
      FAILED)
        cancel_drain_best_effort
        fail "主实例drain失败，部署已取消。"
        ;;
      IN_PROGRESS)
        ;;
      *)
        cancel_drain_best_effort
        fail "收到未知drain结果：${result}"
        ;;
    esac

    now="$(date +%s)"
    if [ $((now - start_ts)) -gt "${DRAIN_TIMEOUT}" ]; then
      cancel_drain_best_effort
      fail "等待drain超过${DRAIN_TIMEOUT}s，部署已取消。"
    fi
    sleep "${POLL_INTERVAL}"
  done
}

cancel_drain_best_effort() {
  [ "${DRAIN_ACTIVE}" -eq 1 ] || return 0
  local status response code result operation_id cancel_url

  # drain POST响应丢失时先从status恢复operationId；仍无法获得时才取消当前操作。
  if [ -z "${DRAIN_OPERATION_ID}" ]; then
    status="$(api_curl "${MAIN_BASE_URL}/internal/sync/drain/status" 2>/dev/null || true)"
    if [ -n "${status}" ] && [ "$(json_value "${status}" '.code' 2>/dev/null || true)" = "200" ]; then
      DRAIN_OPERATION_ID="$(json_value "${status}" '.data.operationId // empty' 2>/dev/null || true)"
    fi
  fi

  cancel_url="${MAIN_BASE_URL}/internal/sync/drain/cancel"
  [ -z "${DRAIN_OPERATION_ID}" ] || cancel_url="${cancel_url}?operationId=${DRAIN_OPERATION_ID}"
  log "取消drain并请求恢复本次中断任务。operationId=${DRAIN_OPERATION_ID:-unknown}"
  response="$(api_curl -X POST "${cancel_url}" 2>/dev/null || true)"
  code="$(json_value "${response}" '.code' 2>/dev/null || true)"
  result="$(json_value "${response}" '.data.drainResult' 2>/dev/null || true)"
  operation_id="$(json_value "${response}" '.data.operationId // empty' 2>/dev/null || true)"
  if [ "${code}" = "200" ] && [ "${result}" = "CANCELLED" ] \
    && { [ -z "${DRAIN_OPERATION_ID}" ] || [ "${operation_id}" = "${DRAIN_OPERATION_ID}" ]; }; then
    DRAIN_ACTIVE=0
    [ -n "${DRAIN_OPERATION_ID}" ] || DRAIN_OPERATION_ID="${operation_id}"
    return 0
  fi
  log "WARN: drain/cancel未被确认成功，请立即人工检查主实例同步状态。response=${response:-empty}"
  return 1
}

stop_and_remove_main() {
  MAIN_REPLACED=1
  log "停止并删除测试主容器：${CONTAINER_NAME}"
  # 旧测试容器可能由历史Compose项目创建，按固定容器名删除可避免新项目启动时名称冲突。
  if container_running "${CONTAINER_NAME}"; then
    docker stop --time 30 "${CONTAINER_NAME}"
  fi
  container_exists "${CONTAINER_NAME}" && docker rm -f "${CONTAINER_NAME}"
  DRAIN_ACTIVE=0
}

start_main_with_tag() {
  log "启动主容器：${IMAGE_NAME}:$1"
  ES_SERVER_IMAGE="${IMAGE_NAME}" ES_SERVER_IMAGE_TAG="$1" \
    compose_up_detached "${CONTAINER_NAME}" -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" \
    up -d --no-build --force-recreate "${SERVICE_NAME}"
}

verify_new_sync_runtime() {
  local response code mode state
  response="$(api_curl "${MAIN_BASE_URL}/internal/sync/drain/status")" \
    || fail "新版同步状态接口不可用。"
  code="$(json_value "${response}" '.code')" || fail "新版同步状态响应格式错误。"
  mode="$(json_value "${response}" '.data.serviceMode')" || fail "新版同步状态缺少serviceMode。"
  state="$(json_value "${response}" '.data.coordinatorState')" || fail "新版同步状态缺少coordinatorState。"
  [ "${code}" = "200" ] && [ "${mode,,}" = "all" ] && [ "${state}" = "RUNNING" ] \
    || fail "新版同步协调器未恢复：code=${code}, mode=${mode}, state=${state}。"
  log "新版同步协调器已恢复RUNNING。"

}

stop_query_handoff() {
  [ "${QUERY_STARTED}" -eq 1 ] || return 0
  log "停止临时query-only容器。"
  QUERY_ES_SERVER_IMAGE="${RESTORE_IMAGE}" compose_cmd \
    -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" --profile query-handoff \
    stop "${QUERY_SERVICE_NAME}"
  QUERY_ES_SERVER_IMAGE="${RESTORE_IMAGE}" compose_cmd \
    -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" --profile query-handoff \
    rm -f "${QUERY_SERVICE_NAME}"
  local exists_result=0
  container_exists "${QUERY_CONTAINER_NAME}" || exists_result=$?
  [ "${exists_result}" -eq 1 ] \
    || fail "临时query-only容器仍存在或无法确认已删除，部署不能标记成功。"
  QUERY_STARTED=0
}

stop_query_handoff_best_effort() {
  [ "${QUERY_STARTED}" -eq 1 ] || return 0
  QUERY_ES_SERVER_IMAGE="${RESTORE_IMAGE}" compose_cmd \
    -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" --profile query-handoff \
    stop "${QUERY_SERVICE_NAME}" >/dev/null 2>&1 || true
  QUERY_ES_SERVER_IMAGE="${RESTORE_IMAGE}" compose_cmd \
    -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" --profile query-handoff \
    rm -f "${QUERY_SERVICE_NAME}" >/dev/null 2>&1 || true
  QUERY_STARTED=0
}

ensure_query_handoff_running_best_effort() {
  [ "${DEPLOY_MODE}" = "safe" ] || return 0
  QUERY_STARTED=1
  if ! container_running "${QUERY_CONTAINER_NAME}"; then
    log "回滚前恢复临时query-only容器。"
    QUERY_ES_SERVER_IMAGE="${RESTORE_IMAGE}" compose_up_detached "${QUERY_CONTAINER_NAME}" \
      -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" --profile query-handoff \
      up -d --no-build --force-recreate "${QUERY_SERVICE_NAME}" || return 1
  fi
  wait_until_ready "${QUERY_CONTAINER_NAME}" "${QUERY_BASE_URL}" "query"
}

# 回滚排空与正常升级分开处理：失败只返回原因，不cancel、不exit，由回滚分支告警后强制停止。
# operationId仅使用当前新主返回值，不能沿用已停止旧主的drain操作ID。
drain_main_before_rollback() {
  local response operation_id current_id code mode state result error
  local start_ts now
  start_ts="$(date +%s)"
  log "回滚前请求新主优雅排空，等待上限${DRAIN_TIMEOUT}s，允许最后一次请求或轮询略超出。"
  response="$(api_curl -X POST "${MAIN_BASE_URL}/internal/sync/drain")" || {
    log "WARN: 回滚前新主drain请求失败或响应不确定，无法确认安全排空。"
    return 1
  }
  operation_id="$(json_value "${response}" '.data.operationId' 2>/dev/null || true)"
  if [ -z "${operation_id}" ]; then
    log "WARN: 回滚前新主drain响应缺少operationId，无法确认安全排空。"
    return 1
  fi

  while true; do
    code="$(json_value "${response}" '.code' 2>/dev/null || true)"
    mode="$(json_value "${response}" '.data.serviceMode' 2>/dev/null || true)"
    state="$(json_value "${response}" '.data.coordinatorState' 2>/dev/null || true)"
    current_id="$(json_value "${response}" '.data.operationId' 2>/dev/null || true)"
    result="$(json_value "${response}" '.data.drainResult' 2>/dev/null || true)"
    if [ "${code}" != "200" ] || [ "${mode,,}" != "all" ] \
      || [ "${state}" != "DRAINING" ] || [ "${current_id}" != "${operation_id}" ]; then
      log "WARN: 回滚前新主drain状态无法确认。code=${code}, mode=${mode}, state=${state}, expectedOperationId=${operation_id}, actualOperationId=${current_id}"
      return 1
    fi
    case "${result}" in
      DRAINED|DRAINED_WITH_ERRORS)
        log "回滚前新主已安全排空。operationId=${operation_id}, result=${result}"
        [ "${result}" != "DRAINED_WITH_ERRORS" ] \
          || log "WARN: 新主虽已安全排空，但存在业务失败任务，回滚后需复核并按需补数。"
        return 0
        ;;
      IN_PROGRESS)
        ;;
      *)
        error="$(json_value "${response}" '.data.error // empty' 2>/dev/null || true)"
        log "WARN: 回滚前新主未安全排空。operationId=${operation_id}, result=${result:-unknown}, error=${error:-未提供}"
        return 1
        ;;
    esac

    now="$(date +%s)"
    if [ $((now - start_ts)) -ge "${DRAIN_TIMEOUT}" ]; then
      log "WARN: 回滚前等待新主排空超过${DRAIN_TIMEOUT}s。operationId=${operation_id}"
      return 1
    fi
    # 复用固定HTTP超时和轮询间隔；失败交给调用方明确告警后执行强制回滚。
    sleep "${POLL_INTERVAL}"
    response="$(api_curl "${MAIN_BASE_URL}/internal/sync/drain/status")" || {
      log "WARN: 回滚前查询新主drain状态失败，无法确认安全排空。operationId=${operation_id}"
      return 1
    }
  done
}

rollback_main_best_effort() {
  [ -n "${RESTORE_TAG}" ] || {
    log "ERROR: 没有可回滚的旧镜像。"
    return 1
  }
  # 回滚前再次确认原版本标签没有被删除或改指；发生漂移时使用固定安全标签兜底。
  if [ "$(docker image inspect "${RESTORE_IMAGE}" --format '{{.Id}}' 2>/dev/null || true)" != "${OLD_IMAGE_ID}" ]; then
    create_safety_rollback_tag || return 1
    log "WARN: 原回滚镜像引用已漂移，已按需创建安全标签：${ROLLBACK_IMAGE}"
    RESTORE_TAG="${ROLLBACK_TAG}"
    RESTORE_IMAGE="${ROLLBACK_IMAGE}"
  fi
  ensure_query_handoff_running_best_effort \
    || log "ERROR: 回滚查询接管未确认成功，请关注业务查询可用性；继续恢复旧主。"
  log "开始回滚主实例：${RESTORE_IMAGE}"
  local force_rollback=0 exists_result=0
  container_exists "${CONTAINER_NAME}" || exists_result=$?
  [ "${exists_result}" -ne 2 ] || {
    log "ERROR: 无法确认新主是否存在，禁止启动旧主，请人工检查Docker状态。"
    return 1
  }
  if [ "${exists_result}" -eq 0 ]; then
    if container_running "${CONTAINER_NAME}"; then
      if drain_main_before_rollback; then
        docker stop --time 30 "${CONTAINER_NAME}" >/dev/null 2>&1 || force_rollback=1
      else
        force_rollback=1
      fi
    elif [ "$(inspect_field "${CONTAINER_NAME}" '{{.State.Restarting}}')" = "true" ]; then
      # 自动重启中的容器不能当成已稳定停止，否则可能在删除前再次启动。
      log "WARN: 回滚前新主处于自动重启状态，无法完成排空，将按强制回滚处理。"
      force_rollback=1
    else
      log "WARN: 回滚前新主已停止，不能补做安全排空；恢复旧版本后需复核中断任务。"
    fi
    if [ "${force_rollback}" -eq 1 ]; then
      log "ERROR: 强制回滚：新主未确认安全排空或正常停止失败，即将强制删除${CONTAINER_NAME}并恢复${RESTORE_IMAGE}。本次不是安全排空成功。"
      log "WARN: 强制回滚可能留下RUNNING任务或未保存的内存进度；后续从持久断点恢复时可能重放。请复核任务状态、索引设置及数据完整性，必要时人工补数；恢复不保证在容器启动后立即执行。"
      print_diagnostics
      docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || {
        log "ERROR: 强制删除新主失败，禁止启动旧主，请人工检查容器和Docker状态。"
        return 1
      }
    else
      # 安全排空后只删除已停止容器；若它意外仍在运行，不能静默升级为强杀。
      docker rm "${CONTAINER_NAME}" >/dev/null 2>&1 || {
        log "ERROR: 删除已停止的新主失败，禁止启动旧主，请人工检查。"
        return 1
      }
    fi
    exists_result=0
    container_exists "${CONTAINER_NAME}" || exists_result=$?
    if [ "${exists_result}" -ne 1 ]; then
      log "ERROR: 新主容器仍存在或无法确认已删除，禁止启动旧主，请人工检查。"
      return 1
    fi
  fi
  start_main_with_tag "${RESTORE_TAG}" || return 1

  local expected_mode="all"
  if [ "${DEPLOY_MODE}" = "legacy" ]; then
    expected_mode=""
    log "WARN: legacy回滚仅验证旧版本/health，查询及同步恢复情况需要人工复核。"
  fi
  wait_until_ready "${CONTAINER_NAME}" "${MAIN_BASE_URL}" "${expected_mode}" || return 1
  if [ "${force_rollback}" -eq 1 ]; then
    log "WARN: 强制回滚已完成，旧镜像主实例已恢复：${RESTORE_IMAGE}。新主未确认安全停止，请复核中断任务和数据；如有临时query-only容器，确认后再停止。"
  else
    log "旧镜像主实例已恢复；请复核查询和任务恢复情况，如有临时query-only容器，确认后再停止。"
  fi
}

print_main_status() {
  docker ps --filter "name=^/${CONTAINER_NAME}$"
  docker inspect "${CONTAINER_NAME}" \
    --format 'status={{.State.Status}} running={{.State.Running}} restarting={{.State.Restarting}} restartCount={{.RestartCount}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} image={{.Config.Image}}'
}

on_exit() {
  local exit_code="$1"
  trap - EXIT
  set +e
  if [ "${exit_code}" -ne 0 ] || [ "${DEPLOY_SUCCEEDED}" -eq 0 ]; then
    log "部署未完成，执行退出保护。"
    if [ "${MAIN_REPLACED}" -eq 0 ]; then
      cancel_drain_best_effort
      stop_query_handoff_best_effort
    else
      rollback_main_best_effort \
        || log "ERROR: 自动回滚失败；临时query-only容器尽量保持运行，请立即人工处理。"
    fi
  fi
  release_deploy_lock
}

main() {
  trap 'on_exit $?' EXIT
  trap 'exit 130' INT TERM
  log "开始部署测试环境es-server。mode=${DEPLOY_MODE}, image=${IMAGE}, compose=${COMPOSE_FILE}"
  acquire_deploy_lock
  ensure_prerequisites
  capture_stable_image

  if [ -n "${OLD_IMAGE_ID}" ] && [ "${DEPLOY_MODE}" = "safe" ]; then
    preflight_stable_capability
    validate_safe_handoff_config
    prepare_rollback_image
    start_query_handoff
    pull_target_image
    request_drain
    wait_for_drain
  else
    if [ -n "${OLD_IMAGE_ID}" ]; then
      log "WARN: legacy模式执行首次版本A升级，本次存在受控查询维护窗口。"
      prepare_rollback_image
    else
      log "未发现旧主容器，按首次安装启动。"
    fi
    pull_target_image
  fi

  if [ -n "${OLD_IMAGE_ID}" ]; then
    stop_and_remove_main
    if [ "${DEPLOY_MODE}" = "safe" ]; then
      run_nginx_smoke "query-only接管"
    fi
  else
    MAIN_REPLACED=1
  fi

  start_main_with_tag "${IMAGE_TAG}"
  wait_until_ready "${CONTAINER_NAME}" "${MAIN_BASE_URL}" "all" \
    || fail "新版主实例未就绪，进入退出保护。"
  verify_new_sync_runtime
  if [ -n "${OLD_IMAGE_ID}" ] && [ "${DEPLOY_MODE}" = "safe" ]; then
    run_nginx_smoke "新主实例恢复后"
  fi
  print_main_status
  stop_query_handoff

  # 所有严格验证和临时容器清理完成后才解除退出保护。rollback标签故意保留供事后回滚。
  DEPLOY_SUCCEEDED=1
  log "测试环境部署完成。原稳定镜像：${RESTORE_IMAGE:-无旧镜像}；安全回滚标签：${ROLLBACK_IMAGE:-未创建（复用原版本标签）}"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
