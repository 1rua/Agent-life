import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { chmod, lstat, mkdir, rm } from "node:fs/promises";
import { dirname } from "node:path";
import { LocalPairingTicketVerifier } from "./local-pairing-ticket-verifier.js";
import { NodeSqliteBridgeAdapter } from "./node-sqlite-adapter.js";
import { createFencedDurableBridgeComposition } from "./composition.js";
import { BridgeHealth } from "./health.js";
import { createRuntimeHttpHandler, type RuntimeHttpResponse } from "./runtime-http.js";
import { PAIRING_TICKET_ENVELOPE } from "./local-pairing-ticket-verifier.js";

const NODE_VERSION = "24.18.0";
const SQLITE_VERSION = "3.53.1";
const MAX_CONTROL_BODY_BYTES = 1_048_576;

const requiredAbsolute = (name: string): string => {
  const value = process.env[name];
  if (value === undefined || value.length === 0 || !value.startsWith("/")) {
    throw new Error(`${name} must be an absolute path`);
  }
  return value;
};

const json = (statusCode: number, value: unknown, statusMessage?: string): RuntimeHttpResponse => Object.freeze({
  statusCode,
  headers: Object.freeze({
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    ...(statusMessage === undefined ? {} : { "x-open-android-intelligence-error": statusMessage }),
  }),
  body: JSON.stringify(value),
});

const readBody = async (request: IncomingMessage): Promise<Uint8Array> => {
  const chunks: Uint8Array[] = [];
  let length = 0;
  for await (const chunk of request) {
    const bytes = chunk instanceof Uint8Array ? chunk : Buffer.from(chunk);
    length += bytes.byteLength;
    if (length > MAX_CONTROL_BODY_BYTES) throw new Error("CONTROL_BODY_TOO_LARGE");
    chunks.push(bytes);
  }
  const output = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    output.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return output;
};

const runtimeMain = async (): Promise<void> => {
  if (process.versions.node !== NODE_VERSION || process.versions.sqlite !== SQLITE_VERSION) {
    throw new Error("SQLITE_DRIVER_LOCK_MISMATCH");
  }
  const databasePath = requiredAbsolute("OPEN_ANDROID_INTELLIGENCE_DATABASE");
  const publicPath = requiredAbsolute("OPEN_ANDROID_INTELLIGENCE_PAIRING_PUBLIC_KEY");
  const socketPath = requiredAbsolute("OPEN_ANDROID_INTELLIGENCE_RUNTIME_SOCKET");
  const leaseTtlMs = Number(process.env.OPEN_ANDROID_INTELLIGENCE_LEASE_TTL_MS ?? "30000");
  if (!Number.isSafeInteger(leaseTtlMs) || leaseTtlMs < 1_000) throw new Error("LEASE_TTL_INVALID");

  await mkdir(dirname(databasePath), { recursive: true, mode: 0o750 });
  await mkdir(dirname(publicPath), { recursive: true, mode: 0o750 });
  await mkdir(dirname(socketPath), { recursive: true, mode: 0o750 });
  const oldSocket = await lstat(socketPath).catch(() => null);
  if (oldSocket !== null && !oldSocket.isSocket()) throw new Error("RUNTIME_SOCKET_PATH_INVALID");
  if (oldSocket?.isSocket() === true) await rm(socketPath, { force: true });

  const persistence = await NodeSqliteBridgeAdapter.open({ databasePath, ownerId: `bridge-${process.pid}` });
  const pairingVerifier = await LocalPairingTicketVerifier.open({ publicPath });
  const leases = persistence.createLeaseCoordinator();
  const composition = await createFencedDurableBridgeComposition({
    persistence,
    leases,
    pairingVerifier,
    ownerId: `bridge-${process.pid}`,
    leaseTtlMs,
  });
  const health = new BridgeHealth([
    { name: "sqlite", check: async () => (await persistence.recover()).schemaVersion === 1
      ? { ok: true } : { ok: false, reason: "SCHEMA_VERSION_INVALID" } },
    { name: "lease", check: async () => {
      await composition.renewLease();
      return { ok: true };
    } },
    { name: "pairing-secret", check: async () => {
      const stat = await lstat(publicPath);
      return stat.isFile() && (stat.mode & 0o222) === 0 ? { ok: true } : { ok: false, reason: "SECRET_STORE_PERMISSION_INVALID" };
    } },
  ]);
  const handler = createRuntimeHttpHandler({
    health,
    control: async (request) => {
      if (request.path !== "/v1/control" || request.method !== "POST") {
        return json(404, { error: "BRIDGE_ROUTE_NOT_FOUND" });
      }
      let decoded: unknown;
      try {
        decoded = JSON.parse(request.body);
      } catch {
        return json(400, { error: "BRIDGE_CONTROL_BODY_INVALID" });
      }
      if (typeof decoded !== "object" || decoded === null || Array.isArray(decoded)
        || (decoded as Record<string, unknown>).envelope !== PAIRING_TICKET_ENVELOPE) {
        return json(400, { error: "BRIDGE_CONTROL_UNSUPPORTED" });
      }
      const verified = await pairingVerifier.verify(decoded);
      if (verified.bridgeFingerprint !== request.peerFingerprint) {
        return json(403, { error: "TAILNET_PEER_BINDING_MISMATCH" });
      }
      await composition.pairing.accept(decoded);
      return { statusCode: 204 };
    },
  });
  const server = createServer(async (request, response) => {
    try {
      const body = request.method === "POST" ? await readBody(request) : undefined;
      const headers: Record<string, string | readonly string[]> = {};
      for (const [name, value] of Object.entries(request.headers)) {
        if (value !== undefined) headers[name] = value;
      }
      const result = await handler({ method: request.method ?? "", path: request.url ?? "/", headers, body });
      response.writeHead(result.statusCode, result.headers ?? {});
      response.end(result.body === undefined ? undefined : Buffer.from(result.body));
    } catch {
      response.writeHead(413, { "content-type": "application/json", "cache-control": "no-store" });
      response.end(JSON.stringify({ error: "BRIDGE_CONTROL_BODY_TOO_LARGE" }));
    }
  });
  let closing = false;
  const close = async (): Promise<void> => {
    if (closing) return;
    closing = true;
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await composition.close().catch(() => undefined);
    await persistence.close().catch(() => undefined);
    await rm(socketPath, { force: true });
  };
  server.once("error", (error) => {
    console.error(JSON.stringify({ event: "runtime_server_failed", error: error.name }));
    void close();
  });
  process.once("SIGINT", () => void close());
  process.once("SIGTERM", () => void close());
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(socketPath, resolve);
  });
  await chmod(socketPath, 0o660);
  console.log(JSON.stringify({
    event: "bridge_runtime_ready",
    database: databasePath,
    socket: socketPath,
    pairingKey: pairingVerifier.keyId,
    driver: persistence.driver,
  }));
};

runtimeMain().catch((error: NodeJS.ErrnoException) => {
  console.error(JSON.stringify({ event: "bridge_runtime_failed", error: error.message }));
  process.exitCode = 1;
});
