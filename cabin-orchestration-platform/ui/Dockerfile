# ── Stage 1: build ──────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
# VITE_API_BASE is baked in at build time — override per deployment
#
# Found 2026-08-02: docker-compose.m920q.yml passes VITE_CABIN_HA_URL,
# VITE_CABIN_Z2M_URL, and VITE_CABIN_FAMILY_HUB_URL as build args, but this
# file never declared ARG/ENV for them — a Docker ARG is NOT automatically
# visible to the build process as an env var; without the explicit ENV
# re-export below, Vite never saw them and every deployed build has been
# silently falling back to the hardcoded defaults in App.jsx's LOCATIONS
# config (a non-resolvable `cabin-hub` hostname for HA/Z2M, `null` for the
# Family Hub link) instead of the real Tailscale-IP/public values compose
# was actually trying to pass in. Same root cause class as GOOGLE_CLIENT_ID/
# ADMIN_EMAILS going missing in family-hub's .env — a value "looks" wired
# through docker-compose but the receiving Dockerfile silently drops it.
ARG VITE_CABIN_API_BASE=http://localhost:8090
ARG VITE_CABIN_WS_BASE=ws://localhost:9001
ARG VITE_CABIN_GRAFANA_URL=http://localhost:3002
ARG VITE_CABIN_NODERED_URL=http://localhost:1880
ARG VITE_CABIN_FRIGATE_URL=http://localhost:5000
ARG VITE_CABIN_HA_URL=http://localhost:8123
ARG VITE_CABIN_Z2M_URL=http://localhost:8080
ARG VITE_CABIN_FAMILY_HUB_URL=
# Google Sign-In for cabin-ui's own authenticated views (e.g. Camera
# Events) — reuses the SAME Web-application OAuth client family-hub signs
# in with, not a new Google Cloud app. Requires cabin.unicornpingpong.com
# added as an authorized JavaScript origin on that client (Google Cloud
# Console -> APIs & Services -> Credentials) — a manual, one-time step.
ARG VITE_CABIN_GOOGLE_CLIENT_ID=
ENV VITE_CABIN_API_BASE=$VITE_CABIN_API_BASE
ENV VITE_CABIN_WS_BASE=$VITE_CABIN_WS_BASE
ENV VITE_CABIN_GRAFANA_URL=$VITE_CABIN_GRAFANA_URL
ENV VITE_CABIN_NODERED_URL=$VITE_CABIN_NODERED_URL
ENV VITE_CABIN_FRIGATE_URL=$VITE_CABIN_FRIGATE_URL
ENV VITE_CABIN_HA_URL=$VITE_CABIN_HA_URL
ENV VITE_CABIN_Z2M_URL=$VITE_CABIN_Z2M_URL
ENV VITE_CABIN_FAMILY_HUB_URL=$VITE_CABIN_FAMILY_HUB_URL
ENV VITE_CABIN_GOOGLE_CLIENT_ID=$VITE_CABIN_GOOGLE_CLIENT_ID
RUN npm run build

# ── Stage 2: serve with nginx ────────────────────────────────────
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
