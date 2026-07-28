# es-server 完整部署文档

## 1. 文档范围和默认前提

本文档以当前项目中的以下文件为准：

```text
es-server/Dockerfile
es-server/docker-build.bat
es-server/docker-build.sh
es-server/docker-compose.yml
es-server/docker-compose-test.yml
es-server/deploy-es-server.sh
es-server/deploy-es-server-test.sh
es-server/nginx/es-server.conf.example
es-server/nginx/es-server-test.conf.example
```

本文档固定按以下顺序说明：

1. 打包并推送镜像。
2. 新服务器、空 ES 的第一次部署。
3. 第一次部署后的完整检查。
4. 后续版本升级。
5. 升级期间 query 接管和同步 drain。
6. 升级后的完整检查和失败回滚。

第一次部署默认使用以下真实前提：

- 是一台新的 Linux 服务器。
- 服务器已经安装 Docker 和 Docker Compose。
- Elasticsearch 服务已经启动，但 ES 中没有任何业务索引、业务 Alias 或同步内部索引。
- 正式、测试环境部署在同一台服务器。
- 正式 ES 容器通过 Docker 网络名称 `elasticsearch8:9200` 访问，宿主机端口为 `9201`。
- 测试 ES 容器通过 Docker 网络名称 `elasticsearch8-test:9200` 访问，宿主机端口为 `9211`。
- 服务器还没有 `es-server` 主容器和临时 query 容器。

空 ES 第一次部署不能直接执行 `deploy-es-server.sh` 或
`deploy-es-server-test.sh`。部署脚本会等待严格 `/ready`，而空 ES 尚未具备内部索引和业务 Alias，严格就绪必然失败。

第一次部署必须先直接使用 Docker Compose 启动应用，完成 ES 初始化并通过严格检查。后续版本升级才使用部署脚本执行 query 接管、同步 drain 和自动回滚。

## 2. 正式与测试环境规划

### 2.1 端口规划

| 环境 | 常驻 all | 临时 query | 内部 Nginx | ES 宿主机端口 |
| --- | ---: | ---: | ---: | ---: |
| 正式 | `8002` | `8003` | `8102` | `9201` |
| 测试 | `9003` | `9004` | `9103` | `9211` |

### 2.2 域名和容器

| 资源 | 正式环境 | 测试环境 |
| --- | --- | --- |
| es-server 外部域名 | `es-server.fofunlive.net` | `es-server-test.fofunlive.net` |
| 内部业务入口 | `服务器内网IP:8102` | `服务器内网IP:9103` |
| Elasticsearch 外部域名 | `es.fofunlive.net` | `es-test.fofunlive.net` |
| 常驻 all 容器 | `sano-es-server` | `sano-es-server-test` |
| 临时 query 容器 | `sano-es-server-query` | `sano-es-server-test-query` |
| Compose 项目 | `sano-es-server` | `sano-es-server-test` |
| 部署锁 | `/tmp/sano-es-server-deploy.lock` | `/tmp/sano-es-server-test-deploy.lock` |

### 2.3 服务模式

常驻实例：

```text
SANO_SERVER_MODE=all
SANO_ES_IMPORT_T_PLUS_ONE_ENABLED=true
SANO_ES_POLLING_ENABLED=true
```

后续安全升级期间临时 query 实例：

```text
SANO_SERVER_MODE=query
SANO_ES_IMPORT_T_PLUS_ONE_ENABLED=false
SANO_ES_POLLING_ENABLED=false
```

业务调用要求：

- 外部业务使用原有域名。
- 内部业务使用服务器内网 IP 加 Nginx 端口。
- 不要让业务直接依赖临时 query 端口 `8003` 或 `9004`。

## 3. 打包并推送镜像

以下命令以目标版本 `v1.0.9` 为例。实际发布时必须改成新的明确版本号。

服务器部署不要使用 `latest`。已发布版本标签不得覆盖；代码发生变化时必须增加版本号。

### 3.1 构建机检查

Windows：

```bat
java -version
mvn -version
docker version
docker buildx version
docker login
```

Linux 或 macOS：

```bash
java -version
mvn -version
docker version
docker buildx version
docker login
```

当前项目使用 Java 21。Dockerfile 只复制本机 Maven 已经生成的可执行 JAR，不会在 Docker 构建阶段下载 Maven 依赖。

### 3.2 Windows 打包并推送

```bat
cd /d C:\work\opts\sano\code\datahub\es-server
docker-build.bat v1.0.9 --push
```

该命令会：

1. 执行 `mvn -q -DskipTests clean package`。
2. 生成 `target/es-server-*.jar`。
3. 构建 `linux/amd64` 和 `linux/arm64` 镜像。
4. 推送 `lxw13000/sano-es-server:v1.0.9`。
5. 同时更新并推送 `lxw13000/sano-es-server:latest`。

检查远程镜像：

```bat
docker buildx imagetools inspect lxw13000/sano-es-server:v1.0.9
```

### 3.3 Linux 或 macOS 打包并推送

```bash
cd /path/to/es-server
chmod +x docker-build.sh
./docker-build.sh v1.0.9 --push
docker buildx imagetools inspect lxw13000/sano-es-server:v1.0.9
```

## 4. 新服务器基础准备

### 4.1 服务器部署目录

正式目录：

```text
/home/ec2-user/datahub/es-server
```

测试目录：

```text
/home/ec2-user/datahub-test/es-server
```

创建目录：

