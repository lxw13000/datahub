# es-server 完整部署文档

> 本文以当前源码、两份 Compose、两份独立部署脚本和 Nginx 示例为准。当前版本只包含查询与 T+1 同步，已经移除 MySQL Polling、checkpoint 和 Polling Worker。

## 1. 当前部署能力

- 常驻 `all` 实例：开放查询，并按总开关执行 T+1。
- 临时 `query` 实例：只在安全升级期间承接查询，不执行 T+1。
- Nginx 同时配置主后端和 backup 后端，对外域名及内部调用地址不随升级改变。
- `sano_import_task` 是当前唯一的同步内部索引。
- `/ready` 严格检查业务 Alias 和 T+1 任务索引。
- `/internal/sync/drain` 在升级前停止新任务，并等待现有任务到达安全断点。

当前不再使用：

- `SANO_ES_POLLING_ENABLED`；
- `sano_sync_polling_checkpoint`；
- Polling 初始化、暂停、恢复、修复和状态接口；
- Polling 专项日志。

历史环境遗留的 `sano_sync_polling_checkpoint` 不会被新版本读取或自动删除。确认不再回滚旧 Polling 版本后，可另行人工清理。

## 2. 端口和域名

测试与正式环境在同一台服务器，端口不得交叉使用。

| 环境 | 常驻 all | 临时 query | 内部 Nginx | Elasticsearch |
| --- | ---: | ---: | ---: | ---: |
| 正式 | 8002 | 8003 | 8102 | 9201 |
| 测试 | 9003 | 9004 | 9103 | 9211 |

| 环境 | es-server 外部域名 | ES 外部域名 | 内部业务调用地址 |
| --- | --- | --- | --- |
| 正式 | `http://es-server.fofunlive.net` | `http://es.fofunlive.net` | `http://服务器内网IP:8102` |
| 测试 | `http://es-server-test.fofunlive.net` | `http://es-test.fofunlive.net` | `http://服务器内网IP:9103` |

业务系统不要直接调用 Docker 后端端口。外部继续使用域名，内部统一使用 Nginx 的 8102 或 9103。

## 3. 部署文件

正式环境：

- `docker-compose.yml`
- `deploy-es-server.sh`
- `nginx/es-server.conf.example`
- `src/main/resources/application-prod.yml`

测试环境：

- `docker-compose-test.yml`
- `deploy-es-server-test.sh`
- `nginx/es-server-test.conf.example`
- `src/main/resources/application-test.yml`

测试与正式部署脚本互相独立，不会调用或操作另一环境。

## 4. 服务器前提

```bash
docker --version
docker compose version || docker-compose --version
curl --version
jq --version
timeout --version
nginx -v
```

部署脚本会自动创建外部 Docker 网络；也可提前创建：

```bash
docker network inspect sano-net >/dev/null 2>&1 || docker network create sano-net
```

ES 容器必须加入 `sano-net`。正式 Compose 默认访问 `elasticsearch8:9200`，测试 Compose 默认访问 `elasticsearch8-test:9200`。实际容器名不同，应使用 `ES_URIS` 覆盖。

## 5. 构建与推送镜像

Windows 构建机：

```bat
cd /d C:\work\opts\sano\code\datahub\es-server
docker-build.bat v1.2.0 --push
```

Linux/macOS 构建机：

```bash
cd /path/to/datahub/es-server
chmod +x docker-build.sh
./docker-build.sh v1.2.0 --push
```

脚本会执行 Maven 打包，构建并推送 `linux/amd64`、`linux/arm64` 镜像，同时更新明确版本标签和 `latest`。部署时必须使用明确版本号，不要用 `latest` 作为正式回滚依据。

## 6. 上传文件

建议目录：

```text
/home/ec2-user/datahub/es-server/       # 正式
/home/ec2-user/datahub-test/es-server/  # 测试
```

正式目录至少需要 `docker-compose.yml`、`deploy-es-server.sh`；测试目录至少需要 `docker-compose-test.yml`、`deploy-es-server-test.sh`。

