/// <reference types="node" />
import { randomBytes, timingSafeEqual } from "node:crypto";
import { isValidP256PublicJwk, verifyEs256 } from "./crypto.js";
import { canonicalBytes, parseCanonicalJson, sha256B64Url, signingPreimage } from "./encoding.js";
import { loadMessageRegistry, } from "./message-registry.js";
import { parseSignatureDomain } from "./profile.js";
import { validateSchema } from "./schema-validator.js";
import { DeterministicConnectionFenceStore, fenceConnection, } from "./connection-fence.js";
import { DeterministicReplayLedger, DeterministicTrustedReplayReconciler, TASK5_RECEIPT_BYTE_BUDGET, buildDeterministicDeviceReplayMetadata, buildDeterministicAdapterReplayMetadata, canonicalReplayIntentMetadataBytes, } from "./replay-window.js";
export const TASK5_MAX_LIFETIME_SECONDS = Object.freeze({
    device_ping: 60,
    bridge_ping: 60,
    device_presence: 60,
    device_key_rotation: 300,
    device_key_rotation_ack: 300,
    bridge_key_rotation: 300,
    bridge_key_rotation_ack: 300,
    adapter_key_rotation: 300,
    adapter_key_rotation_ack: 300,
    device_event: 86_400,
    event_ack: 300,
});
const authenticatedIngressHandleBrand = Symbol("authenticated-ingress-handle");
const adapterCredentialLeaseBrand = Symbol("adapter-credential-lease");
const loadedTrustedBindingBrand = Symbol("loaded-trusted-binding");
const authenticatedBindingContextBrand = Symbol("authenticated-binding-context");
const deviceAdmissionBackendBrand = Symbol("device-admission-backend");
const adapterAdmissionBackendBrand = Symbol("adapter-admission-backend");
const exactWireBytesBrand = Symbol("exact-wire-bytes");
const verifiedSignedEnvelopeBrand = Symbol("verified-signed-envelope");
const acceptedTransportFrameBrand = Symbol("accepted-transport-frame");
const TASK5_MESSAGE_TYPES = new Set(Object.keys(TASK5_MAX_LIFETIME_SECONDS));
const registry = loadMessageRegistry();
const ingressOwners = new WeakMap();
const bindingOwners = new WeakMap();
const contextOwners = new WeakMap();
const backendOwners = new WeakMap();
const reconcilerOwners = new WeakMap();
const deepFreeze = (value) => {
    if (typeof value === "object" && value !== null && !Object.isFrozen(value)) {
        for (const member of Object.values(value))
            deepFreeze(member);
        Object.freeze(value);
    }
    return value;
};
const isRecord = (value) => typeof value === "object" && value !== null && !Array.isArray(value);
const retainedBytes = (source) => {
    const bytes = Uint8Array.from(source);
    return Object.freeze({
        byteLength: bytes.byteLength,
        copy: () => Uint8Array.from(bytes),
        [exactWireBytesBrand]: true,
    });
};
export const retainExactWireBytes = (source) => retainedBytes(source);
const equalBytes = (left, right) => left.byteLength === right.byteLength && timingSafeEqual(left, right);
const parseU64 = (value) => {
    if (typeof value !== "string" || !/^(0|[1-9][0-9]*)$/.test(value))
        return null;
    const parsed = BigInt(value);
    return parsed <= 18446744073709551615n ? parsed : null;
};
const signerRoleForDirection = (direction) => {
    if (direction === "app-to-bridge")
        return "device";
    if (direction === "adapter-to-bridge")
        return "adapter";
    return "bridge-command";
};
const replayRegistryIdentityFor = (messageType, entry) => Object.freeze({
    messageType,
    messageSchemaId: entry.schema_id,
    headerSchemaId: `urn:agent-life:protocol:v1:header:${messageType}`,
    envelopeSchemaId: `urn:agent-life:protocol:v1:envelope:${messageType}`,
    direction: entry.direction,
    signatureDomain: parseSignatureDomain(entry.signature_domain),
    signerRole: signerRoleForDirection(entry.direction),
});
const keyRingIdFor = (trusted, role) => ({
    owner: role === "device" ? "device-installation" : role,
    credentialId: trusted.credential.credentialId,
});
const schemaCheck = (schemaId, value) => {
    try {
        validateSchema(schemaId, value);
        return true;
    }
    catch {
        return false;
    }
};
const parseFamily = (rawWire, kind) => {
    let value;
    try {
        value = parseCanonicalJson(rawWire);
    }
    catch {
        return null;
    }
    const familySchema = kind === "device"
        ? "urn:agent-life:protocol:v1:control-envelope#/$defs/paired_device_family_envelope"
        : "urn:agent-life:protocol:v1:key-rotation#/$defs/adapter_family_envelope";
    if (!schemaCheck(familySchema, value) || !isRecord(value)
        || !isRecord(value.header) || !isRecord(value.payload) || typeof value.signature !== "string")
        return null;
    return { header: value.header, payload: value.payload, signature: value.signature };
};
const ownsIngress = (admission, ingress) => {
    const backend = backendOwners.get(admission);
    return backend !== undefined && backend.kind === ingress.kind && ingressOwners.get(ingress) === backend.token;
};
const isDeviceAdmission = (admission) => backendOwners.get(admission)?.kind === "device";
const isAdapterAdmission = (admission) => backendOwners.get(admission)?.kind === "adapter";
const isDeviceIngress = (ingress) => ingress.kind === "device";
const isAdapterIngress = (ingress) => ingress.kind === "adapter";
const liveKeyFor = (record, issuedAt, now) => {
    if (!record || !isValidP256PublicJwk(record.publicJwk))
        return false;
    const activatedAt = record.activatedAt === null ? null : Date.parse(record.activatedAt);
    const signingNotAfter = record.signingNotAfter === null ? null : Date.parse(record.signingNotAfter);
    if (record.lifecycle === "active") {
        if (activatedAt !== null && issuedAt < activatedAt)
            return false;
        return signingNotAfter === null || issuedAt <= signingNotAfter;
    }
    if (record.lifecycle !== "grace_verify_only" || activatedAt === null || record.liveVerifyUntil === null)
        return false;
    return issuedAt <= activatedAt && now < Date.parse(record.liveVerifyUntil);
};
const validateRotationProposal = (messageType, header, payload, ring, proposalDigest) => {
    if (!messageType.endsWith("key_rotation"))
        return true;
    const jwk = payload.new_public_jwk;
    if (!isRecord(jwk) || !isValidP256PublicJwk(jwk))
        return false;
    const newKeyId = jwk.kid;
    if (typeof newKeyId !== "string" || payload.old_key_id !== header.key_id
        || payload.old_key_id === newKeyId)
        return false;
    const thumbprint = sha256B64Url(canonicalBytes({ crv: jwk.crv, kty: jwk.kty, x: jwk.x, y: jwk.y }));
    if (payload.new_key_thumbprint !== thumbprint)
        return false;
    const retained = ring.keys.get(newKeyId);
    if (!retained)
        return true;
    const transcript = ring.pending?.transcript;
    return retained.lifecycle === "pending" && transcript !== undefined
        && transcript.rotationId === payload.rotation_id
        && transcript.oldKeyId === payload.old_key_id
        && equalBytes(canonicalBytes(transcript.newPublicJwk), canonicalBytes(jwk))
        && transcript.newKeyThumbprint === payload.new_key_thumbprint
        && transcript.challenge === payload.challenge
        && transcript.proposalDigest === proposalDigest
        && transcript.pairingGeneration === parseU64(header.pairing_generation);
};
const verifySignedInternal = async (rawWire, ingress, admission, keyRings, now) => {
    if (rawWire.byteLength > 262_144)
        return { ok: false, error: "MESSAGE_TOO_LARGE" };
    const wire = parseFamily(rawWire, ingress.kind);
    if (!wire)
        return { ok: false, error: "SCHEMA_INVALID" };
    const messageType = wire.header.message_type;
    if (typeof messageType !== "string" || !TASK5_MESSAGE_TYPES.has(messageType))
        return { ok: false, error: "SCHEMA_INVALID" };
    const entries = registry.messages.filter((entry) => entry.message_type === messageType);
    if (entries.length !== 1)
        return { ok: false, error: "SCHEMA_INVALID" };
    const entry = entries[0];
    const role = signerRoleForDirection(entry.direction);
    try {
        parseSignatureDomain(entry.signature_domain);
    }
    catch {
        return { ok: false, error: "SCHEMA_INVALID" };
    }
    const branchDirection = ingress.kind === "device"
        ? entry.direction === "app-to-bridge" || entry.direction === "bridge-to-app"
        : entry.direction === "adapter-to-bridge" || entry.direction === "bridge-to-adapter";
    if (!branchDirection || wire.header.message_schema !== entry.schema_id
        || wire.header.direction !== entry.direction)
        return { ok: false, error: "SCHEMA_INVALID" };
    if (!schemaCheck(`urn:agent-life:protocol:v1:envelope:${messageType}`, wire)) {
        return { ok: false, error: "SCHEMA_INVALID" };
    }
    if (!ownsIngress(admission, ingress))
        return { ok: false, error: "AUTH_FAILED" };
    if (!Object.is(keyRings, admission))
        return { ok: false, error: "AUTH_FAILED" };
    let trusted;
    try {
        if (isDeviceIngress(ingress) && isDeviceAdmission(admission)) {
            trusted = await admission.loadCommittedDeviceBinding(ingress);
        }
        else if (isAdapterIngress(ingress) && isAdapterAdmission(admission)) {
            trusted = await admission.loadCommittedAdapterBinding(ingress);
        }
        else {
            return { ok: false, error: "AUTH_FAILED" };
        }
    }
    catch {
        return { ok: false, error: "AUTH_FAILED" };
    }
    const issuedAt = Date.parse(wire.header.issued_at);
    let snapshot;
    try {
        snapshot = await keyRings.load(keyRingIdFor(trusted, role));
    }
    catch {
        return { ok: false, error: "AUTH_FAILED" };
    }
    const keyId = wire.header.key_id;
    if (typeof keyId !== "string")
        return { ok: false, error: "AUTH_FAILED" };
    const record = snapshot.state.keys.get(keyId);
    if (!record || record.keyId !== keyId || !liveKeyFor(record, issuedAt, now.getTime())) {
        return { ok: false, error: "AUTH_FAILED" };
    }
    const domain = parseSignatureDomain(entry.signature_domain);
    if (!verifyEs256(record.publicJwk, signingPreimage(domain, { header: wire.header, payload: wire.payload }), wire.signature)) {
        return { ok: false, error: "AUTH_FAILED" };
    }
    const expectedDigest = Buffer.from(sha256B64Url(canonicalBytes(wire.payload)), "base64url");
    const suppliedDigest = typeof wire.header.payload_digest === "string"
        ? Buffer.from(wire.header.payload_digest, "base64url") : new Uint8Array();
    if (!equalBytes(expectedDigest, suppliedDigest)
        || !validateRotationProposal(messageType, wire.header, wire.payload, snapshot.state, sha256B64Url(rawWire))) {
        return { ok: false, error: "INTEGRITY_FAILED" };
    }
    const expiresAt = Date.parse(wire.header.expires_at);
    const lifetimeMs = TASK5_MAX_LIFETIME_SECONDS[messageType] * 1000;
    const nowMs = now.getTime();
    if (!Number.isFinite(issuedAt) || !Number.isFinite(expiresAt)
        || expiresAt <= issuedAt || issuedAt > nowMs + 60_000 || expiresAt <= nowMs
        || expiresAt - issuedAt > lifetimeMs)
        return { ok: false, error: "MESSAGE_EXPIRED" };
    const header = deepFreeze(wire.header);
    const payload = deepFreeze(wire.payload);
    const envelope = deepFreeze({
        rawWire: retainedBytes(rawWire),
        messageType: messageType,
        header,
        payload,
        registryEntry: entry,
        signerRole: role,
        envelopeDigest: sha256B64Url(rawWire),
        [verifiedSignedEnvelopeBrand]: true,
    });
    return { ok: true, envelope, trusted };
};
export async function verifySignedEnvelope(rawWire, ingress, dependencies) {
    const now = dependencies.clock.wallNow();
    const decision = await verifySignedInternal(rawWire, ingress, dependencies.admission, dependencies.keyRings, now);
    return decision.ok ? { ok: true, envelope: decision.envelope } : decision;
}
export function verifyAuthenticatedBinding(envelope, trusted, inspection) {
    const headerGeneration = parseU64(envelope.header.connection_generation);
    if (headerGeneration === null
        || !fenceConnection(inspection, trusted.allocatedConnectionGeneration, headerGeneration).ok) {
        return { ok: false, error: "CONNECTION_FENCED" };
    }
    const credential = trusted.credential;
    const pairingGeneration = parseU64(envelope.header.pairing_generation);
    const direction = envelope.header.direction;
    if (!credential.active || envelope.header.device_id !== credential.deviceId
        || pairingGeneration !== credential.pairingGeneration
        || (direction !== "app-to-bridge" && direction !== "bridge-to-app")) {
        return { ok: false, error: "AUTH_BINDING_MISMATCH" };
    }
    const context = deepFreeze({
        kind: "device",
        credentialId: credential.credentialId,
        tenantId: credential.tenantId,
        humanPrincipalId: credential.humanPrincipalId,
        deviceId: credential.deviceId,
        pairingGeneration: credential.pairingGeneration,
        connectionGeneration: trusted.allocatedConnectionGeneration,
        direction,
        [authenticatedBindingContextBrand]: true,
    });
    const owner = bindingOwners.get(trusted);
    if (owner !== undefined)
        contextOwners.set(context, owner);
    return { ok: true, context };
}
const validScope = (scope) => /^[a-z][a-z0-9._-]{0,127}$/.test(scope);
export function verifyAdapterAdmission(envelope, trusted) {
    const generation = parseU64(envelope.header.adapter_credential_generation);
    if (generation === null || generation !== trusted.credential.generation) {
        return { ok: false, error: "CONNECTION_FENCED" };
    }
    const credential = trusted.credential;
    const principal = trusted.principal;
    if (principal.humanPrincipalId === null || principal.agentPrincipalId === null) {
        return { ok: false, error: "ADAPTER_PRINCIPAL_MISSING" };
    }
    const direction = envelope.header.direction;
    const scopes = [...credential.scopeCeiling];
    if (!credential.active || credential.agentPrincipalId === null
        || principal.agentPrincipalId !== credential.agentPrincipalId
        || envelope.header.adapter_credential_id !== credential.credentialId
        || (direction !== "adapter-to-bridge" && direction !== "bridge-to-adapter")
        || !scopes.every(validScope))
        return { ok: false, error: "AUTH_BINDING_MISMATCH" };
    scopes.sort();
    if (scopes.some((scope, index) => index > 0 && scope === scopes[index - 1])) {
        return { ok: false, error: "AUTH_BINDING_MISMATCH" };
    }
    Object.freeze(scopes);
    const context = deepFreeze({
        kind: "adapter",
        credentialId: credential.credentialId,
        adapterCredentialGeneration: credential.generation,
        tenantId: credential.tenantId,
        humanPrincipalId: principal.humanPrincipalId,
        agentPrincipalId: principal.agentPrincipalId,
        agentInstanceId: credential.agentInstanceId,
        workspaceId: credential.workspaceId,
        scopeCeiling: scopes,
        direction,
        [authenticatedBindingContextBrand]: true,
    });
    const owner = bindingOwners.get(trusted);
    if (owner !== undefined)
        contextOwners.set(context, owner);
    return { ok: true, context };
}
export async function verifyTransportFrame(rawWire, ingress, dependencies) {
    const now = dependencies.clock.wallNow();
    const verified = await verifySignedInternal(rawWire, ingress, dependencies.admission, dependencies.keyRings, now);
    if (!verified.ok)
        return verified;
    const admittedAt = now.toISOString();
    let replay;
    if (ingress.kind === "device" && verified.trusted.kind === "device" && isDeviceAdmission(dependencies.admission)) {
        const inspection = await dependencies.admission.inspect(verified.trusted.connectionLease);
        const binding = verifyAuthenticatedBinding(verified.envelope, verified.trusted, inspection);
        if (!binding.ok)
            return binding;
        if (binding.context.kind !== "device")
            return { ok: false, error: "AUTH_BINDING_MISMATCH" };
        if (!dependencies.authorization.evaluate(verified.envelope.messageType, verified.envelope.payload, binding.context).allowed) {
            return { ok: false, error: "NOT_AUTHORIZED" };
        }
        if (dependencies.preReplay) {
            const semantic = await dependencies.preReplay.evaluate(verified.envelope.messageType, verified.envelope.payload, binding.context);
            if (!semantic.allowed)
                return { ok: false, error: semantic.error };
        }
        replay = await dependencies.admission.admitDevice({
            envelope: verified.envelope,
            context: binding.context,
            connectionLease: verified.trusted.connectionLease,
            admittedAt,
        });
    }
    else if (ingress.kind === "adapter" && verified.trusted.kind === "adapter" && isAdapterAdmission(dependencies.admission)) {
        const inspection = await dependencies.admission.inspectAdapterLease(verified.trusted.credentialLease);
        if (inspection.kind !== "current" || inspection.generation !== verified.trusted.credential.generation) {
            return { ok: false, error: "CONNECTION_FENCED" };
        }
        const binding = verifyAdapterAdmission(verified.envelope, verified.trusted);
        if (!binding.ok)
            return binding;
        if (binding.context.kind !== "adapter")
            return { ok: false, error: "AUTH_BINDING_MISMATCH" };
        if (!dependencies.authorization.evaluate(verified.envelope.messageType, verified.envelope.payload, binding.context).allowed) {
            return { ok: false, error: "NOT_AUTHORIZED" };
        }
        if (dependencies.preReplay) {
            const semantic = await dependencies.preReplay.evaluate(verified.envelope.messageType, verified.envelope.payload, binding.context);
            if (!semantic.allowed)
                return { ok: false, error: semantic.error };
        }
        replay = await dependencies.admission.admitAdapter({
            envelope: verified.envelope,
            context: binding.context,
            adapterCredentialLease: verified.trusted.credentialLease,
            admittedAt,
        });
    }
    else {
        return { ok: false, error: "AUTH_BINDING_MISMATCH" };
    }
    if (replay.kind === "accepted")
        return { ok: true, kind: "accepted", frame: replay.frame };
    if (replay.kind === "duplicate")
        return { ok: true, kind: "duplicate", cachedReceipt: replay.cachedReceipt };
    return { ok: false, error: replay.error };
}
/** Executable reference backend for the device branch. It deliberately owns
 * every ingress, binding, context, fence lease, replay claim, and key-ring
 * snapshot that it accepts, making cross-instance substitution fail closed. */
