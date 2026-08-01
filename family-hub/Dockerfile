FROM nginx:alpine
# Host-provided config, baked at build time — same pattern cabin-ui already
# uses (Dockerfile ARGs from docker-compose.m920q.yml, sourced from .env).
# The point: devices don't each configure their own Google Client ID: the
# host that's actually serving the app carries it, every device just
# inherits whatever this deployment was built with.
ARG GOOGLE_CLIENT_ID=
# Comma-separated admin emails — was hardcoded in family-hub.html source
# (a template someone forks would've had to edit and rebuild the app just
# to change who has audit-log access). Same reasoning as GOOGLE_CLIENT_ID:
# an app-level setting belongs to the host serving the app, not baked into
# the source every fork carries around.
ARG ADMIN_EMAILS=
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Explicit allowlist, not `COPY . .` — this directory also holds dev/test
# tooling (package.json, test/) that must never be served publicly.
COPY family-hub.html css2.css google.svg hodgson-crest.svg /usr/share/nginx/html/
RUN echo "window.HOST_CONFIG = { googleClientId: \"${GOOGLE_CLIENT_ID}\", adminEmails: \"${ADMIN_EMAILS}\" };" \
    > /usr/share/nginx/html/host-config.js
EXPOSE 80
