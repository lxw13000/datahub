#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
DATAHUB_DIR="$(cd "${PROJECT_DIR}/.." && pwd)"

assert_contains() {
  local file="$1"
  local expected="$2"
  grep -Fq "${expected}" "${file}" || {
    printf 'ASSERT FAILED: %s missing [%s]\n' "${file}" "${expected}" >&2
    exit 1
  }
}

assert_absent() {
  local file="$1"
  local unexpected="$2"
  ! grep -Fq "${unexpected}" "${file}" || {
    printf 'ASSERT FAILED: %s contains test/prod collision [%s]\n' "${file}" "${unexpected}" >&2
    exit 1
  }
}

PROD_DEPLOY="${PROJECT_DIR}/deploy-es-server.sh"
TEST_DEPLOY="${PROJECT_DIR}/deploy-es-server-test.sh"
PROD_COMPOSE="${PROJECT_DIR}/docker-compose.yml"
TEST_COMPOSE="${PROJECT_DIR}/docker-compose-test.yml"
PROD_NGINX="${PROJECT_DIR}/nginx/es-server.conf.example"
TEST_NGINX="${PROJECT_DIR}/nginx/es-server-test.conf.example"

# 正式与测试部署脚本必须固定使用不同的容器、端口、内部入口和锁目录。
assert_contains "${PROD_DEPLOY}" 'CONTAINER_NAME="sano-es-server"'
assert_contains "${PROD_DEPLOY}" 'QUERY_CONTAINER_NAME="sano-es-server-query"'
assert_contains "${PROD_DEPLOY}" 'SERVER_PORT="8002"'
assert_contains "${PROD_DEPLOY}" 'QUERY_PORT="8003"'
assert_contains "${PROD_DEPLOY}" 'http://127.0.0.1:8102'
assert_contains "${PROD_DEPLOY}" '/tmp/sano-es-server-deploy.lock'
assert_contains "${PROD_DEPLOY}" 'SYNC_API_TOKEN="${SYNC_API_TOKEN:-sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F}"'
assert_absent "${PROD_DEPLOY}" 'SERVER_PORT="9003"'
assert_absent "${PROD_DEPLOY}" 'QUERY_PORT="9004"'

assert_contains "${TEST_DEPLOY}" 'CONTAINER_NAME="sano-es-server-test"'
assert_contains "${TEST_DEPLOY}" 'QUERY_CONTAINER_NAME="sano-es-server-test-query"'
assert_contains "${TEST_DEPLOY}" 'SERVER_PORT="9003"'
assert_contains "${TEST_DEPLOY}" 'QUERY_PORT="9004"'
assert_contains "${TEST_DEPLOY}" 'http://127.0.0.1:9103'
assert_contains "${TEST_DEPLOY}" '/tmp/sano-es-server-test-deploy.lock'
assert_contains "${TEST_DEPLOY}" 'SYNC_API_TOKEN="${SYNC_API_TOKEN:-sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F}"'

# Compose向宿主机全部网络接口发布各自后端端口，版本A的all/query均显式关闭Polling。
assert_contains "${PROD_COMPOSE}" '${SERVER_PORT:-8002}:${SERVER_PORT:-8002}'
assert_contains "${PROD_COMPOSE}" '${QUERY_PORT:-8003}:${QUERY_PORT:-8003}'
assert_contains "${PROD_COMPOSE}" 'SANO_ES_POLLING_ENABLED: "false"'
assert_contains "${TEST_COMPOSE}" '${SERVER_PORT:-9003}:${SERVER_PORT:-9003}'
assert_contains "${TEST_COMPOSE}" '${QUERY_PORT:-9004}:${QUERY_PORT:-9004}'
assert_absent "${PROD_COMPOSE}" '127.0.0.1:${SERVER_PORT:-8002}'
assert_absent "${PROD_COMPOSE}" '127.0.0.1:${QUERY_PORT:-8003}'
assert_absent "${TEST_COMPOSE}" '127.0.0.1:${SERVER_PORT:-9003}'
assert_absent "${TEST_COMPOSE}" '127.0.0.1:${QUERY_PORT:-9004}'

# 两份Nginx配置允许共享80端口，但域名、upstream、内部端口和Docker后端必须完全分离。
assert_contains "${PROD_NGINX}" 'server 127.0.0.1:8002 max_fails=1 fail_timeout=5s;'
assert_contains "${PROD_NGINX}" 'server 127.0.0.1:8003 backup max_fails=1 fail_timeout=5s;'
assert_contains "${PROD_NGINX}" 'listen 8102;'
assert_contains "${PROD_NGINX}" 'server_name es-server.fofunlive.net;'
assert_contains "${PROD_NGINX}" 'server_name es.fofunlive.net;'
assert_contains "${PROD_NGINX}" 'proxy_pass http://127.0.0.1:9201;'
assert_absent "${PROD_NGINX}" '127.0.0.1:9003'
assert_absent "${PROD_NGINX}" 'listen 9103;'
assert_absent "${PROD_NGINX}" 'es-server-test.fofunlive.net'

assert_contains "${TEST_NGINX}" 'server 127.0.0.1:9003 max_fails=1 fail_timeout=5s;'
assert_contains "${TEST_NGINX}" 'server 127.0.0.1:9004 backup max_fails=1 fail_timeout=5s;'
assert_contains "${TEST_NGINX}" 'listen 9103;'
assert_contains "${TEST_NGINX}" 'server_name es-server-test.fofunlive.net;'
assert_contains "${TEST_NGINX}" 'server_name es-test.fofunlive.net;'
assert_contains "${TEST_NGINX}" 'proxy_pass http://127.0.0.1:9211;'

# 同机正式与测试Elasticsearch分别占用9201和9211。
assert_contains "${DATAHUB_DIR}/elasticsearch8/docker-compose-elasticsearch.yml" '"9201:9200"'
assert_contains "${DATAHUB_DIR}/elasticsearch8-test/docker-compose.yml" '"9211:9200"'

printf 'deployment environment isolation tests passed\n'