```bash
chmod +x /home/ec2-user/datahub/es-server/deploy-es-server.sh
chmod +x /home/ec2-user/datahub-test/es-server/deploy-es-server-test.sh
```

## 7. Nginx 配置

测试环境使用 `nginx/es-server-test.conf.example`，核心 upstream 为：

```nginx
upstream es_server_test_query_backend {
    server 127.0.0.1:9003 max_fails=1 fail_timeout=5s;
    server 127.0.0.1:9004 backup max_fails=1 fail_timeout=5s;
    keepalive 32;
}
```

正式环境使用 `nginx/es-server.conf.example`，核心 upstream 为：

```nginx
upstream es_server_query_backend {
    server 127.0.0.1:8002 max_fails=1 fail_timeout=5s;
    server 127.0.0.1:8003 backup max_fails=1 fail_timeout=5s;
    keepalive 32;
}
```

完整示例还分别包含外部 es-server 域名、内部 Nginx 端口和 ES 域名。安装后检查并加载：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

`NGINX_HANDOFF_PRECONFIGURED=true` 表示上述 backup upstream 已经配置并生效。安全升级命令中应保留该参数。

## 8. 全新 ES 的第一次部署

### 8.1 首次不能直接执行部署脚本的原因

全新的 ES 没有业务 Alias，也没有 `sano_import_task`。应用可以启动且 `/health` 为 200，但 `/ready` 会返回 503：

- 第一张启用表 Alias 不存在，查询未就绪；
- `sano_import_task` 不存在，同步未就绪。

部署脚本会等待严格 `/ready`，因此第一次必须直接用 Compose 启动主实例，创建任务索引，并完成至少第一张业务表的一次 T+1 导入。完成后，后续版本都使用安全升级脚本。

### 8.2 测试环境首次部署

准备目录：

```bash
cd /home/ec2-user/datahub-test/es-server
docker network inspect sano-net >/dev/null 2>&1 || docker network create sano-net
mkdir -p logs-test logs-test-query
sudo chown -R 10001:10001 logs-test logs-test-query
sudo chmod 755 logs-test logs-test-query
```

拉取镜像并直接启动常驻实例：

```bash
export ES_SERVER_IMAGE_TAG=v1.2.0
export SANO_SERVER_MODE=all
export SANO_ES_IMPORT_T_PLUS_ONE_ENABLED=true
docker pull lxw13000/sano-es-server:${ES_SERVER_IMAGE_TAG}
docker compose -f docker-compose-test.yml -p sano-es-server-test \
  up -d --no-build --force-recreate es-server
```

确认进程启动：

```bash
docker ps --filter 'name=^/sano-es-server-test$'
docker logs --tail 200 sano-es-server-test
curl -sS http://127.0.0.1:9003/health | jq .
```

从测试部署脚本读取固定 Token：

```bash
TOKEN=$(sed -n 's/^SYNC_API_TOKEN="${SYNC_API_TOKEN:-\([^}]*\)}"$/\1/p' deploy-es-server-test.sh)
test -n "${TOKEN}" || { echo '未能从部署脚本读取Token'; exit 1; }
```

创建当前唯一的同步内部索引：

```bash
curl -sS -H "token: ${TOKEN}" \
  'http://127.0.0.1:9003/import/createImportTaskIndex' | jq .
```

选择一个确认有业务数据的日期，提交所有启用表任务：

```bash
INIT_DATE=20260917
curl -sS -H "token: ${TOKEN}" \
  "http://127.0.0.1:9003/import/importAppointDay?date=${INIT_DATE}" | jq .
```

如果只先初始化 `/ready` 检查的第一张表：

```bash
INIT_DATE=20260917
curl -sS -H "token: ${TOKEN}" \
  "http://127.0.0.1:9003/import/importTableDateRange?tableName=sano_wallet_coin_record&startDate=${INIT_DATE}&endDate=${INIT_DATE}" | jq .
```