```bash
mkdir -p /home/ec2-user/datahub/es-server/nginx
mkdir -p /home/ec2-user/datahub-test/es-server/nginx
```

在 Windows 构建机 PowerShell 中设置服务器地址：

```powershell
$SERVER_IP = "替换为服务器公网IP"
```

创建服务器目录：

```powershell
ssh "ec2-user@$SERVER_IP" "mkdir -p /home/ec2-user/datahub/es-server/nginx /home/ec2-user/datahub-test/es-server/nginx"
```

上传正式环境文件：

```powershell
cd C:\work\opts\sano\code\datahub\es-server
scp deploy-es-server.sh "ec2-user@${SERVER_IP}:/home/ec2-user/datahub/es-server/deploy-es-server.sh"
scp docker-compose.yml "ec2-user@${SERVER_IP}:/home/ec2-user/datahub/es-server/docker-compose.yml"
scp nginx\es-server.conf.example "ec2-user@${SERVER_IP}:/home/ec2-user/datahub/es-server/nginx/es-server.conf.example"
```

上传测试环境文件：

```powershell
cd C:\work\opts\sano\code\datahub\es-server
scp deploy-es-server-test.sh "ec2-user@${SERVER_IP}:/home/ec2-user/datahub-test/es-server/deploy-es-server-test.sh"
scp docker-compose-test.yml "ec2-user@${SERVER_IP}:/home/ec2-user/datahub-test/es-server/docker-compose-test.yml"
scp nginx\es-server-test.conf.example "ec2-user@${SERVER_IP}:/home/ec2-user/datahub-test/es-server/nginx/es-server-test.conf.example"
```

正式环境最终应存在：

```text
deploy-es-server.sh
docker-compose.yml
nginx/es-server.conf.example
```

测试环境最终应存在：

```text
deploy-es-server-test.sh
docker-compose-test.yml
nginx/es-server-test.conf.example
```

### 4.2 检查基础工具

在新的 Amazon Linux 服务器安装当前部署需要的系统工具：

```bash
sudo yum install -y nginx curl jq coreutils
sudo systemctl enable nginx
sudo systemctl start nginx
```

如果当前系统使用 `dnf`：

```bash
sudo dnf install -y nginx curl jq coreutils
sudo systemctl enable nginx
sudo systemctl start nginx
```

安装后检查：

```bash
docker version
docker compose version || docker-compose version
command -v curl
command -v jq
command -v timeout
command -v awk
command -v sed
command -v sort
nginx -v
sudo systemctl status nginx --no-pager
```

后续部署脚本依赖 Docker、Compose、curl、jq、GNU `timeout`、awk、sed 和 sort。

### 4.3 检查部署文件

正式环境：

```bash
cd /home/ec2-user/datahub/es-server
chmod +x deploy-es-server.sh
bash -n deploy-es-server.sh
docker compose -f docker-compose.yml config --quiet \
  || docker-compose -f docker-compose.yml config --quiet
```

测试环境：

```bash
cd /home/ec2-user/datahub-test/es-server
chmod +x deploy-es-server-test.sh
bash -n deploy-es-server-test.sh
docker compose -f docker-compose-test.yml config --quiet \
  || docker-compose -f docker-compose-test.yml config --quiet
```

### 4.4 检查 Docker 网络和 ES 服务

正式与测试 es-server 共用外部 Docker 网络：

```text
sano-net
```

检查网络：

```bash
docker network inspect sano-net
```

如果网络不存在，创建网络：

```bash
docker network create sano-net
```

检查正式 ES 容器：

```bash
docker ps --filter 'name=^/elasticsearch8$'
docker inspect elasticsearch8 \
  --format 'status={{.State.Status}} networks={{json .NetworkSettings.Networks}}'
curl -fsS -u 'elastic:es2peter' http://127.0.0.1:9201
```

检查测试 ES 容器：

```bash
docker ps --filter 'name=^/elasticsearch8-test$'
docker inspect elasticsearch8-test \
  --format 'status={{.State.Status}} networks={{json .NetworkSettings.Networks}}'
curl -fsS -u 'elastic:es2peter' http://127.0.0.1:9211
```

如果现有 ES 容器尚未加入 `sano-net`，按实际环境执行：

正式：

```bash
docker network connect sano-net elasticsearch8
```

测试：

```bash
docker network connect sano-net elasticsearch8-test
```

容器已经连接该网络时不要重复执行 `docker network connect`。

### 4.5 确认 ES 当前为空

正式：

```bash
curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9201/_cat/indices?v'
curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9201/_cat/aliases?v'
```

测试：

```bash
curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9211/_cat/indices?v'
curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9211/_cat/aliases?v'
```

第一次部署默认以下内容都不存在：

```text
sano_import_task
sano_sync_polling_checkpoint
sano_wallet_coin_record
sano_wallet_diamond_record
```

## 5. 安装 Nginx 配置

正式、测试环境在同一台服务器上，两个配置文件必须同时保留，不能互相覆盖。

### 5.1 正式环境

```bash
cd /home/ec2-user/datahub/es-server

if [ -f /etc/nginx/conf.d/es-server.conf ]; then
  sudo cp /etc/nginx/conf.d/es-server.conf \
    /etc/nginx/conf.d/es-server.conf.bak-$(date +%Y%m%d%H%M%S)
fi

sudo install -m 0644 nginx/es-server.conf.example \
  /etc/nginx/conf.d/es-server.conf
sudo nginx -t
sudo systemctl reload nginx
```

正式配置提供：

