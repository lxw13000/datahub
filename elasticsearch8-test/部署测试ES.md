# 测试 ES 部署

该目录部署独立测试集群，容器名为 `elasticsearch8-test`，加入已有 Docker 网络 `sano-net`。
它使用宿主机 `9211/9311` 端口，不会占用生产 ES 的 `9201/9301`，数据也只写入本目录的 `data`。

## 首次部署

在 Linux 服务器的本目录执行：

```bash
docker network inspect sano-net >/dev/null 2>&1 || docker network create sano-net
mkdir -p data logs plugins backup
sudo chown -R 1000:0 data logs plugins backup
docker compose up -d
docker compose ps
curl http://127.0.0.1:9211
```

容器内的 Elasticsearch 以 UID `1000` 写入挂载目录；未设置目录权限时，容器通常会因无法写入 `data` 或 `logs` 而启动失败。

## 应用连接

同在 `sano-net` 的 Docker 应用使用内部地址，不经过宿主机端口：

```yaml
ES_URIS: elasticsearch8-test:9200
```

本地 IDE 或服务器外的开发环境使用服务器地址和映射端口：

```yaml
ES_URIS: http://服务器IP或域名:9211
```

测试 ES 未启用安全认证，`ES_USERNAME` 和 `ES_PASSWORD` 可以保留但不会参与认证。请通过安全组、防火墙或反向代理限制 `9211` 的访问来源，不能直接暴露到公网。
