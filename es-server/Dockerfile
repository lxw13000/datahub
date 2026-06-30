##
# es-server 运行镜像：
# 1. jar 由 docker-build.bat / docker-build.sh 在宿主机先完成编译。
# 2. Docker 只复制 jar 并制作运行镜像，避免容器内 Maven 下载依赖超时。
#
# eclipse-temurin 官方镜像支持 linux/amd64 和 linux/arm64。
# 如需构建多架构镜像：
# docker buildx build --platform linux/amd64,linux/arm64 -t lxw13000/sano-es-server:latest .
##

FROM eclipse-temurin:21-jre-jammy

LABEL maintainer="sano"
LABEL app="es-server"

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8002 \
    LOG_DIR=/app/logs/es-server \
    JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

ARG APP_UID=10001
ARG APP_GID=10001

WORKDIR /app

# 安装时区和健康检查工具，并创建非 root 运行用户。
RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata curl \
    && ln -snf /usr/share/zoneinfo/${TZ} /etc/localtime \
    && echo ${TZ} > /etc/timezone \
    && groupadd --system --gid ${APP_GID} app \
    && useradd --system --uid ${APP_UID} --gid app --home-dir /app app \
    && mkdir -p /app/logs/es-server \
    && chown -R app:app /app \
    && rm -rf /var/lib/apt/lists/*

# 复制宿主机 Maven 已经打好的 Spring Boot 可执行 jar。
COPY target/es-server-*.jar /app/es-server.jar

USER app

EXPOSE 8002

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health" || curl -fsS "http://127.0.0.1:${SERVER_PORT}/" || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Dserver.port=${SERVER_PORT} -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} -DLOG_DIR=${LOG_DIR} -jar /app/es-server.jar"]
