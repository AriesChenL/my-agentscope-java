# 构建：cd <repo root> && docker build -t my-agentscope-java:latest -f deploy/Dockerfile .
# 多阶段构建：第一阶段 mvnw package，第二阶段只带 fat jar 跑

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# 先复制 wrapper + pom，跑一次离线下载（让 Docker 缓存依赖层）
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B -q || true

# 再复制源码 + 打包
COPY src/ src/
RUN ./mvnw package -DskipTests -B -q

# ----------- runtime stage -----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar

# JVM 参数：容器内堆 = 容器内存 * 75%，G1GC，1G 堆建议 200ms 暂停目标
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:MaxGCPauseMillis=200"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
