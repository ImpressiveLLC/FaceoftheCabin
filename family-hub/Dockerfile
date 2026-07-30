FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Explicit allowlist, not `COPY . .` — this directory also holds dev/test
# tooling (package.json, test/) that must never be served publicly.
COPY family-hub.html css2.css google.svg hodgson-crest.svg /usr/share/nginx/html/
EXPOSE 80