- `es-server.fofunlive.net:80`
- `服务器内网IP:8102`
- `es.fofunlive.net:80`

`es-server` upstream：

```text
127.0.0.1:8002         主 all 实例
127.0.0.1:8003 backup  临时 query 实例
```

### 5.2 测试环境

```bash
cd /home/ec2-user/datahub-test/es-server

if [ -f /etc/nginx/conf.d/es-server-test.conf ]; then
  sudo cp /etc/nginx/conf.d/es-server-test.conf \
    /etc/nginx/conf.d/es-server-test.conf.bak-$(date +%Y%m%d%H%M%S)
fi

sudo install -m 0644 nginx/es-server-test.conf.example \
  /etc/nginx/conf.d/es-server-test.conf
sudo nginx -t
sudo systemctl reload nginx
```

测试配置提供：

- `es-server-test.fofunlive.net:80`
- `服务器内网IP:9103`
- `es-test.fofunlive.net:80`

`es-server-test` upstream：

```text
127.0.0.1:9003         主 all 实例
127.0.0.1:9004 backup  临时 query 实例
```

主容器尚未部署时，Nginx 的 es-server `/health` 暂时失败属于正常现象。

## 6. 测试环境第一次部署：新服务器、空 ES

本章是测试环境第一次部署的完整执行顺序。

第一次不要执行：

```bash
./deploy-es-server-test.sh
```

### 6.1 准备目录和权限

```bash
cd /home/ec2-user/datahub-test/es-server
mkdir -p logs-test logs-test-query
sudo chown -R 10001:10001 logs-test logs-test-query
sudo chmod 755 logs-test logs-test-query
chmod +x deploy-es-server-test.sh
```

### 6.2 拉取镜像

```bash
cd /home/ec2-user/datahub-test/es-server
docker pull lxw13000/sano-es-server:v1.0.9
docker image inspect lxw13000/sano-es-server:v1.0.9 \
  --format 'id={{.Id}} tags={{json .RepoTags}}'
```

### 6.3 直接使用 Compose 启动 all 容器

这就是第一次部署的启动命令：

```bash
cd /home/ec2-user/datahub-test/es-server
ES_SERVER_IMAGE_TAG=v1.0.9 docker compose \
  -f docker-compose-test.yml \
  -p sano-es-server-test \
  up -d --no-build --force-recreate es-server
```

如果服务器使用旧版独立 `docker-compose`：

```bash
cd /home/ec2-user/datahub-test/es-server
ES_SERVER_IMAGE_TAG=v1.0.9 docker-compose \
  -f docker-compose-test.yml \
  -p sano-es-server-test \
  up -d --no-build --force-recreate es-server
```

检查容器：

```bash
docker ps --filter 'name=^/sano-es-server-test$'
docker logs --tail 100 sano-es-server-test
```

等待 Web 启动：

```bash
until curl -fsS http://127.0.0.1:9003/health >/dev/null; do
  sleep 2
done
curl -fsS http://127.0.0.1:9003/health
```

预期返回：

```text
OK
```

此时只检查 `/health`。空 ES 下 `/ready` 返回 HTTP 503 属于预期结果。

### 6.4 创建两个同步内部索引

设置内部接口 Token：

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'
```

创建 T+1 任务索引：

```bash
curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/import/createImportTaskIndex | jq .
```

创建 Polling checkpoint 索引：

```bash
curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/import/createSyncInternalIndices | jq .
```

检查索引：

```bash
curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9211/_cat/indices/sano_import_task,sano_sync_polling_checkpoint?v'
```

预期两个索引都存在：

```text
sano_import_task
sano_sync_polling_checkpoint
```

初始化接口在索引已存在时会返回错误，不要重复调用。

### 6.5 生成严格就绪需要的第一个业务 Alias

当前测试配置存在 T+1 表，`/ready` 会优先检查第一张 T+1 表：

```text
sano_wallet_diamond_record
```

空 ES 中不存在该 Alias，因此必须先成功导入该表某个有数据的日期。

以下使用 `20260726` 作为示例。执行前必须确认 MySQL 中该日期有
`sano_wallet_diamond_record` 数据；如果没有数据，请替换成确认有数据的日期。

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'
IMPORT_DATE='20260726'

curl -fsS -H "token: ${TOKEN}" \
  "http://127.0.0.1:9003/import/importTableDateRange?tableName=sano_wallet_diamond_record&startDate=${IMPORT_DATE}&endDate=${IMPORT_DATE}" \
  | jq .
```

该接口异步创建并执行 T+1 任务。任务 ID 为：

```text
sano_wallet_diamond_record_20260726
```

查询任务：

```bash
IMPORT_DATE='20260726'
TASK_ID="sano_wallet_diamond_record_${IMPORT_DATE}"

curl -fsS -u 'elastic:es2peter' \
  "http://127.0.0.1:9211/sano_import_task/_doc/${TASK_ID}" \
  | jq '._source'
```

持续观察任务和日志：

```bash
docker logs -f sano-es-server-test
```

看到目标任务进入 `SUCCESS` 后使用 `Ctrl+C` 退出日志跟踪。

如果任务进入 `TIMEOUT_PARTIAL`，再次执行同一个接口，任务会从安全断点继续：

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'
IMPORT_DATE='20260726'

curl -fsS -H "token: ${TOKEN}" \
  "http://127.0.0.1:9003/import/importTableDateRange?tableName=sano_wallet_diamond_record&startDate=${IMPORT_DATE}&endDate=${IMPORT_DATE}" \
  | jq .
