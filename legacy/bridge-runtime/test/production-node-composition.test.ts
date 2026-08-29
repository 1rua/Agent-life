import { generateKeyPairSync, sign } from "node:crypto";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, describe, expect, it } from "vitest";
import { createFencedDurableBridgeComposition } from "../src/composition.js";
import { LocalPairingTicketVerifier } from "../src/local-pairing-ticket-verifier.js";
import { openNodeSqliteBridgeAdapter } from "../src/node-sqlite-adapter.js";

const root = await mkdtemp(join(tmpdir(), "agent-life-production-node-"));
const publicPath = join(root, "public.pem");
const { publicKey, privateKey } = generateKeyPairSync("ed25519");
await mkdir(root, { recursive: true });
await writeFile(publicPath, publicKey.export({ format: "pem", type: "spki" }).toString(), { mode: 0o444 });

const envelope = async (): Promise<unknown> => {
  const verifier = await LocalPairingTicketVerifier.open({ publicPath, clock: () => 2_000 });
  const payload = Buffer.from(JSON.stringify({
    ticketId: "ticket-a",
    tenantId: "tenant-a",
    humanPrincipalId: "human-a",
    deviceId: "device-a",
    bridgeFingerprint: "sha256:bridge-a",
    pairingGeneration: "1",
    policyAttestationRevision: "4",
    issuedAtMs: 1_000,
    expiresAtMs: 10_000,
  })).toString("base64url");
  return {
    envelope: "agent-life.pairing-ticket/v1",
    keyId: verifier.keyId,
    payload,
    signature: sign(null, Buffer.from(`agent-life.pairing-ticket/v1\n${verifier.keyId}\n${payload}`), privateKey).toString("base64url"),
  };
};

afterAll(async () => {
  await rm(root, { recursive: true, force: true });
});

describe("Node SQLite production composition", () => {
  it("verifies, persists, and reopens pairing state through the locked stack", async () => {
    const databasePath = join(root, "bridge.sqlite");
    const adapter = await openNodeSqliteBridgeAdapter({ databasePath, ownerId: "worker-a" });
    const verifier = await LocalPairingTicketVerifier.open({ publicPath, clock: () => 2_000 });
    const composition = await createFencedDurableBridgeComposition({
      persistence: adapter,
      leases: adapter.createLeaseCoordinator(),
      pairingVerifier: verifier,
      ownerId: "worker-a",
      leaseTtlMs: 5_000,
      clock: () => 2_000,
    });
    expect(composition.productionClaim).toBe("single-host-production");
    expect(composition.pendingDependencyLocks).toEqual([]);
    await expect(composition.pairing.accept(await envelope())).resolves.toMatchObject({
      tenantId: "tenant-a",
      pairingGeneration: 1n,
    });
    await expect(composition.pairing.current({
      tenantId: "tenant-a",
      humanPrincipalId: "human-a",
      deviceId: "device-a",
    })).resolves.toMatchObject({ policyAttestationRevision: 4n });
    await composition.close();

    const reopened = await createFencedDurableBridgeComposition({
      persistence: adapter,
      leases: adapter.createLeaseCoordinator(),
      pairingVerifier: verifier,
      ownerId: "worker-b",
      leaseTtlMs: 5_000,
      clock: () => 2_000,
    });
    await expect(reopened.pairing.current({
      tenantId: "tenant-a",
      humanPrincipalId: "human-a",
      deviceId: "device-a",
    })).resolves.toMatchObject({ bridgeFingerprint: "sha256:bridge-a" });
    await reopened.close();
    await adapter.close();
  });
});
