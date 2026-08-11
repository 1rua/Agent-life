/** WP-06 local runtime adapter plus fail-closed ingress/health/migration ports;
 * no real network/auth adapter or production DB claim. */
export {
  createFileBackedBridgeStore,
  FileBackedBridgeStore,
  FILE_BACKED_BRIDGE_STORE_FORMAT,
  FILE_BACKED_BRIDGE_STORE_VERSION,
  openFileBackedBridgeStore,
} from "./file-backed-store.js";
export {
  DURABLE_OPERATION_DISPATCHER_PORT,
  DurableOperationDispatcher,
  assertDurableOperationDispatcher,
  isDurableOperationDispatcher,
} from "./durable-operation-dispatcher.js";
export type { DurableOperationDispatcherOptions } from "./durable-operation-dispatcher.js";
export { createDurableBridgeComposition } from "./composition.js";
export type {
  DurableBridgeComposition,
  DurableBridgeCompositionOptions,
} from "./composition.js";
export type {
  FileBackedBridgeManifest,
  FileBackedBridgeStoreOptions,
  FileBackedRecoveryReport,
} from "./file-backed-store.js";
export {
  BridgeIngress,
  ConnectionGenerationFence,
  MemoryReplayAdmission,
} from "./ingress.js";
export type {
  BridgeIngressOptions,
  IngressControlFrame,
  IngressDispatch,
  IngressReceipt,
  IngressStatus,
  ReplayAdmission,
  ReplayLookup,
  TailscaleUserspaceListener,
  TsnetDependencyState,
} from "./ingress.js";
export { BridgeHealth, createHealthHttpHandler } from "./health.js";
export type {
  HealthCheck,
  HealthCheckResult,
  HealthHttpRequest,
  HealthHttpResponse,
  LiveHealth,
  ReadyHealth,
} from "./health.js";
export { MigrationRunner, runBridgeMigrations } from "./migration-runner.js";
export type { MigrationRunReport, MigrationStep } from "./migration-runner.js";