```

如果任务进入 `FAILED`，不要继续重启和严格就绪检查，先根据任务 `last_error` 和导入错误日志修复失败原因。

任务 `SUCCESS` 后检查物理索引和 Alias：

```bash
IMPORT_DATE='20260726'

curl -fsS -u 'elastic:es2peter' \
  "http://127.0.0.1:9211/sano_wallet_diamond_record_${IMPORT_DATE}" \
  | jq .

curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9211/_alias/sano_wallet_diamond_record' \
  | jq .
```

如果任务显示 `SUCCESS`，但 Alias 仍不存在，说明该日期源端总量为 0，程序按无数据成功处理，没有创建空索引。必须更换为 MySQL 确认有数据的日期重新提交。

### 6.6 等待 T+1 dispatcher 停止

重启前确认当前 T+1 dispatcher 和活动任务已经结束：

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/internal/sync/drain/status \
  | jq '.data.tPlusOne'
```

预期：

```text
dispatcherActive=false
activeTask=false
```

### 6.7 重启容器，初始化 Polling checkpoint

第一次启动时 checkpoint 索引不存在，Polling 协调器已经进入
`INITIALIZATION_FAILED`。虽然第 6.4 节创建了索引，但当前进程不会自动重新触发启动。

因此必须重启容器：

```bash
docker restart sano-es-server-test
```

等待 Web 恢复：

```bash
until curl -fsS http://127.0.0.1:9003/health >/dev/null; do
  sleep 2
done
curl -fsS http://127.0.0.1:9003/health
```

重启后，Polling 协调器会：

1. 检查 `sano_sync_polling_checkpoint`。
2. 为 `sano_wallet_coin_record` 创建唯一 checkpoint。
3. 使用配置的 `bootstrap-start-date=2026-07-27`。
4. 将初始 `last_id` 设为 `0`。
5. 创建并绑定 `sano_wallet_coin_record_20260727`。
6. 启动该表 Polling Worker。

### 6.8 测试环境第一次部署严格检查

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'

