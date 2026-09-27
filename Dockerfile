# syntax=docker/dockerfile:1
# Build cả frontend (Vue) và backend (Spring Boot) thành 1 image:
#   docker build -t fbads .

# Giai đoạn 1: build giao diện Vue ra /app/dist
FROM node:22-slim AS web
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
RUN npm run build

# Giai đoạn 2: build file jar bằng Maven (tải thư viện trước để Docker cache lại khi chỉ đổi code)
FROM maven:3.9-eclipse-temurin-21 AS jar
WORKDIR /src
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B -DskipTests package

# Giai đoạn 3: chỉ cần JRE để chạy
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 fbads
COPY --from=jar /src/target/fbads.jar ./fbads.jar
COPY --from=web /app/dist ./public
ENV PUBLIC_DIR=/app/public \
    HOST=0.0.0.0 \
    PORT=3000 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:ReservedCodeCacheSize=64m -XX:MaxMetaspaceSize=160m"
USER fbads
EXPOSE 3000
# Không có curl trong image → hỏi /actuator/health bằng /dev/tcp của bash
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3"]
CMD ["java", "-jar", "fbads.jar"]
