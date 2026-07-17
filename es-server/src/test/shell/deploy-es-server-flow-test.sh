#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# 默认验证正式脚本；通过DEPLOY_SCRIPT_OVERRIDE可复用同一组流程用例验证独立测试脚本。
DEPLOY_SCRIPT="${DEPLOY_SCRIPT_OVERRIDE:-${PROJECT_DIR}/deploy-es-server.sh}"
EXPECTED_CONTAINER_NAME="${EXPECTED_CONTAINER_NAME:-sano-es-server}"

assert_contains() {
  local file="$1"
  local expected="$2"
  grep -Fxq "${expected}" "${file}" || {
    printf 'ASSERT FAILED: missing [%s]\n--- events ---\n' "${expected}" >&2
    cat "${file}" >&2
    return 1
  }
}

assert_absent() {
  local file="$1"
  local unexpected="$2"
  ! grep -Fxq "${unexpected}" "${file}" || {
    printf 'ASSERT FAILED: unexpected [%s]\n--- events ---\n' "${unexpected}" >&2
    cat "${file}" >&2
    return 1
  }
}

assert_exact() {
  local file="$1"
  local expected="$2"
  local actual
  actual="$(paste -sd, "${file}")"
  [ "${actual}" = "${expected}" ] || {
    printf 'ASSERT FAILED\nexpected: %s\nactual:   %s\n' "${expected}" "${actual}" >&2
    return 1
  }
}

run_case() {
  local scenario="$1"
  local event_file="$2"
  EVENT_FILE="${event_file}" bash "${BASH_SOURCE[0]}" --case "${scenario}"
}

execute_case() {
  local scenario="$1"
  # source后覆盖外部动作，离线测试不会调用真实Docker、curl或Nginx。
  # shellcheck source=/dev/null
  source "${DEPLOY_SCRIPT}"

  DEPLOY_MODE="safe"
  [ "${scenario}" != "legacy" ] || DEPLOY_MODE="legacy"
  IMAGE_TAG="new"
  IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"
  log() { :; }
  event() { printf '%s\n' "$1" >>"${EVENT_FILE}"; }
  acquire_deploy_lock() { LOCK_ACQUIRED=1; event lock; }
  release_deploy_lock() { LOCK_ACQUIRED=0; event unlock; }
  ensure_prerequisites() { event prerequisites; }
  capture_stable_image() {
    OLD_IMAGE_ID="sha256:old"
    event capture
  }
  prepare_rollback_image() {
    ROLLBACK_TAG="rollback-test"
    ROLLBACK_IMAGE="${IMAGE_NAME}:${ROLLBACK_TAG}"
    RESTORE_TAG="${ROLLBACK_TAG}"
    RESTORE_IMAGE="${ROLLBACK_IMAGE}"
    event prepare-rollback
  }
  preflight_stable_capability() {
    event preflight
    [ "${scenario}" != "preflight-failure" ]
  }
  validate_safe_handoff_config() { event handoff-config; }
  start_query_handoff() { QUERY_STARTED=1; event start-query; }
  pull_target_image() { event pull; }
  request_drain() { DRAIN_ACTIVE=1; DRAIN_OPERATION_ID="op-1"; event drain; }
  wait_for_drain() {
    event "wait-drain${scenario#success}"
    [ "${scenario}" != "pre-replace-failure" ]
  }
  stop_and_remove_main() { MAIN_REPLACED=1; DRAIN_ACTIVE=0; event stop-main; }
  run_nginx_smoke() { event "smoke:$1"; }
  start_main_with_tag() { event "start-main:$1"; }
  wait_until_ready() {
    event "ready:$1"
    if [ "${scenario}" = "post-replace-failure" ] && [ "$1" = "${CONTAINER_NAME}" ]; then
      return 1
    fi
  }
  verify_new_sync_runtime() { event verify-sync; }
  print_main_status() { event main-status; }
  stop_query_handoff() {
    [ "${QUERY_STARTED}" -eq 1 ] || return 0
    QUERY_STARTED=0
    event stop-query
  }
  cancel_drain_best_effort() {
    [ "${DRAIN_ACTIVE}" -eq 1 ] || return 0
    DRAIN_ACTIVE=0
    event cancel
  }
  stop_query_handoff_best_effort() {
    [ "${QUERY_STARTED}" -eq 1 ] || return 0
    QUERY_STARTED=0
    event stop-query-best-effort
  }
  rollback_main_best_effort() { event rollback; }

  main
}

if [ "${1:-}" = "--case" ]; then
  execute_case "$2"
  exit 0
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

success_events="${tmp_dir}/success.events"
run_case success "${success_events}"
assert_exact "${success_events}" \
  "lock,prerequisites,capture,preflight,handoff-config,prepare-rollback,start-query,pull,drain,wait-drain,stop-main,smoke:query-only接管,start-main:new,ready:${EXPECTED_CONTAINER_NAME},verify-sync,smoke:新主实例恢复后,main-status,stop-query,unlock"

errors_events="${tmp_dir}/errors.events"
run_case success-with-errors "${errors_events}"
assert_contains "${errors_events}" "wait-drain-with-errors"
assert_contains "${errors_events}" stop-main
assert_absent "${errors_events}" cancel
assert_absent "${errors_events}" rollback

preflight_events="${tmp_dir}/preflight.events"
if run_case preflight-failure "${preflight_events}"; then
  printf 'ASSERT FAILED: capability preflight failure returned success\n' >&2
  exit 1
fi
assert_contains "${preflight_events}" preflight
assert_absent "${preflight_events}" prepare-rollback
assert_absent "${preflight_events}" start-query
assert_absent "${preflight_events}" drain

legacy_events="${tmp_dir}/legacy.events"
run_case legacy "${legacy_events}"
assert_exact "${legacy_events}" \
  "lock,prerequisites,capture,prepare-rollback,pull,stop-main,start-main:new,ready:${EXPECTED_CONTAINER_NAME},verify-sync,main-status,unlock"

pre_replace_events="${tmp_dir}/pre-replace.events"
if run_case pre-replace-failure "${pre_replace_events}"; then
  printf 'ASSERT FAILED: pre-replace failure returned success\n' >&2
  exit 1
fi
assert_contains "${pre_replace_events}" cancel
assert_contains "${pre_replace_events}" stop-query-best-effort
assert_absent "${pre_replace_events}" stop-main
assert_absent "${pre_replace_events}" rollback

post_replace_events="${tmp_dir}/post-replace.events"
if run_case post-replace-failure "${post_replace_events}"; then
  printf 'ASSERT FAILED: post-replace failure returned success\n' >&2
  exit 1
fi
assert_contains "${post_replace_events}" rollback
assert_absent "${post_replace_events}" cancel
assert_absent "${post_replace_events}" stop-query

if [ -n "${DEPLOY_SCRIPT_OVERRIDE:-}" ]; then
  # 独立测试脚本在legacy模式下必须能够接管已停止的旧测试容器并保留回滚镜像ID。
  DEPLOY_MODE=legacy
  # shellcheck source=/dev/null
  source "${DEPLOY_SCRIPT}"
  container_exists() { return 0; }
  container_running() { return 1; }
  inspect_field() { printf '%s\n' 'sha256:stopped-test-image'; }
  log() { :; }
  capture_stable_image
  [ "${OLD_IMAGE_ID}" = "sha256:stopped-test-image" ] || {
    printf 'ASSERT FAILED: legacy did not capture stopped test image\n' >&2
    exit 1
  }
fi

printf 'deploy-es-server flow tests passed\n'