必须选择该表确实有数据的日期。无数据任务会正常完成，但不会创建空索引和 Alias。

观察导入：

```bash
docker logs -f --tail 200 sano-es-server-test
```

导入完成后验证：

```bash
curl -sS -H "token: ${TOKEN}" \
  -o /tmp/es-server-test-ready.json \
  -w 'HTTP=%{http_code}\n' \
  http://127.0.0.1:9003/ready
jq . /tmp/es-server-test-ready.json

curl -sS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/internal/sync/drain/status | jq .
```

预期 `/ready` 的 `ready=true`、`serviceMode=ALL`、`queryReady=true`、`syncReady=true`。不需要创建 Polling checkpoint，也不需要为了初始化 checkpoint 重启容器。

### 8.3 正式环境首次部署

```bash
cd /home/ec2-user/datahub/es-server
docker network inspect sano-net >/dev/null 2>&1 || docker network create sano-net
mkdir -p logs logs-query
sudo chown -R 10001:10001 logs logs-query
sudo chmod 755 logs logs-query

export ES_SERVER_IMAGE_TAG=v1.2.0
export SANO_SERVER_MODE=all
export SANO_ES_IMPORT_T_PLUS_ONE_ENABLED=true
docker pull lxw13000/sano-es-server:${ES_SERVER_IMAGE_TAG}
docker compose -f docker-compose.yml -p sano-es-server \
  up -d --no-build --force-recreate es-server

docker ps --filter 'name=^/sano-es-server$'
docker logs --tail 200 sano-es-server
curl -sS http://127.0.0.1:8002/health | jq .

TOKEN=$(sed -n 's/^SYNC_API_TOKEN="${SYNC_API_TOKEN:-\([^}]*\)}"$/\1/p' deploy-es-server.sh)
test -n "${TOKEN}" || { echo '未能从部署脚本读取Token'; exit 1; }

curl -sS -H "token: ${TOKEN}" \
  'http://127.0.0.1:8002/import/createImportTaskIndex' | jq .

INIT_DATE=20260917
curl -sS -H "token: ${TOKEN}" \
  "http://127.0.0.1:8002/import/importAppointDay?date=${INIT_DATE}" | jq .

curl -sS -H "token: ${TOKEN}" \
  -o /tmp/es-server-ready.json \
  -w 'HTTP=%{http_code}\n' \
  http://127.0.0.1:8002/ready
jq . /tmp/es-server-ready.json

curl -sS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/internal/sync/drain/status | jq .
```

正式数据初始化前，应再次确认 `application-prod.yml` 中启用表、MySQL、ES 和通知配置，并选择适合的首次导入日期。

## 9. 已有环境安全升级

安全升级顺序：

```text
验证旧主/ready和drain协议
→ 启动旧镜像query临时实例
→ query严格就绪
→ 拉取新镜像
→ 请求旧主drain
→ Reader停止新读取，已读队列和Bulk完成
→ 保存安全断点或任务终态
→ drain完成
→ 替换all实例
→ 验证新主/ready和协调器RUNNING
→ 查询冒烟
→ 删除query临时实例
```

测试环境完整命令：

```bash
cd /home/ec2-user/datahub-test/es-server
ES_SERVER_IMAGE_TAG=v1.2.1 \
DEPLOY_MODE=safe \
NGINX_HANDOFF_PRECONFIGURED=true \
./deploy-es-server-test.sh
```

一行执行同样有效：

```bash
ES_SERVER_IMAGE_TAG=v1.2.1 DEPLOY_MODE=safe NGINX_HANDOFF_PRECONFIGURED=true ./deploy-es-server-test.sh
```

正式环境完整命令：

```bash
cd /home/ec2-user/datahub/es-server
ES_SERVER_IMAGE_TAG=v1.2.1 \
DEPLOY_MODE=safe \
NGINX_HANDOFF_PRECONFIGURED=true \
./deploy-es-server.sh
```

一行写法：

