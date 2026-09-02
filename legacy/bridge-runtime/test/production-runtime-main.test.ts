import { generateKeyPairSync, sign, createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { tmpdir } from "node:os";
import { join } from "node:path";
import http from "node:http";
import { promisify } from "node:util";
import { afterAll, beforeAll, expect, it, vi } from "vitest";

const execFileAsync = promisify(execFile);
const root = await mkdtemp(join(tmpdir(), "agent-life-runtime-main-"));
const databasePath = join(root, "bridge.sqlite");
const publicPath = join(root, "pairing-public.pem");
const socketPath = join(root, "runtime.sock");
const { publicKey, privateKey } = generateKeyPairSync("ed25519");
await writeFile(publicPath, publicKey.export({ format: "pem", type: "spki" }).toString(), { mode: 0o444 });
const keyId = `sha256:${createHash("sha256").update(publicKey.export({ format: "der", type: "spki" })).digest("hex")}`;
const peerFingerprint = `sha256:${"a".repeat(64)}`;
const ticket = {
  ticketId: "ticket-runtime-main",
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  bridgeFingerprint: peerFingerprint,
  pairingGeneration: "1",
  policyAttestationRevision: "4",
  issuedAtMs: Date.now(),
  expiresAtMs: Date.now() + 60_000,
};
const payload = Buffer.from(JSON.stringify(ticket)).toString("base64url");
const signature = sign(null, Buffer.from(`agent-life.pairing-ticket/v1\n${keyId}\n${payload}`), privateKey).toString("base64url");
const body = JSON.stringify({ envelope: "agent-life.pairing-ticket/v1", keyId, payload, signature });

let child: ReturnType<typeof spawnMain>;

function spawnMain() {
  return import("node:child_process").then(({ spawn }) => spawn(process.execPath, [
    "--import",
    "tsx",
    join(import.meta.dirname, "../src/main.ts"),
  ], {
    env: {
      ...process.env,
      AGENT_LIFE_DATABASE: databasePath,
      AGENT_LIFE_PAIRING_PUBLIC_KEY: publicPath,
      AGENT_LIFE_RUNTIME_SOCKET: socketPath,
      AGENT_LIFE_LEASE_TTL_MS: "30000",
    },
    stdio: ["ignore", "pipe", "pipe"],
  }));
}

const request = (options: http.RequestOptions, body?: string) => new Promise<http.IncomingMessage>((resolve, reject) => {
  const request = http.request({ ...options, socketPath }, (response) => resolve(response));
  request.on("error", reject);
  if (body !== undefined) request.write(body);
  request.end();
});

beforeAll(async () => {
  child = await spawnMain();
  await vi.waitFor(async () => {
    const response = await request({ method: "GET", path: "/health/ready" });
    if (response.statusCode !== 200) throw new Error(`runtime not ready: ${response.statusCode}`);
    response.resume();
  }, { interval: 50, timeout: 5_000 });
}, 8_000);

afterAll(async () => {
  if (child?.exitCode === null) {
    child.kill("SIGTERM");
    await new Promise<void>((resolve) => {
      child.once("exit", () => resolve());
    });
  }
  await rm(root, { recursive: true, force: true });
});

it("serves a real Node SQLite runtime and accepts only a matching authenticated peer", async () => {
  const wrong = await request({
    method: "POST", path: "/v1/control", headers: {
      "x-agent-life-peer-fingerprint": `sha256:${"b".repeat(64)}`,
      "content-type": "application/json",
    },
  }, body);
  await new Promise((resolve) => wrong.resume().on("end", resolve));
  expect(wrong.statusCode).toBe(403);

  const accepted = await request({
    method: "POST", path: "/v1/control", headers: {
      "x-agent-life-peer-fingerprint": peerFingerprint,
      "content-type": "application/json",
    },
  }, body);
  await new Promise((resolve) => accepted.resume().on("end", resolve));
  expect(accepted.statusCode).toBe(204);

  const database = new DatabaseSync(databasePath, { readOnly: true });
  try {
    const rows = database.prepare("SELECT namespace, key FROM bridge_entries ORDER BY namespace").all() as { namespace: string; key: string }[];
    expect(rows.map((row) => row.namespace)).toEqual(["pairing.bindings", "pairing.tickets"]);
  } finally {
    database.close();
  }
});
