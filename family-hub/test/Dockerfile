# QA runner for family-hub.html — self-contained, no host dependencies
# beyond Docker. Build context must be family-hub/ (the parent dir), since
# the test suite serves the real family-hub.html/css/svg files.
#
# Build: docker build -f test/Dockerfile -t family-hub-qa .
# Run:   docker run --rm family-hub-qa
#
# Base image version is pinned to match the "playwright" npm version in
# package.json exactly — Playwright requires the library and browser
# binaries to be the same version. Bump both together.
FROM mcr.microsoft.com/playwright:v1.62.0-noble

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .

CMD ["npm", "test"]
