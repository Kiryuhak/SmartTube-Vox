# Production-ready container for VOX Live Translation Backend
FROM node:22-slim

WORKDIR /app

# Установка необходимых утилит для runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY package.json ./

# В MOCK режиме зависимости встроенные (zero-external npm deps)
# При подключении внешних модулей: RUN npm ci --omit=dev

COPY src/ ./src/

ENV NODE_ENV=production
ENV PORT=8090
ENV HOST=0.0.0.0
ENV VOX_BACKEND_MODE=MOCK

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8090/health || exit 1

CMD ["node", "src/server.js"]
