import {
  assertDurableBridgeStore,
  type DurableBridgeStore,
} from "../../bridge-contract/src/durable-store.js";
import {
  NotificationService,
  type NotificationServiceOptions,
} from "../../bridge-contract/src/notification-service.js";
import { PairingService } from "../../bridge-contract/src/pairing-service.js";
import { BridgeServiceError, type Authorize } from "../../bridge-contract/src/service-types.js";
import { NotificationStore } from "../../bridge-contract/src/notification-store.js";
import { SubscriptionStore } from "../../bridge-contract/src/subscription-store.js";
import {
  assertDurableOperationDispatcher,
  DurableOperationDispatcher,
  type DurableOperationDispatcher as DurableOperationDispatcherType,
} from "./durable-operation-dispatcher.js";

export type DurableBridgeCompositionOptions = Readonly<{
  /** Unknown is intentional: an unmarked process-local store must fail closed. */
  durableStore: unknown;
  pairing: PairingService;
  /** Omit to open a dispatcher from the supplied durable store. */
  operations?: unknown;
  /** These remain explicit process-local fixture seams until their adapters exist. */
  notificationStore?: NotificationStore;
  subscriptionStore?: SubscriptionStore;
  authorize?: Authorize;
}>;

export type DurableBridgeComposition = Readonly<{
  durableStore: DurableBridgeStore;
  operations: DurableOperationDispatcherType;
  notifications: NotificationService;
  /** The only service namespace persisted by this source/runtime slice. */
  durableNamespaces: readonly ["operation.claims"];
  /** Domains still process-local and therefore not production-ready. */
  processLocalNamespaces: readonly [
    "pairing.tickets",
    "pairing.bindings",
    "notification.records",
    "notification.positions",
    "subscription.bindings",
    "subscription.events",
  ];
}>;

/**
 * Explicit Bridge composition root for the currently durable service slice.
 *
 * The durable store and operation dispatcher are mandatory at this boundary;
 * passing `NotificationStore` or `OperationDispatcher` as either one fails
 * closed. Pairing/notification/subscription stores are accepted only as
 * explicitly named process-local fixture seams and are surfaced in the
 * returned metadata. This is not a production deployment claim.
 */
export const createDurableBridgeComposition = async (
  options: DurableBridgeCompositionOptions,
): Promise<DurableBridgeComposition> => {
  if (!options || typeof options !== "object") throw new BridgeServiceError("BRIDGE_COMPOSITION_OPTIONS_INVALID");
  const durableStore = assertDurableBridgeStore(options.durableStore);
  const operations = options.operations === undefined
    ? await DurableOperationDispatcher.open({ store: durableStore })
    : assertDurableOperationDispatcher(options.operations);
  const notificationOptions: NotificationServiceOptions = {
    pairing: options.pairing,
    operations,
    ...(options.notificationStore === undefined ? {} : { store: options.notificationStore }),
    ...(options.subscriptionStore === undefined ? {} : { subscriptions: options.subscriptionStore }),
    ...(options.authorize === undefined ? {} : { authorize: options.authorize }),
  };
  return Object.freeze({
    durableStore,
    operations,
    notifications: new NotificationService(notificationOptions),
    durableNamespaces: Object.freeze(["operation.claims"] as const),
    processLocalNamespaces: Object.freeze([
      "pairing.tickets",
      "pairing.bindings",
      "notification.records",
      "notification.positions",
      "subscription.bindings",
      "subscription.events",
    ] as const),
  });
};
