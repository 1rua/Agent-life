/** WP-06 local runtime adapter plus fail-closed ingress/health/migration ports;
 * no real network/auth adapter or production DB claim. */
export { createFileBackedBridgeStore, FileBackedBridgeStore, FILE_BACKED_BRIDGE_STORE_FORMAT, FILE_BACKED_BRIDGE_STORE_VERSION, openFileBackedBridgeStore, } from "./file-backed-store.js";
export { DURABLE_OPERATION_DISPATCHER_PORT, DurableOperationDispatcher, assertDurableOperationDispatcher, isDurableOperationDispatcher, } from "./durable-operation-dispatcher.js";
export { createDurableBridgeComposition, createFencedDurableBridgeComposition, } from "./composition.js";
export { DurableBridgeStateRepositories, DurableNotificationRepository, DurablePairingRepository, DurableSubscriptionRepository, } from "./durable-state-repositories.js";
export { BRIDGE_LEASE_COORDINATOR_PORT, PAIRING_TICKET_VERIFIER_PORT, assertBridgeLease, assertConnectedBridgeLeaseCoordinator, assertConnectedPairingTicketVerifier, } from "./production-ports.js";
export { runBridgeBackupRestoreDrill } from "./backup-restore-drill.js";
export { BridgeIngress, ConnectionGenerationFence, MemoryReplayAdmission, } from "./ingress.js";
export { BridgeHealth, createHealthHttpHandler } from "./health.js";
export { MigrationRunner, runBridgeMigrations } from "./migration-runner.js";
