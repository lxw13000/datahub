#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

# 强制脚本走独立docker-compose分支，模拟服务器上的Compose启动行为。
mkdir -p "${TMP_DIR}/bin"
cat >"${TMP_DIR}/bin/docker" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
cat >"${TMP_DIR}/bin/docker-compose" <<'EOF'
#!/usr/bin/env bash
case "${FAKE_COMPOSE_MODE:-success}" in
  success)
    exit 0
    ;;
  hang)
    trap 'exit 0' TERM
    while true; do
      sleep 1
    done
    ;;
  fail)
    exit 17
    ;;
esac
EOF
chmod +x "${TMP_DIR}/bin/docker" "${TMP_DIR}/bin/docker-compose"
export PATH="${TMP_DIR}/bin:${PATH}"

verify_script() {
  local deploy_script="$1"

  # 每个脚本在独立子Shell中加载，避免正式与测试脚本的全局变量互相覆盖。
  (
    # shellcheck source=/dev/null
    source "${deploy_script}"
    COMPOSE_UP_TIMEOUT="0.2"
    CONTAINER_STATE="running"
    log() { :; }
    container_running() { [ "${CONTAINER_STATE}" = "running" ]; }

    FAKE_COMPOSE_MODE=success compose_up_detached target-container up -d service
    FAKE_COMPOSE_MODE=hang compose_up_detached target-container up -d service 2>/dev/null

    CONTAINER_STATE="stopped"
    set +e
    FAKE_COMPOSE_MODE=hang compose_up_detached target-container up -d service 2>/dev/null
    exit_code=$?
    set -e
    [ "${exit_code}" -eq 124 ] || {
      printf 'ASSERT FAILED: timeout without running container returned %s\n' "${exit_code}" >&2
      exit 1
    }

    CONTAINER_STATE="running"
    set +e
    FAKE_COMPOSE_MODE=fail compose_up_detached target-container up -d service
    exit_code=$?
    set -e
    [ "${exit_code}" -eq 17 ] || {
      printf 'ASSERT FAILED: compose failure was hidden, exit=%s\n' "${exit_code}" >&2
      exit 1
    }
  )
}

verify_script "${PROJECT_DIR}/deploy-es-server.sh"
verify_script "${PROJECT_DIR}/deploy-es-server-test.sh"

printf 'deploy-es-server compose up timeout tests passed\n'
