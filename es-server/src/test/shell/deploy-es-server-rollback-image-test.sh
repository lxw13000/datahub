#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

verify_script() {
  local deploy_script="$1"

  (
    # shellcheck source=/dev/null
    source "${deploy_script}"
    IMAGE_NAME="registry/sano-es-server"
    IMAGE_TAG="v1.0.8"
    IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"
    OLD_IMAGE_ID="sha256:old"
    OLD_IMAGE_REFERENCE="${IMAGE_NAME}:v1.0.7"
    MOCK_REFERENCED_IMAGE_ID="${OLD_IMAGE_ID}"
    MOCK_REPO_TAGS=""
    log() { :; }
    docker() {
      if [ "$1 $2" = "image inspect" ]; then
        if [[ "${5:-}" == *RepoTags* ]]; then
          printf '%s\n' "${MOCK_REPO_TAGS}"
        else
          printf '%s\n' "${MOCK_REFERENCED_IMAGE_ID}"
        fi
      fi
      return 0
    }

    prepare_rollback_image
    [ "${RESTORE_TAG}" = "v1.0.7" ] && [ "${RESTORE_IMAGE}" = "${IMAGE_NAME}:v1.0.7" ] || {
      printf 'ASSERT FAILED: original version tag was not preserved: %s\n' "${RESTORE_IMAGE}" >&2
      exit 1
    }
    [ -z "${ROLLBACK_TAG}" ] && [ -z "${ROLLBACK_IMAGE}" ] || {
      printf 'ASSERT FAILED: trusted version tag created redundant safety tag: %s\n' "${ROLLBACK_TAG}" >&2
      exit 1
    }

    # 兼容旧脚本产生的纯时间戳标签：从同一镜像的RepoTags恢复原版本。
    OLD_IMAGE_REFERENCE="${IMAGE_NAME}:test-rollback-20260717183250"
    MOCK_REPO_TAGS="${OLD_IMAGE_REFERENCE}"$'\n'"${IMAGE_NAME}:v1.0.7"
    prepare_rollback_image
    [ "${SOURCE_VERSION_TAG}" = "v1.0.7" ] \
      && [ "${RESTORE_IMAGE}" = "${IMAGE_NAME}:v1.0.7" ] \
      && [ -z "${ROLLBACK_TAG}" ] || {
      printf 'ASSERT FAILED: historical rollback tag did not recover v1.0.7\n' >&2
      exit 1
    }

    # 原标签与本次目标标签相同时可能被pull覆盖，必须使用刚创建的安全标签。
    OLD_IMAGE_REFERENCE="${IMAGE}"
    MOCK_REPO_TAGS=""
    prepare_rollback_image
    [ "${RESTORE_TAG}" = "${ROLLBACK_TAG}" ] && [ "${RESTORE_IMAGE}" = "${ROLLBACK_IMAGE}" ] || {
      printf 'ASSERT FAILED: target tag should use safety rollback image\n' >&2
      exit 1
    }
    [[ "${ROLLBACK_TAG}" == *rollback-v1.0.8-* ]] || {
      printf 'ASSERT FAILED: target safety tag lost source version: %s\n' "${ROLLBACK_TAG}" >&2
      exit 1
    }

    # 原标签已改指其他镜像时不能伪装成旧版本，仍须使用安全标签。
    OLD_IMAGE_REFERENCE="${IMAGE_NAME}:v1.0.7"
    MOCK_REFERENCED_IMAGE_ID="sha256:changed"
    prepare_rollback_image
    [ "${RESTORE_TAG}" = "${ROLLBACK_TAG}" ] && [ "${RESTORE_IMAGE}" = "${ROLLBACK_IMAGE}" ] || {
      printf 'ASSERT FAILED: drifted version tag should use safety rollback image\n' >&2
      exit 1
    }
  )
}

verify_script "${PROJECT_DIR}/deploy-es-server.sh"
verify_script "${PROJECT_DIR}/deploy-es-server-test.sh"

printf 'deploy-es-server rollback image tests passed\n'
