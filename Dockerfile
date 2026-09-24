# syntax=docker/dockerfile:1
# Giai đoạn 1: build giao diện Vue (cần shared/ vì giao diện dùng chung luật kiểm tra với server)
FROM node:22-slim AS web
WORKDIR /app
COPY web/package.json web/package-lock.json ./web/
RUN cd web && npm ci
COPY web ./web
COPY shared ./shared
RUN cd web && npm run build

# Giai đoạn 2: chạy server (không cần cài thư viện nào)
FROM node:22-slim
ENV NODE_ENV=production
WORKDIR /app
COPY server.js ./
COPY lib ./lib
COPY shared ./shared
COPY --from=web /app/public ./public
EXPOSE 3000
CMD ["node", "server.js"]
