# 构建：cd <repo root> && docker build -t my-agentscope-java:latest -f deploy/Dockerfile .
#
# 注意：原本是多阶段（builder 用 JDK，runtime 用 JRE 减小镜像）。
# 但本地镜像源拉不到 eclipse-temurin:21-jdk-alpine / jre-alpine，
# 退回单阶段用 maven:3.9-eclipse-temurin-21（本地已缓存）。
# 生产环境如果能拉 21-jre-alpine，把 Dockerfile 拆回多阶段会让镜像从 ~800MB 减到 ~250MB。

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先复制 wrapper + pom，跑一次离线下载（让 Docker 缓存依赖层）
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN mvn dependency:go-offline -B -q || true

# 再复制源码 + 打包
COPY src/ src/
RUN mvn package -DskipTests -B -q

# 单阶段：直接在 builder 镜像跑
FROM maven:3.9-eclipse-temurin-21
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar

# JVM 参数：容器内堆 = 容器内存 * 75%，G1GC，1G 堆建议 200ms 暂停目标
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:MaxGCPauseMillis=200"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
