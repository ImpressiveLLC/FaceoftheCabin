# ── Stage 1: build ──────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
# VITE_API_BASE is baked in at build time — override per deployment
ARG VITE_CABIN_API_BASE=http://localhost:8090
ARG VITE_CABIN_WS_BASE=ws://localhost:9001
ARG VITE_CABIN_GRAFANA_URL=http://localhost:3002
ARG VITE_CABIN_NODERED_URL=http://localhost:1880
ARG VITE_CABIN_FRIGATE_URL=http://localhost:5000
ENV VITE_CABIN_API_BASE=$VITE_CABIN_API_BASE
ENV VITE_CABIN_WS_BASE=$VITE_CABIN_WS_BASE
ENV VITE_CABIN_GRAFANA_URL=$VITE_CABIN_GRAFANA_URL
ENV VITE_CABIN_NODERED_URL=$VITE_CABIN_NODERED_URL
ENV VITE_CABIN_FRIGATE_URL=$VITE_CABIN_FRIGATE_URL
RUN npm run build

# ── Stage 2: serve with nginx ────────────────────────────────────
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