```bash
ES_SERVER_IMAGE_TAG=v1.2.1 DEPLOY_MODE=safe NGINX_HANDOFF_PRECONFIGURED=true ./deploy-es-server.sh
```

`SYNC_API_TOKEN` 无需传入；两份部署脚本已经内置与服务一致的固定 Token。

若失败发生在替换主实例前，脚本会取消 drain 并停止临时 query；若替换后失败，会使用旧镜像恢复主实例，并尽量保留 query 继续承接查询。

## 10. legacy 模式

`legacy` 仅用于旧镜像尚不支持 `/ready`、统一 drain 和 query 接管时的首次协议升级，期间存在查询维护窗口：

```bash
ES_SERVER_IMAGE_TAG=v1.2.0 DEPLOY_MODE=legacy NGINX_HANDOFF_PRECONFIGURED=true ./deploy-es-server-test.sh
```

当前版本已经具备安全协议后，正常升级必须使用 `safe`。全新空 ES 也不要用 `legacy` 脚本初始化，因为它仍会等待严格 `/ready`；应按第 8 节直接使用 Compose。

## 11. 升级检查

测试环境：

```bash
cd /home/ec2-user/datahub-test/es-server
TOKEN=$(sed -n 's/^SYNC_API_TOKEN="${SYNC_API_TOKEN:-\([^}]*\)}"$/\1/p' deploy-es-server-test.sh)

docker ps --filter 'name=sano-es-server-test'
docker inspect sano-es-server-test \
  --format 'image={{.Config.Image}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart={{.RestartCount}}'
curl -sS -H "token: ${TOKEN}" http://127.0.0.1:9003/ready | jq .
curl -sS -H "token: ${TOKEN}" http://127.0.0.1:9003/internal/sync/drain/status | jq .
```

正式环境：

```bash
cd /home/ec2-user/datahub/es-server
TOKEN=$(sed -n 's/^SYNC_API_TOKEN="${SYNC_API_TOKEN:-\([^}]*\)}"$/\1/p' deploy-es-server.sh)

docker ps --filter 'name=sano-es-server'
docker inspect sano-es-server \
  --format 'image={{.Config.Image}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart={{.RestartCount}}'
curl -sS -H "token: ${TOKEN}" http://127.0.0.1:8002/ready | jq .
curl -sS -H "token: ${TOKEN}" http://127.0.0.1:8002/internal/sync/drain/status | jq .
```

正常主实例应满足：

- `serviceMode=ALL`；
- `/ready` 的 `ready=true`；
- drain/status 的 `coordinatorState=RUNNING`；
- 容器 `restartCount=0`；
- 临时 query 容器已删除。

## 12. 人工 drain 与恢复

通常由脚本自动完成。排障时可人工操作：

```bash
RESPONSE=$(curl -sS -X POST -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/internal/sync/drain)
echo "${RESPONSE}" | jq .
OPERATION_ID=$(echo "${RESPONSE}" | jq -r '.data.operationId')

curl -sS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/internal/sync/drain/status | jq .

curl -sS -X POST -H "token: ${TOKEN}" \
  "http://127.0.0.1:9003/internal/sync/drain/cancel?operationId=${OPERATION_ID}" | jq .
```

正式环境将 9003 替换为 8002。取消操作必须携带本次 drain 的 operationId。

## 13. 回滚镜像

脚本优先复用旧容器的明确版本标签。例如 `v1.2.0` 升到 `v1.2.1` 失败时恢复 `v1.2.0`，不会无条件复制镜像。

只有原标签不可靠时才创建时间戳安全标签，例如：旧容器使用 `latest`、历史 rollback 标签、标签即将被新镜像覆盖，或标签已经漂移到其他镜像 ID。

```bash
docker images lxw13000/sano-es-server
docker inspect sano-es-server --format '{{.Config.Image}} {{.Image}}'
```

升级过程中不要删除旧版本镜像。新版本稳定且没有容器引用后，再按明确标签人工删除。

