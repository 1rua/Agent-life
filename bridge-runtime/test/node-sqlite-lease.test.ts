import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  BRIDGE_LEASE_COORDINATOR_PORT,
  type BridgeLease,
} from "../src/production-ports.js";
import { openNodeSqliteBridgeAdapter } from "../src/node-sqlite-adapter.js";

const root = await mkdtemp(join(tmpdir(), "agent-life-node-sqlite-lease-"));
let clock = 0;
let adapter = await openNodeSqliteBridgeAdapter({
  databasePath: join(root, "bridge.sqlite"),
  ownerId: "owner-a",
  clock: () => clock,
});
const leases = adapter.createLeaseCoordinator();

afterAll(async () => {
  await adapter.close();
  await rm(root, { recursive: true, force: true });
});

describe("Node SQLite lease coordinator", () => {
  it("is connected and issues a monotonic lease", async () => {
    expect(leases.port).toBe(BRIDGE_LEASE_COORDINATOR_PORT);
    expect(leases.status).toBe("connected");
    const lease = await leases.acquire({ scope: "bridge.runtime", ownerId: "owner-a", ttlMs: 100 });
    expect(lease.fencingToken).toBe(1n);
    expect(lease.expiresAtMs).toBe(100);
    await expect(leases.renew(lease)).resolves.toMatchObject({ fencingToken: 1n });
  });

  it("rejects a different owner before expiry and fences stale workers after takeover", async () => {
    await expect(leases.acquire({ scope: "bridge.runtime", ownerId: "owner-b", ttlMs: 100 }))
      .rejects.toMatchObject({ code: "BRIDGE_LEASE_BUSY" });
    clock = 101;
    const takeover = await leases.acquire({ scope: "bridge.runtime", ownerId: "owner-b", ttlMs: 100 });
    expect(takeover.fencingToken).toBe(2n);

    let workCalled = false;
    const stale: BridgeLease = { scope: "bridge.runtime", ownerId: "owner-a", fencingToken: 1n, expiresAtMs: 100 };
    await expect(leases.transact(stale, "lease.test.stale", async () => {
      workCalled = true;
    })).rejects.toMatchObject({ code: "BRIDGE_LEASE_FENCED" });
    expect(workCalled).toBe(false);

    await expect(leases.renew(stale)).rejects.toMatchObject({ code: "BRIDGE_LEASE_FENCED" });
    await expect(leases.transact(takeover, "lease.test.current", async (transaction) =>
      transaction.read("assistant.metadata", "missing"))).resolves.toBeNull();
    await expect(leases.release(takeover)).resolves.toBeUndefined();
  });
});

describe("Node SQLite multi-connection lease coordinator", () => {
  it("fences another adapter connection after lease takeover", async () => {
    const secondPath = join(root, "bridge.sqlite");
    let clockTwo = 1_000;
    const first = await openNodeSqliteBridgeAdapter({
      databasePath: secondPath,
      ownerId: "connection-a",
      clock: () => clockTwo,
    });
    const second = await openNodeSqliteBridgeAdapter({
      databasePath: secondPath,
      ownerId: "connection-b",
      clock: () => clockTwo,
      busyTimeoutMs: 5_000,
    });
    try {
      const lease = await first.createLeaseCoordinator().acquire({
        scope: "bridge.multi-connection",
        ownerId: "connection-a",
        ttlMs: 1_000,
      });
      await expect(second.createLeaseCoordinator().acquire({
        scope: "bridge.multi-connection",
        ownerId: "connection-b",
        ttlMs: 1_000,
      })).rejects.toMatchObject({ code: "BRIDGE_LEASE_BUSY" });
      clockTwo = 2_001;
      const takeover = await second.createLeaseCoordinator().acquire({
        scope: "bridge.multi-connection",
        ownerId: "connection-b",
        ttlMs: 1_000,
      });
      expect(takeover.fencingToken).toBe(2n);
      await expect(first.createLeaseCoordinator().transact(lease, "lease.test.cross-process", async () => undefined))
        .rejects.toMatchObject({ code: "BRIDGE_LEASE_FENCED" });
    } finally {
      await first.close();
      await second.close();
    }
  });
});
