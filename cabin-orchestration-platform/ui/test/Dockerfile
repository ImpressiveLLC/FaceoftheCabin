# QA runner for cabin-ui's Vitest unit suite — self-contained, no host
# dependencies beyond Docker. Mirrors family-hub/test/Dockerfile's pattern
# (see that file's comment); simpler here since Vitest runs in jsdom, no
# real browser image needed the way family-hub's Playwright suite needs one.
#
# Build context is the REPO ROOT, not this ui/ directory (unlike
# family-hub/test/Dockerfile) -- src/ThemeProvider.test.jsx includes a
# cross-repo drift check comparing this app's theme catalog against
# family-hub.html's, so that file needs to be reachable inside the image
# too. See that test's own comment for why (found 2026-08-07: family-hub
# had 2 presets this app didn't, a real gap with no guard against it
# recurring).
#
# Build: docker build -f cabin-orchestration-platform/ui/test/Dockerfile -t cabin-ui-qa .   (run from repo root)
# Run:   docker run --rm cabin-ui-qa
FROM node:20-alpine

WORKDIR /app
COPY cabin-orchestration-platform/ui/package.json cabin-orchestration-platform/ui/package-lock.json ./
RUN npm ci
COPY cabin-orchestration-platform/ui/. .
COPY family-hub/family-hub.html /family-hub/family-hub.html

CMD ["npm", "test"]