## 14. 日志

| 环境/实例 | 宿主机目录 | 容器目录 |
| --- | --- | --- |
| 正式 all | `./logs` | `/app/logs/es-server` |
| 正式 query | `./logs-query` | `/app/logs/es-server-query` |
| 测试 all | `./logs-test` | `/app/logs/es-server` |
| 测试 query | `./logs-test-query` | `/app/logs/es-server-query` |

日志按容器当前日期进入 `yyyyMMdd` 子目录：

```bash
docker logs --tail 200 sano-es-server
docker logs --tail 200 sano-es-server-test
grep -R "ES-TPlusOne" ./logs/$(date +%Y%m%d)/
grep -R "ES-TPlusOne" ./logs-test/$(date +%Y%m%d)/
```

当前不再生成 Polling 专项日志。

## 15. 常用命令

测试环境：

```bash
cd /home/ec2-user/datahub-test/es-server
docker compose -f docker-compose-test.yml -p sano-es-server-test ps
docker compose -f docker-compose-test.yml -p sano-es-server-test logs --tail 200 es-server
docker compose -f docker-compose-test.yml -p sano-es-server-test stop es-server
docker compose -f docker-compose-test.yml -p sano-es-server-test start es-server
```

正式环境：

```bash
cd /home/ec2-user/datahub/es-server
docker compose -f docker-compose.yml -p sano-es-server ps
docker compose -f docker-compose.yml -p sano-es-server logs --tail 200 es-server
docker compose -f docker-compose.yml -p sano-es-server stop es-server
docker compose -f docker-compose.yml -p sano-es-server start es-server
```

查看唯一同步内部索引：

```bash
curl -u 'elastic:实际密码' 'http://127.0.0.1:9211/_cat/indices/sano_import_task?v'
curl -u 'elastic:实际密码' 'http://127.0.0.1:9201/_cat/indices/sano_import_task?v'
```

## 16. 常见故障

### 16.1 `/health` 成功但 `/ready` 为 503

- `QUERY_ES_UNAVAILABLE`：ES、权限或第一张业务 Alias 不可查询；
- `T_PLUS_ONE_TASK_INDEX_MISSING`：未创建 `sano_import_task`；
- `T_PLUS_ONE_UNAVAILABLE`：任务索引检查异常。

全新 ES 按第 8 节初始化，不要创建 Polling checkpoint。

### 16.2 safe 模式拒绝升级

常见原因包括：旧主未运行或未就绪、drain/status 不是 `RUNNING`、Nginx backup 未预配置、query 未就绪、drain 失败或超时、新主未就绪。部署脚本拒绝继续是为了避免停止仍持有未安全持久化任务的旧主，应先修复状态再重新执行。

### 16.3 Compose 显示容器已启动但命令不退出

脚本对 detached 启动设置了独立超时。超时后若容器已经运行，会继续检查 `/ready`；否则失败并进入退出保护。人工检查：

```bash
docker ps
docker logs --tail 200 sano-es-server-test
curl -sS http://127.0.0.1:9003/health | jq .
```

## 17. 检查清单

首次部署：

```text
[ ] Docker、Compose、curl、jq、timeout、Nginx可用
[ ] ES与es-server位于sano-net
[ ] 主实例/health成功
[ ] sano_import_task创建成功
[ ] 第一张启用表已有物理索引和Alias
[ ] /ready返回200且ready=true
[ ] drain/status返回coordinatorState=RUNNING
[ ] 外部域名和内部Nginx入口正常
```

安全升级：

```text
[ ] 使用明确版本号
[ ] Nginx主后端与backup已预配置
[ ] 旧主严格/ready正常
[ ] query临时实例接管成功
[ ] drain返回DRAINED或DRAINED_WITH_ERRORS
[ ] 新all实例严格/ready正常
[ ] 协调器恢复RUNNING
[ ] 外部域名和内部入口正常
[ ] query临时容器已删除
[ ] 日志没有持续WARN/ERROR
```