export class DeterministicDeviceSecurityBackend {
    [deviceAdmissionBackendBrand] = true;
    #token = Object.freeze({});
    #credential;
    #fence;
    #bindings = new WeakMap();
    #leaseBindings = new WeakMap();
    #rings = new Map();
    #ringIds = new Map();
    #ledger;
    #frames = new WeakMap();
    #pendingFrames = new Map();
    #admittedAt = new Map();
    #replayLookups = 0n;
    #replayMutations = 0n;
    #beforeReplayCommit;
    #replayReferenceCheck = () => false;
    constructor(options) {
        this.#credential = deepFreeze({ ...options.credential });
        const fenceKey = {
            credentialId: this.#credential.credentialId,
            pairingGeneration: this.#credential.pairingGeneration,
        };
        this.#fence = new DeterministicConnectionFenceStore(fenceKey, {
            generation: options.initialConnection.generation,
            fenceRevision: options.initialConnection.fenceRevision,
            connectionId: options.initialConnection.connectionId ?? null,
            transportProfileId: options.initialConnection.transportProfileId ?? null,
            leasePersistenceId: options.initialConnection.leasePersistenceId ?? null,
        }, { leaseIdSource: options.leaseIdSource });
        this.#ledger = new DeterministicReplayLedger({ claimIdSource: options.claimIdSource });
        for (const { id, snapshot } of options.keyRings) {
            this.#rings.set(this.#ringKey(id), snapshot);
            this.#ringIds.set(this.#ringKey(id), Object.freeze({ ...id }));
        }
        this.#beforeReplayCommit = options.beforeReplayCommit;
        this.#replayReferenceCheck = options.replayReferenceCheck ?? (() => false);
        backendOwners.set(this, { kind: "device", token: this.#token });
    }
    static restart(snapshot) {
        const backend = new DeterministicDeviceSecurityBackend({
            credential: snapshot.credential,
            initialConnection: snapshot.connection,
            keyRings: snapshot.keyRings,
        });
        if (snapshot.replayLedger)
            backend.#ledger = DeterministicReplayLedger.restart(snapshot.replayLedger);
        const replayRows = snapshot.replayRows ?? snapshot.pendingRows;
        const claimIds = new Set();
        for (const pending of replayRows) {
            if (claimIds.has(pending.claim.claimId))
                throw new Error("INTEGRITY_FAILED");
            claimIds.add(pending.claim.claimId);
            backend.#restorePending(pending);
        }
        if (snapshot.replayLedger) {
            const expected = new Set(snapshot.replayLedger.rows.map((row) => row.claim.claimId));
            const restored = new Set(replayRows.map((row) => row.claim.claimId));
            if (expected.size !== restored.size || [...expected].some((claimId) => !restored.has(claimId))) {
                throw new Error("INTEGRITY_FAILED");
            }
        }
        return backend;
    }
    #restorePending(pending) {
        const parsed = parseCanonicalJson(pending.rawWire);
        if (!isRecord(parsed) || !isRecord(parsed.header) || !isRecord(parsed.payload) || typeof parsed.signature !== "string") {
            throw new Error("INTEGRITY_FAILED");
        }
        const entry = registry.messages.find((candidate) => candidate.message_type === pending.claim.messageType);
        if (!entry || sha256B64Url(pending.rawWire) !== pending.claim.envelopeDigest)
            throw new Error("INTEGRITY_FAILED");
        validateSchema(`urn:agent-life:protocol:v1:envelope:${pending.claim.messageType}`, parsed);
        const lease = this.#fence.restoreCurrentLease();
        const context = deepFreeze({
            kind: "device",
            ...pending.context,
            [authenticatedBindingContextBrand]: true,
        });
        contextOwners.set(context, this.#token);
        const envelope = deepFreeze({
            rawWire: retainedBytes(pending.rawWire),
            messageType: pending.claim.messageType,
            header: parsed.header,
            payload: parsed.payload,
            registryEntry: entry,
            signerRole: signerRoleForDirection(entry.direction),
            envelopeDigest: pending.claim.envelopeDigest,
            [verifiedSignedEnvelopeBrand]: true,
        });
        const retainedCharge = pending.retainedBytes
            ?? pending.rawWire.byteLength + pending.intentMetadataBytes.byteLength + TASK5_RECEIPT_BYTE_BUDGET;
        let claim = this.#ledger.findClaim(pending.claim.messageId);
        if (claim === null) {
            claim = this.#ledger.restorePending(pending.claim, retainedCharge);
            if (pending.status === "finalized" && pending.receipt) {
                if (this.#ledger.finalize(claim, retainedBytes(pending.receipt)) === "rejected")
                    throw new Error("INTEGRITY_FAILED");
            }
            else if (pending.status === "abandoned" && this.#ledger.abandon(claim) === "rejected") {
                throw new Error("INTEGRITY_FAILED");
            }
        }
        if (claim.claimId !== pending.claim.claimId
            || claim.envelopeDigest !== pending.claim.envelopeDigest
            || claim.sequence !== pending.claim.sequence
            || claim.messageType !== pending.claim.messageType
            || claim.space.kind !== pending.claim.space.kind)
            throw new Error("INTEGRITY_FAILED");
        const leasePersistenceId = this.#fence.persistenceId(lease);
        if (leasePersistenceId === null)
            throw new Error("INTEGRITY_FAILED");
        const rebuiltMetadata = buildDeterministicDeviceReplayMetadata({
            claim,
            registryIdentity: replayRegistryIdentityFor(pending.claim.messageType, entry),
            bindingSnapshot: context,
            connectionLease: lease,
            connectionLeasePersistenceId: leasePersistenceId,
            admittedAt: pending.admittedAt,
        });
        const rebuiltMetadataBytes = canonicalReplayIntentMetadataBytes(rebuiltMetadata);
        const persistedMetadataBytes = canonicalReplayIntentMetadataBytes(pending.persistedMetadata);
        if (!equalBytes(rebuiltMetadataBytes, pending.intentMetadataBytes)
            || !equalBytes(persistedMetadataBytes, pending.intentMetadataBytes)) {
            throw new Error("INTEGRITY_FAILED");
        }
        const frame = deepFreeze({
            envelope, context, claim, connectionLease: lease, [acceptedTransportFrameBrand]: true,
        });
        const trusted = deepFreeze({
            kind: "device",
            transport: pending.transport,
            transportProfileId: pending.transportProfileId,
            connectionId: pending.connectionId,
            allocatedConnectionGeneration: pending.context.connectionGeneration,
            connectionLease: lease,
            credential: this.#credential,
            [loadedTrustedBindingBrand]: true,
        });
        this.#leaseBindings.set(lease, trusted);
        this.#frames.set(claim, frame);
        const row = this.#ledger.row(claim);
        if (!row || (pending.status !== undefined && row.status !== pending.status)
            || (pending.receipt !== undefined && ((row.receipt === null) !== (pending.receipt === null)
                || (row.receipt !== null && pending.receipt !== null && !equalBytes(row.receipt, pending.receipt))))) {
            throw new Error("INTEGRITY_FAILED");
        }
        this.#pendingFrames.set(claim.claimId, frame);
        this.#admittedAt.set(claim.claimId, pending.admittedAt);
    }
    snapshot() {
        const replayRows = [...this.#pendingFrames.values()].map((frame) => {
            const trusted = this.#leaseBindings.get(frame.connectionLease);
            if (!trusted)
                throw new Error("INTEGRITY_FAILED");
            const admittedAt = this.#admittedAt.get(frame.claim.claimId);
            const leasePersistenceId = this.#fence.persistenceId(frame.connectionLease);
            if (!admittedAt || !leasePersistenceId)
                throw new Error("INTEGRITY_FAILED");
            const persistedMetadata = buildDeterministicDeviceReplayMetadata({
                claim: frame.claim,
                registryIdentity: replayRegistryIdentityFor(frame.envelope.messageType, frame.envelope.registryEntry),
                bindingSnapshot: frame.context,
                connectionLease: frame.connectionLease,
                connectionLeasePersistenceId: leasePersistenceId,
                admittedAt,
            });
            const ledgerRow = this.#ledger.row(frame.claim);
            if (!ledgerRow)
                throw new Error("INTEGRITY_FAILED");
            return Object.freeze({
                rawWire: frame.envelope.rawWire.copy(),
                admittedAt,
                transport: trusted.transport,
                transportProfileId: trusted.transportProfileId,
                connectionId: trusted.connectionId,
                context: Object.freeze({
                    credentialId: frame.context.credentialId,
                    tenantId: frame.context.tenantId,
                    humanPrincipalId: frame.context.humanPrincipalId,
                    deviceId: frame.context.deviceId,
                    pairingGeneration: frame.context.pairingGeneration,
                    connectionGeneration: frame.context.connectionGeneration,
                    direction: frame.context.direction,
                }),
                claim: Object.freeze({
                    claimId: frame.claim.claimId,
                    space: Object.freeze({ ...frame.claim.space }),
                    messageType: frame.claim.messageType,
                    messageId: frame.claim.messageId,
                    sequence: frame.claim.sequence,
                    envelopeDigest: frame.claim.envelopeDigest,
                    expiresAt: frame.claim.expiresAt,
                    retentionUntil: frame.claim.retentionUntil,
                }),
                persistedMetadata,
                intentMetadataBytes: canonicalReplayIntentMetadataBytes(persistedMetadata),
                status: ledgerRow.status,
                receipt: ledgerRow.receipt ? Uint8Array.from(ledgerRow.receipt) : null,
                retainedBytes: ledgerRow.retainedBytes,
            });
        });
        return Object.freeze({
            credential: this.#credential,
            connection: this.#fence.snapshot(),
            keyRings: Object.freeze([...this.#rings].map(([key, ring]) => Object.freeze({ id: this.#ringIds.get(key), snapshot: ring }))),
            pendingRows: Object.freeze(replayRows.filter((row) => row.status === "pending")),
            replayRows: Object.freeze(replayRows),
            replayLedger: this.#ledger.snapshot(),
        });
    }
    #ringKey(id) {
        return `${id.owner}\u0000${id.credentialId}`;
    }
    async authenticateDevice(input) {
        const allocated = await this.allocateNext({
            credentialId: this.#credential.credentialId,
            pairingGeneration: this.#credential.pairingGeneration,
        }, input.connectionId, input.transportProfileId);
        if (allocated.kind === "exhausted")
            throw new Error("CONNECTION_FENCED");
        const ingress = Object.freeze({
            kind: "device",
            handleId: input.handleId,
            [authenticatedIngressHandleBrand]: true,
        });
        const trusted = deepFreeze({
            kind: "device",
            transport: input.transport,
            transportProfileId: input.transportProfileId,
            connectionId: input.connectionId,
            allocatedConnectionGeneration: allocated.allocation.generation,
            connectionLease: allocated.allocation.lease,
            credential: this.#credential,
            [loadedTrustedBindingBrand]: true,
        });
        ingressOwners.set(ingress, this.#token);
        bindingOwners.set(trusted, this.#token);
        this.#bindings.set(ingress, trusted);
        this.#leaseBindings.set(allocated.allocation.lease, trusted);
        return Object.freeze({ ingress, allocation: allocated.allocation });
    }
    async loadCommittedDeviceBinding(handle) {
        if (ingressOwners.get(handle) !== this.#token)
            throw new Error("AUTH_FAILED");
        const binding = this.#bindings.get(handle);
        if (!binding)
            throw new Error("AUTH_FAILED");
        return binding;
    }
    allocateNext(key, connectionId, transportProfileId) {
        return this.#fence.allocateNext(key, connectionId, transportProfileId);
    }
    inspect(lease) {
        return this.#fence.inspect(lease);
    }
    async load(id) {
        const snapshot = this.#rings.get(this.#ringKey(id));
        if (!snapshot)
            throw new Error("AUTH_FAILED");
        return snapshot;
    }
    referenceOwnsContext(context) {
        return contextOwners.get(context) === this.#token;
    }
    /** Rotation recovery may inspect a claim only through the backend-owned
     * reconciler. It receives the retained frame/status, never caller-supplied
     * wire bytes or an unbranded claim. */
    replayClaimForReconciler(reconciler, claimId) {
        if (reconcilerOwners.get(reconciler) !== this.#token)
            return null;
        const frame = this.#pendingFrames.get(claimId);
        if (!frame)
            return null;
        const row = this.#ledger.row(frame.claim);
        return row ? Object.freeze({ frame, status: row.status, receipt: row.receipt ? retainExactWireBytes(row.receipt) : null }) : null;
    }
    ownsTrustedReconciler(reconciler) {
        return reconcilerOwners.get(reconciler) === this.#token;
    }
    referenceReplaceKeyRing(id, snapshot) {
        this.#rings.set(this.#ringKey(id), snapshot);
    }
    diagnostics() {
        return Object.freeze({ replayLookups: this.#replayLookups, replayMutations: this.#replayMutations });
    }
    capacityDiagnostics(space) {
        return this.#ledger.capacity(space);
    }
    setReplayReferenceCheck(check) {
        this.#replayReferenceCheck = check;
    }
    async admitDevice(request) {
        if (contextOwners.get(request.context) !== this.#token) {
            return { kind: "rejected", error: "CONNECTION_FENCED", denial: "DEVICE_LEASE_STALE" };
        }
        const inspection = await this.#fence.inspect(request.connectionLease);
        if (inspection.kind !== "current" || inspection.generation !== request.context.connectionGeneration) {
            return { kind: "rejected", error: "CONNECTION_FENCED", denial: "DEVICE_LEASE_STALE" };
        }
        await this.#beforeReplayCommit?.();
        const commitInspection = await this.#fence.inspect(request.connectionLease);
        if (commitInspection.kind !== "current" || commitInspection.generation !== request.context.connectionGeneration) {
            return { kind: "rejected", error: "CONNECTION_FENCED", denial: "DEVICE_LEASE_STALE" };
        }
        const space = Object.freeze({
            kind: "device",
            credentialId: request.context.credentialId,
            pairingGeneration: request.context.pairingGeneration,
            keyId: String(request.envelope.header.key_id),
            direction: request.context.direction,
        });
        const preview = this.#ledger.previewClaim(space, request.envelope, request.admittedAt);
        const leasePersistenceId = this.#fence.persistenceId(request.connectionLease);
        if (!preview || !leasePersistenceId) {
            return { kind: "rejected", error: "INTEGRITY_FAILED", denial: "MESSAGE_ID_CONFLICT" };
        }
        const previewMetadata = buildDeterministicDeviceReplayMetadata({
            claim: preview,
            registryIdentity: replayRegistryIdentityFor(request.envelope.messageType, request.envelope.registryEntry),
            bindingSnapshot: request.context,
            connectionLease: request.connectionLease,
            connectionLeasePersistenceId: leasePersistenceId,
            admittedAt: request.admittedAt,
        });
        const retainedByteCharge = request.envelope.rawWire.byteLength
            + canonicalReplayIntentMetadataBytes(previewMetadata).byteLength
            + TASK5_RECEIPT_BYTE_BUDGET;
        this.#replayLookups += 1n;
        const decision = this.#ledger.admit(space, request.envelope, request.admittedAt, retainedByteCharge, {
            now: new Date(request.admittedAt),
            canRemove: (claimId) => !this.#replayReferenceCheck(claimId),
        });
        if (decision.kind !== "accepted") {
            if (decision.kind === "duplicate" && decision.receipt !== null) {
                return { kind: "duplicate", cachedReceipt: decision.receipt };
            }
            return decision.kind === "duplicate"
                ? { kind: "rejected", error: "REPLAY_REJECTED", denial: "PENDING" }
                : decision.error === "INTEGRITY_FAILED"
                    ? { kind: "rejected", error: "INTEGRITY_FAILED", denial: "MESSAGE_ID_CONFLICT" }
                    : decision;
        }
        const frame = deepFreeze({
            envelope: request.envelope,
            context: request.context,
            claim: decision.claim,
            connectionLease: request.connectionLease,
            [acceptedTransportFrameBrand]: true,
        });
        this.#replayMutations += 1n;
        this.#frames.set(decision.claim, frame);
        this.#pendingFrames.set(decision.claim.claimId, frame);
        this.#admittedAt.set(decision.claim.claimId, request.admittedAt);
        return { kind: "accepted", frame };
    }
    async finalize(claim, receipt) {
        const result = this.#ledger.finalize(claim, receipt);
        return result === "rejected"
            ? { kind: "rejected", error: "INTEGRITY_FAILED" }
            : { kind: result };
    }
    createReconciler(reconcilerId) {
        const reconciler = new DeterministicTrustedReplayReconciler(reconcilerId);
        reconcilerOwners.set(reconciler, this.#token);
        return reconciler;
    }
    async loadPending(reconciler, claimId) {
        if (reconcilerOwners.get(reconciler) !== this.#token)
            return { kind: "not_found" };
        const frame = this.#pendingFrames.get(claimId);
        if (frame && this.#ledger.row(frame.claim)?.status === "pending")
            return { kind: "pending", frame };
        return { kind: "not_found" };
    }
    async resumePending(reconciler, claimId) {
        const loaded = await this.loadPending(reconciler, claimId);
        return loaded.kind === "pending"
            ? { kind: "resumed", frame: loaded.frame }
            : loaded;
    }
    async abandonPending(reconciler, claimId) {
        if (reconcilerOwners.get(reconciler) !== this.#token)
            return { kind: "not_found" };
        const frame = this.#pendingFrames.get(claimId);
        if (!frame)
            return { kind: "not_found" };
        const result = this.#ledger.abandon(frame.claim);
        return result === "abandoned" || result === "same"
            ? { kind: result }
            : { kind: "already_finalized" };
    }
    async compact(reconciler, space, clock) {
        if (reconcilerOwners.get(reconciler) !== this.#token)
            return { removedRows: 0n, removedRetainedBytes: 0n };
        const result = this.#ledger.compact(space, clock.wallNow(), (claimId) => !this.#replayReferenceCheck(claimId));
        for (const [claimId, frame] of this.#pendingFrames) {
            if (this.#ledger.findClaim(frame.claim.messageId) === null)
                this.#pendingFrames.delete(claimId);
        }
        return result;
    }
}
export class DeterministicAdapterSecurityBackend {
    [adapterAdmissionBackendBrand] = true;
    #token = Object.freeze({});
    #credential;
    #principal;
    #bindings = new WeakMap();
    #leases = new WeakMap();
    #leaseIds = new WeakMap();
    #leaseBindings = new WeakMap();
    #rings = new Map();
    #ringIds = new Map();
    #ledger;
    #frames = new Map();
    #admittedAt = new Map();
    #leaseIdSource;
    #restoredLeasePersistenceId;
    #beforeReplayCommit;
    #replayReferenceCheck = () => false;
    #replayLookups = 0n;
    #replayMutations = 0n;
    /**
     * Credential-generation changes are deliberately two-step.  Rotation
     * backends may enter the constructor-private transaction seam, but an
     * arbitrary caller cannot advance the authoritative generation by invoking
     * a public/protected mutator directly.  The depth guard also makes a crash
     * cut observable: no generation is changed until the owning rotation
     * journal has reached its commit point.
     */
    #credentialRotationDepth = 0;
    constructor(options) {
        const scopes = [...options.credential.scopeCeiling];
        if (!scopes.every(validScope))
            throw new Error("AUTH_BINDING_MISMATCH");
        scopes.sort();
        if (scopes.some((scope, index) => index > 0 && scope === scopes[index - 1]))
            throw new Error("AUTH_BINDING_MISMATCH");
        this.#credential = deepFreeze({ ...options.credential, scopeCeiling: Object.freeze(scopes) });
        this.#principal = deepFreeze({ ...options.principal });
        this.#leaseIdSource = options.leaseIdSource ?? (() => randomBytes(32).toString("base64url"));
        this.#restoredLeasePersistenceId = options.initialCredentialLeasePersistenceId ?? null;
        this.#ledger = new DeterministicReplayLedger({ claimIdSource: options.claimIdSource });
        this.#beforeReplayCommit = options.beforeReplayCommit;
        this.#replayReferenceCheck = options.replayReferenceCheck ?? (() => false);
        for (const { id, snapshot } of options.keyRings) {
            this.#rings.set(`${id.owner}\u0000${id.credentialId}`, snapshot);
            this.#ringIds.set(`${id.owner}\u0000${id.credentialId}`, Object.freeze({ ...id }));
        }
        backendOwners.set(this, { kind: "adapter", token: this.#token });
    }
    static restart(snapshot) {
        const backend = new DeterministicAdapterSecurityBackend({
            credential: snapshot.credential,
            principal: snapshot.principal,
            keyRings: snapshot.keyRings,
            initialCredentialLeasePersistenceId: snapshot.credentialLeasePersistenceId,
        });
        if (snapshot.replayLedger)
            backend.#ledger = DeterministicReplayLedger.restart(snapshot.replayLedger);
        const claimIds = new Set();
        for (const row of snapshot.replayRows) {
            if (claimIds.has(row.claim.claimId))
                throw new Error("INTEGRITY_FAILED");
            claimIds.add(row.claim.claimId);
            backend.#restoreRow(row);
        }
        if (snapshot.replayLedger) {
            const expected = new Set(snapshot.replayLedger.rows.map((row) => row.claim.claimId));
            const restored = new Set(snapshot.replayRows.map((row) => row.claim.claimId));
            if (expected.size !== restored.size || [...expected].some((claimId) => !restored.has(claimId))) {
                throw new Error("INTEGRITY_FAILED");
            }
        }
        return backend;
    }
    #mintLease(persistedId) {
        const lease = Object.freeze({ [adapterCredentialLeaseBrand]: true });
        const id = persistedId ?? this.#leaseIdSource();
        const decoded = Buffer.from(id, "base64url");
        if (decoded.byteLength !== 32 || Buffer.from(decoded).toString("base64url") !== id)
            throw new Error("INTEGRITY_FAILED");
        this.#leases.set(lease, this.#credential.generation);
        this.#leaseIds.set(lease, id);
        return lease;
    }
    persistenceId(lease) {
        return this.#leaseIds.get(lease) ?? null;
    }
    #restoreRow(row) {
        const parsed = parseCanonicalJson(row.rawWire);
        if (!isRecord(parsed) || !isRecord(parsed.header) || !isRecord(parsed.payload) || typeof parsed.signature !== "string")
            throw new Error("INTEGRITY_FAILED");
        const entry = registry.messages.find((candidate) => candidate.message_type === row.claim.messageType);
        if (!entry || sha256B64Url(row.rawWire) !== row.claim.envelopeDigest)
            throw new Error("INTEGRITY_FAILED");
        validateSchema(`urn:agent-life:protocol:v1:envelope:${row.claim.messageType}`, parsed);
        const claim = this.#ledger.findClaim(row.claim.messageId);
        if (!claim || claim.claimId !== row.claim.claimId || claim.envelopeDigest !== row.claim.envelopeDigest)
            throw new Error("INTEGRITY_FAILED");
        const leaseIdFromSnapshot = row.persistedMetadata.lease_ref.kind === "adapter_credential"
            ? row.persistedMetadata.lease_ref.adapter_credential_lease_id : null;
        if (!leaseIdFromSnapshot)
            throw new Error("INTEGRITY_FAILED");
        const lease = this.#mintLease(leaseIdFromSnapshot);
        const leaseId = this.persistenceId(lease);
        if (!leaseId)
            throw new Error("INTEGRITY_FAILED");
        const context = deepFreeze({
            kind: "adapter", ...row.context, [authenticatedBindingContextBrand]: true,
        });
        contextOwners.set(context, this.#token);
        const envelope = deepFreeze({
            rawWire: retainedBytes(row.rawWire), messageType: row.claim.messageType, header: parsed.header,
            payload: parsed.payload, registryEntry: entry, signerRole: signerRoleForDirection(entry.direction),
            envelopeDigest: row.claim.envelopeDigest, [verifiedSignedEnvelopeBrand]: true,
        });
        const rebuiltMetadata = buildDeterministicAdapterReplayMetadata({
            claim, registryIdentity: replayRegistryIdentityFor(row.claim.messageType, entry),
            bindingSnapshot: context, adapterCredentialLease: lease,
            adapterCredentialLeasePersistenceId: leaseId, admittedAt: row.admittedAt,
        });
        if (!equalBytes(canonicalReplayIntentMetadataBytes(rebuiltMetadata), row.intentMetadataBytes)
            || !equalBytes(canonicalReplayIntentMetadataBytes(row.persistedMetadata), row.intentMetadataBytes))
            throw new Error("INTEGRITY_FAILED");
        const frame = deepFreeze({
            envelope, context, claim, connectionLease: null, adapterCredentialLease: lease, [acceptedTransportFrameBrand]: true,
        });
        const trusted = deepFreeze({
            kind: "adapter", connectionId: row.connectionId, credential: this.#credential,
            principal: this.#principal, credentialLease: lease, [loadedTrustedBindingBrand]: true,
        });
        bindingOwners.set(trusted, this.#token);
        this.#leaseBindings.set(lease, trusted);
        this.#frames.set(claim.claimId, frame);
        this.#admittedAt.set(claim.claimId, row.admittedAt);
        const ledgerRow = this.#ledger.row(claim);
        if (!ledgerRow || (row.status !== undefined && ledgerRow.status !== row.status)
            || (row.receipt !== undefined && ((ledgerRow.receipt === null) !== (row.receipt === null)
                || (ledgerRow.receipt !== null && row.receipt !== null && !equalBytes(ledgerRow.receipt, row.receipt))))) {
            throw new Error("INTEGRITY_FAILED");
        }
    }
    snapshot() {
        const replayRows = [...this.#frames.values()].map((frame) => {
            const admittedAt = this.#admittedAt.get(frame.claim.claimId);
            const leaseId = this.persistenceId(frame.adapterCredentialLease);
            if (!admittedAt || !leaseId)
                throw new Error("INTEGRITY_FAILED");
            const persistedMetadata = buildDeterministicAdapterReplayMetadata({
                claim: frame.claim, registryIdentity: replayRegistryIdentityFor(frame.envelope.messageType, frame.envelope.registryEntry),
                bindingSnapshot: frame.context, adapterCredentialLease: frame.adapterCredentialLease,
                adapterCredentialLeasePersistenceId: leaseId, admittedAt,
            });
            const ledgerRow = this.#ledger.row(frame.claim);
            if (!ledgerRow)
                throw new Error("INTEGRITY_FAILED");
            return Object.freeze({
                rawWire: frame.envelope.rawWire.copy(), admittedAt,
                connectionId: this.#leaseBindings.get(frame.adapterCredentialLease)?.connectionId ?? "adapter-connection",
                context: Object.freeze({
                    credentialId: frame.context.credentialId, tenantId: frame.context.tenantId,
                    humanPrincipalId: frame.context.humanPrincipalId, agentPrincipalId: frame.context.agentPrincipalId,
                    agentInstanceId: frame.context.agentInstanceId, workspaceId: frame.context.workspaceId,
                    adapterCredentialGeneration: frame.context.adapterCredentialGeneration,
                    scopeCeiling: [...frame.context.scopeCeiling], direction: frame.context.direction,
                }),
                claim: Object.freeze({ ...frame.claim, space: Object.freeze({ ...frame.claim.space }) }),
                persistedMetadata, intentMetadataBytes: canonicalReplayIntentMetadataBytes(persistedMetadata),
                status: ledgerRow.status, receipt: ledgerRow.receipt ? Uint8Array.from(ledgerRow.receipt) : null, retainedBytes: ledgerRow.retainedBytes,
            });
        });
        const first = replayRows[0];
        return Object.freeze({
            credential: this.#credential, principal: this.#principal,
            connectionId: first?.connectionId ?? "adapter-connection",
            credentialLeasePersistenceId: first ? first.persistedMetadata.lease_ref.adapter_credential_lease_id : null,
            keyRings: Object.freeze([...this.#rings].map(([key, snapshot]) => Object.freeze({ id: this.#ringIds.get(key), snapshot }))),
            replayRows: Object.freeze(replayRows), replayLedger: this.#ledger.snapshot(),
        });
    }
    async authenticateAdapter(input) {
        const ingress = Object.freeze({
            kind: "adapter",
            handleId: input.handleId,
            [authenticatedIngressHandleBrand]: true,
        });
        const lease = this.#mintLease(this.#restoredLeasePersistenceId ?? undefined);
        this.#restoredLeasePersistenceId = null;
        const trusted = deepFreeze({
            kind: "adapter",
            connectionId: input.connectionId,
            credential: this.#credential,
            principal: this.#principal,
            credentialLease: lease,
            [loadedTrustedBindingBrand]: true,
        });
        ingressOwners.set(ingress, this.#token);
        bindingOwners.set(trusted, this.#token);
        this.#bindings.set(ingress, trusted);
        this.#leaseBindings.set(lease, trusted);
        return Object.freeze({ ingress, credentialLease: lease });
    }
    async loadCommittedAdapterBinding(handle) {
        if (ingressOwners.get(handle) !== this.#token)
            throw new Error("AUTH_FAILED");
        const binding = this.#bindings.get(handle);
        if (!binding)
            throw new Error("AUTH_FAILED");
        return binding;
    }
    async inspectAdapterLease(lease) {
        return this.#leases.get(lease) === this.#credential.generation
            ? { kind: "current", generation: this.#credential.generation }
            : { kind: "fenced" };
    }
    async load(id) {
        if (id.owner !== "adapter" || id.credentialId !== this.#credential.credentialId)
            throw new Error("AUTH_FAILED");
        const snapshot = this.#rings.get(`${id.owner}\u0000${id.credentialId}`);
        if (!snapshot)
            throw new Error("AUTH_FAILED");
        return snapshot;
    }
    /** Rotation backends extend this reference implementation and must use the
     * same authenticated context and ring tables as adapter admission. */
    referenceOwnsContext(context) {
        return contextOwners.get(context) === this.#token;
    }
    referenceReplaceKeyRing(id, snapshot) {
        if (id.owner !== "adapter" || id.credentialId !== this.#credential.credentialId)
            throw new Error("AUTH_FAILED");
        this.#rings.set(`${id.owner}\u0000${id.credentialId}`, snapshot);
    }
    replayClaimForReconciler(reconciler, claimId) {
        if (reconcilerOwners.get(reconciler) !== this.#token)
            return null;
        const frame = this.#frames.get(claimId);
        if (!frame)
            return null;
        const row = this.#ledger.row(frame.claim);
        return row ? Object.freeze({ frame, status: row.status, receipt: row.receipt ? retainExactWireBytes(row.receipt) : null }) : null;
    }
    ownsTrustedReconciler(reconciler) {
        return reconcilerOwners.get(reconciler) === this.#token;
    }
    diagnostics() {
        return Object.freeze({ replayLookups: this.#replayLookups, replayMutations: this.#replayMutations });
    }
    async admitAdapter(request) {
        if (contextOwners.get(request.context) !== this.#token
            || (await this.inspectAdapterLease(request.adapterCredentialLease)).kind !== "current") {
            return { kind: "rejected", error: "CONNECTION_FENCED", denial: "ADAPTER_LEASE_STALE" };
        }
        await this.#beforeReplayCommit?.();
        const commitLease = await this.inspectAdapterLease(request.adapterCredentialLease);
        if (commitLease.kind !== "current" || commitLease.generation !== request.context.adapterCredentialGeneration) {
            return { kind: "rejected", error: "CONNECTION_FENCED", denial: "ADAPTER_LEASE_STALE" };
        }
        const space = Object.freeze({
            kind: "adapter",
            credentialId: request.context.credentialId,
            adapterCredentialGeneration: request.context.adapterCredentialGeneration,
            keyId: String(request.envelope.header.key_id),
            direction: request.context.direction,
        });
        const preview = this.#ledger.previewClaim(space, request.envelope, request.admittedAt);
        const leaseId = this.persistenceId(request.adapterCredentialLease);
        if (!preview || !leaseId)
            return { kind: "rejected", error: "INTEGRITY_FAILED", denial: "MESSAGE_ID_CONFLICT" };
        const previewMetadata = buildDeterministicAdapterReplayMetadata({
            claim: preview, registryIdentity: replayRegistryIdentityFor(request.envelope.messageType, request.envelope.registryEntry),
            bindingSnapshot: request.context, adapterCredentialLease: request.adapterCredentialLease,
            adapterCredentialLeasePersistenceId: leaseId, admittedAt: request.admittedAt,
        });
        const retainedByteCharge = request.envelope.rawWire.byteLength
            + canonicalReplayIntentMetadataBytes(previewMetadata).byteLength + TASK5_RECEIPT_BYTE_BUDGET;
        this.#replayLookups += 1n;
        const decision = this.#ledger.admit(space, request.envelope, request.admittedAt, retainedByteCharge, {
            now: new Date(request.admittedAt),
            canRemove: (claimId) => !this.#replayReferenceCheck(claimId),
        });
        if (decision.kind !== "accepted") {
            if (decision.kind === "duplicate" && decision.receipt !== null)
                return { kind: "duplicate", cachedReceipt: decision.receipt };
            return decision.kind === "duplicate"
                ? { kind: "rejected", error: "REPLAY_REJECTED", denial: "PENDING" }
                : decision.error === "INTEGRITY_FAILED"
                    ? { kind: "rejected", error: "INTEGRITY_FAILED", denial: "MESSAGE_ID_CONFLICT" }
                    : decision;
        }
        const frame = deepFreeze({
            envelope: request.envelope,
            context: request.context,
            claim: decision.claim,
            connectionLease: null,
            adapterCredentialLease: request.adapterCredentialLease,
            [acceptedTransportFrameBrand]: true,
        });
        this.#frames.set(decision.claim.claimId, frame);
        this.#admittedAt.set(decision.claim.claimId, request.admittedAt);
        this.#replayMutations += 1n;
        return { kind: "accepted", frame };
    }
    runCredentialRotationTransaction(operation) {
        if (this.#credentialRotationDepth !== 0)
            throw new Error("INVALID_STATE_TRANSITION");
        this.#credentialRotationDepth = 1;
        try {
            return operation();
        }
        finally {
            this.#credentialRotationDepth = 0;
        }
    }
    commitCredentialGeneration(nextGeneration) {
        if (this.#credentialRotationDepth !== 1)
            throw new Error("AUTH_FAILED");
        if (nextGeneration !== this.#credential.generation + 1n)
            throw new Error("INVALID_STATE_TRANSITION");
        this.#credential = deepFreeze({ ...this.#credential, generation: nextGeneration });
    }
    /** Read-only view for a branch-owned rotation recovery transaction. */
    currentCredentialGeneration() {
        return this.#credential.generation;
    }
    capacityDiagnostics(space) {
        return this.#ledger.capacity(space);
    }
    setReplayReferenceCheck(check) {
        this.#replayReferenceCheck = check;
    }
    async finalize(claim, receipt) {
        const result = this.#ledger.finalize(claim, receipt);
        return result === "rejected"
            ? { kind: "rejected", error: "INTEGRITY_FAILED" }
            : { kind: result };
    }
    createReconciler(reconcilerId) {
        const reconciler = new DeterministicTrustedReplayReconciler(reconcilerId);
        reconcilerOwners.set(reconciler, this.#token);
        return reconciler;
    }
    async loadPending(reconciler, claimId) {
        if (reconcilerOwners.get(reconciler) !== this.#token)
            return { kind: "not_found" };
        const frame = this.#frames.get(claimId);
        return frame && this.#ledger.row(frame.claim)?.status === "pending"
            ? { kind: "pending", frame } : { kind: "not_found" };
    }
    async resumePending(reconciler, claimId) {
        const loaded = await this.loadPending(reconciler, claimId);
        return loaded.kind === "pending" ? { kind: "resumed", frame: loaded.frame } : loaded;
    }
    async abandonPending(reconciler, claimId) {
        if (reconcilerOwners.get(reconciler) !== this.#token)
            return { kind: "not_found" };
        const frame = this.#frames.get(claimId);
        if (!frame)
            return { kind: "not_found" };
        const result = this.#ledger.abandon(frame.claim);
        return result === "abandoned" || result === "same"
            ? { kind: result } : { kind: "already_finalized" };
    }
    async compact(reconciler, space, clock) {
        if (reconcilerOwners.get(reconciler) !== this.#token)
            return { removedRows: 0n, removedRetainedBytes: 0n };
        const result = this.#ledger.compact(space, clock.wallNow(), (claimId) => !this.#replayReferenceCheck(claimId));
        for (const [claimId, frame] of this.#frames)
            if (!this.#ledger.findClaim(frame.claim.messageId))
                this.#frames.delete(claimId);
        return result;
    }
}
export { allocateConnectionGeneration, fenceConnection } from "./connection-fence.js";