for ATTEMPT in $(seq 1 90); do
  READY_RESPONSE=$(curl -sS -H "token: ${TOKEN}" \
    http://127.0.0.1:9003/ready || true)

  if printf '%s' "${READY_RESPONSE}" | jq -e '.ready == true' >/dev/null 2>&1; then
    printf '%s' "${READY_RESPONSE}" | jq .
    break
  fi

  if [ "${ATTEMPT}" -eq 90 ]; then
    printf '%s' "${READY_RESPONSE}" | jq .
    echo "ERROR: sano-es-server-test等待严格就绪超过180秒。"
    exit 1
  fi
  sleep 2
done
```

预期：

```text
ready=true
serviceMode=ALL
queryReady=true
syncReady=true
```

检查同步状态：

```bash
curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/internal/sync/status \
  | jq .
```

检查 checkpoint：

```bash
curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9211/sano_sync_polling_checkpoint/_doc/sano_wallet_coin_record' \
  | jq '._source'
```

预期至少包含：

```text
table_name=sano_wallet_coin_record
index_alias=sano_wallet_coin_record
status=RUNNING
sync_date=2026-07-27
last_id>=0
```

检查容器：

```bash
docker inspect sano-es-server-test \
  --format 'image={{.Config.Image}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart={{.RestartCount}}'
```

检查 Nginx：

```bash
curl -fsS http://127.0.0.1:9103/health

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9103/ready \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://es-server-test.fofunlive.net/ready \
  | jq .
```

以上检查全部通过后，测试环境第一次部署完成。

## 7. 正式环境第一次部署：新服务器、空 ES

正式环境步骤与测试环境一致，但必须使用正式目录、端口、Compose 文件和 ES。

第一次不要执行：

```bash
./deploy-es-server.sh
```

### 7.1 准备目录和权限

```bash
cd /home/ec2-user/datahub/es-server
mkdir -p logs logs-query
sudo chown -R 10001:10001 logs logs-query
sudo chmod 755 logs logs-query
chmod +x deploy-es-server.sh
```

### 7.2 拉取镜像

```bash
cd /home/ec2-user/datahub/es-server
docker pull lxw13000/sano-es-server:v1.0.9
docker image inspect lxw13000/sano-es-server:v1.0.9 \
  --format 'id={{.Id}} tags={{json .RepoTags}}'
```

### 7.3 直接使用 Compose 启动 all 容器

```bash
cd /home/ec2-user/datahub/es-server
ES_SERVER_IMAGE_TAG=v1.0.9 docker compose \
  -f docker-compose.yml \
  -p sano-es-server \
  up -d --no-build --force-recreate es-server
```

如果服务器使用旧版独立 `docker-compose`：

```bash
cd /home/ec2-user/datahub/es-server
ES_SERVER_IMAGE_TAG=v1.0.9 docker-compose \
  -f docker-compose.yml \
  -p sano-es-server \
  up -d --no-build --force-recreate es-server
```

检查并等待 Web 启动：

```bash
docker ps --filter 'name=^/sano-es-server$'
docker logs --tail 100 sano-es-server

until curl -fsS http://127.0.0.1:8002/health >/dev/null; do
  sleep 2
done
curl -fsS http://127.0.0.1:8002/health
```

预期返回 `OK`。此时 `/ready` 返回 HTTP 503 属于预期结果。

### 7.4 创建两个同步内部索引

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/import/createImportTaskIndex \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/import/createSyncInternalIndices \
  | jq .

curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9201/_cat/indices/sano_import_task,sano_sync_polling_checkpoint?v'
```

### 7.5 生成严格就绪需要的第一个业务 Alias

正式配置的 `/ready` 同样优先检查：

```text
sano_wallet_diamond_record
```

选择一个 MySQL 确认有数据的日期。以下以 `20260726` 为例：

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'
IMPORT_DATE='20260726'

curl -fsS -H "token: ${TOKEN}" \
  "http://127.0.0.1:8002/import/importTableDateRange?tableName=sano_wallet_diamond_record&startDate=${IMPORT_DATE}&endDate=${IMPORT_DATE}" \
  | jq .
```

查询任务：

```bash
IMPORT_DATE='20260726'
TASK_ID="sano_wallet_diamond_record_${IMPORT_DATE}"

curl -fsS -u 'elastic:es2peter' \
  "http://127.0.0.1:9201/sano_import_task/_doc/${TASK_ID}" \
  | jq '._source'
```

观察日志：

```bash
docker logs -f sano-es-server
```

任务必须进入 `SUCCESS`。如果进入 `TIMEOUT_PARTIAL`，再次提交同一个接口继续执行；如果进入 `FAILED`，先处理失败原因。

任务成功后检查：

```bash
IMPORT_DATE='20260726'

curl -fsS -u 'elastic:es2peter' \
  "http://127.0.0.1:9201/sano_wallet_diamond_record_${IMPORT_DATE}" \
  | jq .

curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9201/_alias/sano_wallet_diamond_record' \
  | jq .
```

如果 Alias 不存在，说明选择日期没有源数据，必须更换有数据的日期。

### 7.6 等待 T+1 dispatcher 停止

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/internal/sync/drain/status \
  | jq '.data.tPlusOne'
```

确认：

```text
dispatcherActive=false
activeTask=false
```

### 7.7 重启容器，初始化 Polling checkpoint

```bash
docker restart sano-es-server

until curl -fsS http://127.0.0.1:8002/health >/dev/null; do
  sleep 2
done
curl -fsS http://127.0.0.1:8002/health
```

### 7.8 正式环境第一次部署严格检查

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'

for ATTEMPT in $(seq 1 90); do
  READY_RESPONSE=$(curl -sS -H "token: ${TOKEN}" \
    http://127.0.0.1:8002/ready || true)

  if printf '%s' "${READY_RESPONSE}" | jq -e '.ready == true' >/dev/null 2>&1; then
    printf '%s' "${READY_RESPONSE}" | jq .
    break
  fi

  if [ "${ATTEMPT}" -eq 90 ]; then
    printf '%s' "${READY_RESPONSE}" | jq .
    echo "ERROR: sano-es-server等待严格就绪超过180秒。"
    exit 1
  fi
  sleep 2
done

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/internal/sync/status \
  | jq .

curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9201/sano_sync_polling_checkpoint/_doc/sano_wallet_coin_record' \
  | jq '._source'

docker inspect sano-es-server \
  --format 'image={{.Config.Image}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart={{.RestartCount}}'
```

检查 Nginx：

```bash
curl -fsS http://127.0.0.1:8102/health

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8102/ready \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://es-server.fofunlive.net/ready \
  | jq .
```

以上检查全部通过后，正式环境第一次部署完成。

## 8. 第一次部署完成后的统一检查

### 8.1 容器数量

正式环境平时只应存在：

```text
sano-es-server
```

测试环境平时只应存在：

```text
sano-es-server-test
```

检查：

```bash
docker ps -a --filter 'name=sano-es-server'
```

第一次部署不启动临时 query 容器，因此不应存在：

```text
sano-es-server-query
sano-es-server-test-query
```

### 8.2 ES 索引

正式：

```bash
curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9201/_cat/indices/sano_*?v&s=index'
curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9201/_cat/aliases/sano_*?v&s=alias,index'
```

测试：

```bash
curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9211/_cat/indices/sano_*?v&s=index'
curl -fsS -u 'elastic:es2peter' \
  'http://127.0.0.1:9211/_cat/aliases/sano_*?v&s=alias,index'
```

至少应存在：

```text
sano_import_task
sano_sync_polling_checkpoint
sano_wallet_diamond_record_导入日期
sano_wallet_diamond_record Alias
sano_wallet_coin_record_20260727
sano_wallet_coin_record Alias
```

### 8.3 Polling 日志

测试：

```bash
cd /home/ec2-user/datahub-test/es-server
DAY=$(date +%Y%m%d)
ls -lh "logs-test/${DAY}"
tail -n 200 "logs-test/${DAY}/es-server-polling.log"
tail -n 200 "logs-test/${DAY}/es-server-polling-error.log"
```

正式：

```bash
cd /home/ec2-user/datahub/es-server
DAY=$(date +%Y%m%d)
ls -lh "logs/${DAY}"
tail -n 200 "logs/${DAY}/es-server-polling.log"
tail -n 200 "logs/${DAY}/es-server-polling-error.log"
```

正常运行时应能看到：

```text
ES-Polling coordinator started
ES-Polling worker started
ES-Polling cycle completed
```

## 9. 后续版本升级前准备

第一次部署完成且严格检查通过后，后续发布不再直接执行 Compose 启动命令，而是使用部署脚本的 `safe` 模式。

以下以从 `v1.0.9` 升级到 `v1.0.10` 为例。

### 9.1 构建并推送新版本

Windows：

```bat
cd /d C:\work\opts\sano\code\datahub\es-server
docker-build.bat v1.0.10 --push
docker buildx imagetools inspect lxw13000/sano-es-server:v1.0.10
```

Linux 或 macOS：

```bash
cd /path/to/es-server
./docker-build.sh v1.0.10 --push
docker buildx imagetools inspect lxw13000/sano-es-server:v1.0.10
```

### 9.2 更新服务器部署文件

正式环境更新：

```text
/home/ec2-user/datahub/es-server/deploy-es-server.sh
/home/ec2-user/datahub/es-server/docker-compose.yml
/home/ec2-user/datahub/es-server/nginx/es-server.conf.example
```

测试环境更新：

```text
/home/ec2-user/datahub-test/es-server/deploy-es-server-test.sh
/home/ec2-user/datahub-test/es-server/docker-compose-test.yml
/home/ec2-user/datahub-test/es-server/nginx/es-server-test.conf.example
```

检查正式文件：

```bash
cd /home/ec2-user/datahub/es-server
chmod +x deploy-es-server.sh
bash -n deploy-es-server.sh
docker compose -f docker-compose.yml config --quiet \
  || docker-compose -f docker-compose.yml config --quiet
```

检查测试文件：

```bash
cd /home/ec2-user/datahub-test/es-server
chmod +x deploy-es-server-test.sh
bash -n deploy-es-server-test.sh
docker compose -f docker-compose-test.yml config --quiet \
  || docker-compose -f docker-compose-test.yml config --quiet
```

### 9.3 检查 Nginx 已支持 query 接管

```bash
sudo nginx -t
sudo systemctl reload nginx
```

检查正式 upstream 配置：

```bash
grep -nE '8002|8003|8102' /etc/nginx/conf.d/es-server.conf
```

检查测试 upstream 配置：

```bash
grep -nE '9003|9004|9103' /etc/nginx/conf.d/es-server-test.conf
```

### 9.4 升级前检查当前主实例

测试：

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'

docker inspect sano-es-server-test \
  --format 'image={{.Config.Image}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart={{.RestartCount}}'

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/ready \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/internal/sync/drain/status \
  | jq .
```

正式：

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'

docker inspect sano-es-server \
  --format 'image={{.Config.Image}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart={{.RestartCount}}'

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/ready \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/internal/sync/drain/status \
  | jq .
```

升级前必须满足：

```text
ready=true
serviceMode=ALL
queryReady=true
syncReady=true
coordinatorState=RUNNING
```

## 10. 测试环境后续安全升级

### 10.1 完整升级命令

```bash
cd /home/ec2-user/datahub-test/es-server
ES_SERVER_IMAGE_TAG=v1.0.10 \
DEPLOY_MODE=safe \
NGINX_HANDOFF_PRECONFIGURED=true \
./deploy-es-server-test.sh
```

单行写法：

```bash
cd /home/ec2-user/datahub-test/es-server
ES_SERVER_IMAGE_TAG=v1.0.10 DEPLOY_MODE=safe NGINX_HANDOFF_PRECONFIGURED=true ./deploy-es-server-test.sh
```

正常发布不需要传入 `SYNC_API_TOKEN`。脚本已内置与当前代码一致的固定 Token。

### 10.2 safe 升级实际流程

脚本依次执行：

1. 获取当前 `sano-es-server-test` 的镜像引用和镜像 ID。
2. 检查旧主实例 `/ready=true`、`serviceMode=ALL`。
3. 检查旧主实例具备统一 drain 协议。
4. 确认 Nginx 已预配置 `9004` backup。
5. 复用旧镜像原有版本标签作为回滚镜像。
6. 使用旧稳定镜像启动 `sano-es-server-test-query`。
7. 临时 query 容器使用 `SANO_SERVER_MODE=query` 和端口 `9004`。
8. 检查临时 query `/ready=true`、`serviceMode=QUERY`。
9. 拉取目标镜像 `v1.0.10`。
10. 请求旧 all 实例进入 drain。
11. 等待 T+1 和 Polling 到达可恢复边界。
12. 停止并删除旧 all 容器。
13. 通过外部域名和内部 `9103` 执行查询冒烟；此时由 `9004` 承接。
14. 启动目标版本 all 容器并恢复 `9003`。
15. 检查新实例严格就绪、服务模式和同步协调器。
16. 再次通过外部域名和内部 `9103` 执行查询冒烟。
17. 停止并删除临时 query 容器。
18. 输出最终容器状态。

### 10.3 优雅停机时 Polling 的行为

部署脚本调用：

```text
POST /internal/sync/drain
GET  /internal/sync/drain/status
```

Polling drain 顺序：

1. 不再启动新的表 Worker。
2. 向现有 Worker 设置停止标记。
3. 正在执行 MySQL 查询时等待 SQL 返回。
4. 已经读取到内存的 MySQL 数据仍执行完整 ES Bulk。
5. Bulk 正在重试时等待本批重试结束。
6. 保存 Worker 当前日期和内存 `lastId`。
7. checkpoint 业务状态保持 `RUNNING`。
8. Worker 退出。

新 all 实例启动后从持久 checkpoint 恢复。

### 10.4 优雅停机时 T+1 的行为

1. 不再创建或启动新任务。
2. 当前 Reader 不再读取新批次。
3. 已经排队或正在执行的 Bulk 继续完成。
4. 保存最后连续完成批次的安全断点。
5. 当前任务进入 `TIMEOUT_PARTIAL` 或明确失败终态。

部署脚本接受：

```text
DRAINED
DRAINED_WITH_ERRORS
```

收到 `FAILED`、未知状态或等待超过 600 秒时，不停止旧主实例。

## 11. 正式环境后续安全升级

### 11.1 完整升级命令

```bash
cd /home/ec2-user/datahub/es-server
ES_SERVER_IMAGE_TAG=v1.0.10 \
DEPLOY_MODE=safe \
NGINX_HANDOFF_PRECONFIGURED=true \
./deploy-es-server.sh
```

单行写法：

```bash
cd /home/ec2-user/datahub/es-server
ES_SERVER_IMAGE_TAG=v1.0.10 DEPLOY_MODE=safe NGINX_HANDOFF_PRECONFIGURED=true ./deploy-es-server.sh
```

正式环境执行与测试环境相同的 query 接管、同步 drain、严格就绪和失败回滚流程，端口对应为：

```text
all=8002
query=8003
内部Nginx=8102
ES=9201
```

## 12. 升级后完整检查

### 12.1 测试环境

```bash
cd /home/ec2-user/datahub-test/es-server
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'

docker ps --filter 'name=^/sano-es-server-test$'

docker inspect sano-es-server-test \
  --format 'image={{.Config.Image}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart={{.RestartCount}}'

curl -fsS http://127.0.0.1:9003/health

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/ready \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/internal/sync/drain/status \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/internal/sync/status \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9103/ready \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://es-server-test.fofunlive.net/ready \
  | jq .

docker ps -a --filter 'name=^/sano-es-server-test-query$'
docker logs --tail 200 sano-es-server-test
```

预期：

- 主容器镜像是 `v1.0.10`。
- `status=running`。
- 最终 `health=healthy`。
- `restart=0`。
- `/ready` 中 `ready=true`。
- `serviceMode=ALL`。
- `queryReady=true`。
- `syncReady=true`。
- drain 状态中 `coordinatorState=RUNNING`。
- 临时 query 容器已经删除。

### 12.2 正式环境

```bash
cd /home/ec2-user/datahub/es-server
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'

docker ps --filter 'name=^/sano-es-server$'

docker inspect sano-es-server \
  --format 'image={{.Config.Image}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart={{.RestartCount}}'

curl -fsS http://127.0.0.1:8002/health

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/ready \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/internal/sync/drain/status \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/internal/sync/status \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8102/ready \
  | jq .

curl -fsS -H "token: ${TOKEN}" \
  http://es-server.fofunlive.net/ready \
  | jq .

docker ps -a --filter 'name=^/sano-es-server-query$'
docker logs --tail 200 sano-es-server
```

## 13. 升级失败和自动回滚

### 13.1 旧主容器尚未停止

如果 safe 流程在旧主容器停止前失败，脚本会：

1. 尝试取消本轮 drain。
2. 请求恢复本轮被中断的同步任务。
3. 停止并删除临时 query 容器。
4. 保持旧主容器运行。

### 13.2 旧主容器已经被替换

如果新主容器启动或严格检查失败，脚本会：

1. 尽量保持临时 query 容器运行。
2. 删除失败的新主容器。
3. 使用原稳定版本镜像重新创建主容器。
4. 原版本标签仍可靠时直接复用，例如从 `v1.0.9` 恢复。
5. 原版本标签不可靠时才创建时间戳安全标签。
6. 旧主 `/health` 恢复后保留临时 query 容器，等待人工确认。

Docker 标签不会复制镜像层。

### 13.3 回滚后检查

测试：

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'

docker ps -a --filter 'name=sano-es-server-test'
docker inspect sano-es-server-test \
  --format 'image={{.Config.Image}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart={{.RestartCount}}'
curl -fsS http://127.0.0.1:9003/health
curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/ready \
  | jq .
curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/internal/sync/status \
  | jq .
```

正式：

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'

docker ps -a --filter 'name=sano-es-server'
docker inspect sano-es-server \
  --format 'image={{.Config.Image}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart={{.RestartCount}}'
curl -fsS http://127.0.0.1:8002/health
curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/ready \
  | jq .
curl -fsS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/internal/sync/status \
  | jq .
```

确认旧主正常后，如果临时 query 容器仍存在，执行清理。

测试：

```bash
cd /home/ec2-user/datahub-test/es-server
docker compose -f docker-compose-test.yml -p sano-es-server-test \
  --profile query-handoff stop es-server-query
docker compose -f docker-compose-test.yml -p sano-es-server-test \
  --profile query-handoff rm -f es-server-query
```

正式：

```bash
cd /home/ec2-user/datahub/es-server
docker compose -f docker-compose.yml -p sano-es-server \
  --profile query-handoff stop es-server-query
docker compose -f docker-compose.yml -p sano-es-server \
  --profile query-handoff rm -f es-server-query
```

## 14. 部署脚本参数

| 环境变量 | 默认值 | 用途 |
| --- | ---: | --- |
| `ES_SERVER_IMAGE_TAG` | `latest` | 目标镜像标签；实际部署必须显式传版本 |
| `DEPLOY_MODE` | `safe` | `safe` 或 `legacy` |
| `START_TIMEOUT` | `180` | 等待严格就绪秒数 |
| `COMPOSE_UP_TIMEOUT` | `30` | Compose detached 命令等待秒数 |
| `DRAIN_TIMEOUT` | `600` | 等待同步 drain 秒数 |
| `POLL_INTERVAL` | `2` | 部署状态轮询间隔秒数 |
| `STABLE_SECONDS` | `20` | 容器稳定观察秒数 |
| `LOG_TAIL_LINES` | `120` | 失败诊断输出日志行数 |
| `NGINX_HANDOFF_PRECONFIGURED` | `false` | 确认 Nginx backup 已预配置 |

正常后续发布只需传：

测试：

```bash
ES_SERVER_IMAGE_TAG=v1.0.10 DEPLOY_MODE=safe NGINX_HANDOFF_PRECONFIGURED=true ./deploy-es-server-test.sh
```

正式：

```bash
ES_SERVER_IMAGE_TAG=v1.0.10 DEPLOY_MODE=safe NGINX_HANDOFF_PRECONFIGURED=true ./deploy-es-server.sh
```

`legacy` 不执行 query 接管和 drain，不用于已经完成第一次初始化后的日常升级。

## 15. 日志位置

测试主实例：

```text
/home/ec2-user/datahub-test/es-server/logs-test
```

测试临时 query：

```text
/home/ec2-user/datahub-test/es-server/logs-test-query
```

正式主实例：

```text
/home/ec2-user/datahub/es-server/logs
```

正式临时 query：

```text
/home/ec2-user/datahub/es-server/logs-query
```

当天主要日志：

```text
es-server.log
es-server-error.log
es-server-import.log
es-server-import-error.log
es-server-polling.log
es-server-polling-error.log
es-server-search-api.log
```

## 16. 常见问题

### 16.1 第一次部署为什么不能直接使用部署脚本

空 ES 没有：

```text
sano_import_task
sano_sync_polling_checkpoint
sano_wallet_diamond_record Alias
```

而 `/ready` 会检查这些内容。部署脚本无法在 `/ready` 通过前调用初始化接口，所以第一次必须直接 Compose 启动。

### 16.2 创建 checkpoint 索引后为什么必须重启

Polling 协调器只在 `ApplicationReadyEvent` 时自动启动。第一次启动发现 checkpoint 索引缺失后进入 `INITIALIZATION_FAILED`，创建索引不会自动重放该事件。

### 16.3 T+1 任务成功但 Alias 仍不存在

所选日期源端总量为 0 时，T+1 按成功处理，但不会创建空索引或 Alias。必须选择 MySQL 确认有数据的日期。

### 16.4 `/health` 成功但 `/ready` 返回 503

`/health` 只表示 Web 容器存活。使用以下命令查看严格就绪详情。

测试：

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'
curl -sS -H "token: ${TOKEN}" \
  http://127.0.0.1:9003/ready \
  | jq .
```

正式：

```bash
TOKEN='sano-es-Z1q7n2V4m8K5x9P3d6R0h4T8w2Y7c1F'
curl -sS -H "token: ${TOKEN}" \
  http://127.0.0.1:8002/ready \
  | jq .
```

### 16.5 Compose 显示容器已启动但命令不退出

当前部署脚本已经为 Compose detached 启动设置 30 秒边界。手工排查：

```bash
docker ps
ps -ef | grep '[d]ocker-compose'
docker logs --tail 200 sano-es-server-test
```

正式环境把容器名改为 `sano-es-server`。

### 16.6 部署锁残留

测试：

```bash
cat /tmp/sano-es-server-test-deploy.lock/pid
DEPLOY_PID=$(cat /tmp/sano-es-server-test-deploy.lock/pid)
ps -o pid,ppid,stat,etime,wchan:32,cmd -p "${DEPLOY_PID}"
```

正式：

```bash
cat /tmp/sano-es-server-deploy.lock/pid
DEPLOY_PID=$(cat /tmp/sano-es-server-deploy.lock/pid)
ps -o pid,ppid,stat,etime,wchan:32,cmd -p "${DEPLOY_PID}"
```

只有确认 PID 已不存在且没有部署进程后，才能删除对应锁目录。

## 17. 最终发布检查单

### 17.1 第一次部署

```text
[ ] Docker、Compose、curl、jq、timeout 可用
[ ] ES 服务已启动并加入 sano-net
[ ] ES 当前为空
[ ] Nginx 正式、测试配置已安装
[ ] 使用明确版本完成镜像打包和推送
[ ] 使用 Compose 直接启动 all 容器
[ ] /health 返回 OK
[ ] 已创建 sano_import_task
[ ] 已创建 sano_sync_polling_checkpoint
[ ] 已通过有数据日期生成 sano_wallet_diamond_record Alias
[ ] T+1 dispatcher 已停止
[ ] 已重启 all 容器
[ ] Polling checkpoint 已初始化
[ ] Polling Worker 已启动
[ ] /ready=true、serviceMode=ALL
[ ] 外部域名和内部 Nginx 入口正常
```

### 17.2 后续升级

```text
[ ] 新版本镜像已打包并推送
[ ] 当前主实例 /ready=true
[ ] 当前 coordinatorState=RUNNING
[ ] Nginx 主后端和 backup 后端配置正确
[ ] 使用 DEPLOY_MODE=safe
[ ] 命令包含 NGINX_HANDOFF_PRECONFIGURED=true
[ ] query 临时实例接管成功
[ ] drain 返回 DRAINED 或 DRAINED_WITH_ERRORS
[ ] 新 all 实例严格就绪
[ ] 新同步协调器恢复 RUNNING
[ ] 外部域名和内部 Nginx 入口正常
[ ] 临时 query 容器已经删除
[ ] 日志没有持续 ERROR
```
