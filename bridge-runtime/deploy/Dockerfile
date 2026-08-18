FROM node:22.23.0-bookworm-slim

ENV NODE_ENV=production \
    BRIDGE_INGRESS_TRANSPORT=tsnet-userspace \
    BRIDGE_INGRESS_PORT=443

WORKDIR /opt/agent-life-bridge
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev
COPY dist ./dist

RUN useradd --system --uid 10001 --create-home --home-dir /nonexistent agent-life-bridge \
    && mkdir -p /var/lib/agent-life-bridge \
    && chown -R agent-life-bridge:agent-life-bridge /var/lib/agent-life-bridge /opt/agent-life-bridge
USER agent-life-bridge
VOLUME ["/var/lib/agent-life-bridge"]

# The production image is only a process image. It does not publish a host
# port; a locked tsnet userspace adapter supplies the private listener.
ENTRYPOINT ["node", "dist/bridge-runtime/src/main.js"]