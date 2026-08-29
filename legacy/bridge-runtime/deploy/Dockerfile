# syntax=docker/dockerfile:1.7
ARG NODE_IMAGE=node:24.18.0-bookworm-slim@sha256:6f7b03f7c2c8e2e784dcf9295400527b9b1270fd37b7e9a7285cf83b6951452d

FROM ${NODE_IMAGE} AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY bridge-contract ./bridge-contract
COPY protocol ./protocol
COPY bridge-runtime/package.json bridge-runtime/package-lock.json bridge-runtime/tsconfig.json bridge-runtime/tsconfig.build.json ./bridge-runtime/
COPY bridge-runtime/src ./bridge-runtime/src
RUN npm --prefix bridge-runtime run build

FROM ${NODE_IMAGE} AS runtime
ENV NODE_ENV=production \
    AGENT_LIFE_DATABASE=/var/lib/agent-life-bridge/bridge.sqlite \
    AGENT_LIFE_PAIRING_PUBLIC_KEY=/var/lib/agent-life-bridge/secrets/pairing-ticket-public.pem \
    AGENT_LIFE_RUNTIME_SOCKET=/run/agent-life/runtime.sock
RUN groupadd --system --gid 10000 agent-life \
  && useradd --system --uid 10002 --gid 10000 --home-dir /nonexistent --shell /usr/sbin/nologin agent-life-runtime \
  && mkdir -p /var/lib/agent-life-bridge /run/agent-life \
  && chown -R 10002:10000 /var/lib/agent-life-bridge /run/agent-life \
  && chmod 0750 /var/lib/agent-life-bridge /run/agent-life
WORKDIR /opt/agent-life-bridge
COPY --from=build --chown=10002:10000 /src/bridge-runtime/dist ./dist
USER 10002:10000
VOLUME ["/var/lib/agent-life-bridge"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["node", "-e", "const http=require('node:http');const r=http.request({socketPath:process.env.AGENT_LIFE_RUNTIME_SOCKET,path:'/health/ready'},x=>process.exit(x.statusCode===200?0:1));r.on('error',()=>process.exit(1));r.end()"]
ENTRYPOINT ["node", "dist/bridge-runtime/src/main.js"]
