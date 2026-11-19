# 水利知识库 RAG 应用镜像（apt 方案，适配 Docker Hub 拉取受限的网络环境）
#
# 设计决策：
#   1. 基础镜像用本项目已在用的 pgvector/pgvector:pg16（本地已有，无需从 Docker Hub 拉取）；
#   2. 通过 apt 安装 Debian 官方源的 openjdk-17-jre-headless（实测 2.3MB/s，可稳定下载）；
#   3. COPY 本机 mvn package 产出的 jar，构建秒级完成。
#   说明：镜像里包含 PostgreSQL 客户端工具属于"附带 baggage"，对纯 Java 应用是冗余，
#   但换来的是零网络依赖的确定性构建。若网络允许拉取 eclipse-temurin:17-jre，
#   可换回标准基础镜像，Dockerfile 只改 FROM 一行。
#
# 构建步骤：
#   mvn -DskipTests package
#   docker compose up -d --build app
FROM pgvector/pgvector:pg16

WORKDIR /app

# 安装 JRE（Debian 12 官方源，国内直连可用）
RUN apt-get update -qq && \
    apt-get install -y -qq --no-install-recommends openjdk-17-jre-headless && \
    rm -rf /var/lib/apt/lists/*

# jar 名与 pom.xml 的 artifactId/version 对应
COPY target/water-rag-demo-1.0.0.jar /app/app.jar

# 时区设为上海，避免日志时间与本地差 8 小时
ENV TZ=Asia/Shanghai

EXPOSE 8080

# 用环境变量覆盖配置（DB_HOST / REDIS_HOST / AI_API_KEY 等由 compose 注入）
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
