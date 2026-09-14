# D19 Option B -- tiny standalone MQTT log-shipping side-car. Alpine +
# node:20 matches this project's existing node base (see docs/MAINTENANCE.md's
# Sprint 2 operational note on node:20-alpine); no native compile step is
# needed (pure-JS `mqtt` dependency only), so a single stage is enough.
FROM node:20-alpine

WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY agent.js ./

CMD ["node", "agent.js"]
