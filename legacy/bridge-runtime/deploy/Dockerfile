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
    OPEN_ANDROID_INTELLIGENCE_DATABASE=/var/lib/open-android-intelligence-bridge/bridge.sqlite \
    OPEN_ANDROID_INTELLIGENCE_PAIRING_PUBLIC_KEY=/var/lib/open-android-intelligence-bridge/secrets/pairing-ticket-public.pem \
    OPEN_ANDROID_INTELLIGENCE_RUNTIME_SOCKET=/run/open-android-intelligence/runtime.sock
RUN groupadd --system --gid 10000 open-android-intelligence \
  && useradd --system --uid 10002 --gid 10000 --home-dir /nonexistent --shell /usr/sbin/nologin open-android-intelligence-runtime \
  && mkdir -p /var/lib/open-android-intelligence-bridge /run/open-android-intelligence \
  && chown -R 10002:10000 /var/lib/open-android-intelligence-bridge /run/open-android-intelligence \
  && chmod 0750 /var/lib/open-android-intelligence-bridge /run/open-android-intelligence
WORKDIR /opt/open-android-intelligence-bridge
COPY --from=build --chown=10002:10000 /src/bridge-runtime/dist ./dist
USER 10002:10000
VOLUME ["/var/lib/open-android-intelligence-bridge"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["node", "-e", "const http=require('node:http');const r=http.request({socketPath:process.env.OPEN_ANDROID_INTELLIGENCE_RUNTIME_SOCKET,path:'/health/ready'},x=>process.exit(x.statusCode===200?0:1));r.on('error',()=>process.exit(1));r.end()"]
ENTRYPOINT ["node", "dist/bridge-runtime/src/main.js"]
