import { DeterministicDeviceSecurityBackend, DeterministicAdapterSecurityBackend, retainExactWireBytes, } from "./control-envelope.js";
import { DeterministicOutboundEnvelopeStore, } from "./outbound-envelope.js";
import { canonicalBytes, parseCanonicalJson, sha256B64Url } from "./encoding.js";
const deviceRotationBackendBrand = Symbol("device-rotation-backend");
const adapterRotationBackendBrand = Symbol("adapter-rotation-backend");
const parseRetainedEnvelope = (envelope) => {
    try {
        const value = parseCanonicalJson(envelope instanceof Uint8Array ? Uint8Array.from(envelope) : envelope.rawWire.copy());
        if (typeof value !== "object" || value === null || Array.isArray(value))
            return null;
        const candidate = value;
        if (typeof candidate.signature !== "string" || typeof candidate.header !== "object" || candidate.header === null || Array.isArray(candidate.header)
            || typeof candidate.payload !== "object" || candidate.payload === null || Array.isArray(candidate.payload))
            return null;
        return Object.freeze({
            header: candidate.header,
            payload: candidate.payload,
            signature: candidate.signature,
        });
    }
    catch {
        return null;
    }
};
const stringField = (payload, field) => typeof payload[field] === "string" ? payload[field] : null;
const sameJwk = (left, right) => {
    try {
        return sha256B64Url(canonicalBytes(left)) === sha256B64Url(canonicalBytes(right));
    }
    catch {
        return false;
    }
};
const keyThumbprint = (jwk) => sha256B64Url(canonicalBytes({
    crv: jwk.crv, kty: jwk.kty, x: jwk.x, y: jwk.y,
}));
const sameBytes = (left, right) => left.byteLength === right.byteLength && left.every((byte, index) => byte === right[index]);
const sameWire = (left, right) => left.messageType === right.messageType
    && left.messageId === right.messageId
    && left.sequence === right.sequence
    && left.envelopeDigest === right.envelopeDigest
    && sameBytes(left.rawWire.copy(), right.rawWire.copy());
const sameActivation = (left, right) => left !== null
    && left.factId === right.factId
    && left.rotationId === right.rotationId
    && left.owner === right.owner
    && left.credentialId === right.credentialId
    && left.pairingGeneration === right.pairingGeneration
    && left.oldAdapterCredentialGeneration === right.oldAdapterCredentialGeneration
    && left.nextAdapterCredentialGeneration === right.nextAdapterCredentialGeneration
    && left.oldKeyId === right.oldKeyId
    && left.newKeyId === right.newKeyId
    && left.proposalDigest === right.proposalDigest
    && left.activatedAt === right.activatedAt
    && left.retireAt === right.retireAt;
const keyRecordMatches = (left, right) => left !== undefined
    && left.keyId === right.keyId
    && left.lifecycle === right.lifecycle
    && left.activatedAt === right.activatedAt
    && left.signingNotAfter === right.signingNotAfter
    && left.liveVerifyUntil === right.liveVerifyUntil
    && sameJwk(left.publicJwk, right.publicJwk);
const rotationPayloadMatches = (envelope, transcript, expectedType, expectedDirection) => {
    const parsed = "rawWire" in envelope ? parseRetainedEnvelope(envelope) : envelope;
    if (!parsed || parsed.header.message_type !== expectedType || parsed.header.direction !== expectedDirection
        || stringField(parsed.header, "key_id") !== transcript.oldKeyId
        || stringField(parsed.payload, "rotation_id") !== transcript.rotationId
        || stringField(parsed.payload, "old_key_id") !== transcript.oldKeyId
        || stringField(parsed.payload, "new_key_thumbprint") !== transcript.newKeyThumbprint
        || stringField(parsed.payload, "challenge") !== transcript.challenge)
        return false;
    if (transcript.nextAdapterCredentialGeneration !== null
        && stringField(parsed.payload, "next_adapter_credential_generation") !== transcript.nextAdapterCredentialGeneration.toString())
        return false;
    if (!expectedType.endsWith("_ack")
        && (!sameJwk(parsed.payload.new_public_jwk, transcript.newPublicJwk)
            || keyThumbprint(transcript.newPublicJwk) !== transcript.newKeyThumbprint))
        return false;
    if (expectedType.endsWith("_ack")
        && (stringField(parsed.payload, "proposal_digest") !== transcript.proposalDigest
            || stringField(parsed.payload, "new_key_id") !== transcript.newPublicJwk.kid))
        return false;
    return true;
};
const activationFact = (id, transcript, ackPayload, activatedAt) => {
    const newKeyId = stringField(ackPayload, "new_key_id");
    if (!newKeyId)
        return null;
    const parsed = Date.parse(activatedAt);
    if (!Number.isFinite(parsed))
        return null;
    const retireAt = new Date(parsed + 900_000).toISOString();
    return Object.freeze({
        factId: `${id.owner}:${id.credentialId}:${transcript.rotationId}`,
        rotationId: transcript.rotationId,
        owner: id.owner,
        credentialId: id.credentialId,
        pairingGeneration: transcript.pairingGeneration,
        oldAdapterCredentialGeneration: transcript.oldAdapterCredentialGeneration,
        nextAdapterCredentialGeneration: transcript.nextAdapterCredentialGeneration,
        oldKeyId: transcript.oldKeyId,
        newKeyId,
        proposalDigest: transcript.proposalDigest,
        activatedAt,
        retireAt,
    });
};
const receiptFor = (branch, rotationId) => retainExactWireBytes(canonicalBytes({ status: `${branch}-rotation-committed`, rotation_id: rotationId }));
const deviceRotationStores = new WeakSet();
const adapterRotationStores = new WeakSet();
/** Device-branch reference store. Replay/fence/key-ring state comes from the
 * inherited physical backend while outbound bytes and rotation state are
 * committed through this single store capability. */
export class DeterministicDeviceRotationStore extends DeterministicDeviceSecurityBackend {
    [deviceRotationBackendBrand] = true;
    #outbound;
    #journals = new Map();
    #serialTail = Promise.resolve();
    #currentSigningKey;
    constructor(options) {
        super(options.security);
        this.#currentSigningKey = options.outbound.keyRecord;
        this.#outbound = new DeterministicOutboundEnvelopeStore({
            ...options.outbound,
            keyRecordFor: () => this.#currentSigningKey,
        }, options.outboundSnapshot);
        for (const journal of options.journalSnapshots ?? [])
            this.#journals.set(journal.journalId, journal);
        this.setReplayReferenceCheck((claimId) => [...this.#journals.values()].some((journal) => journal.proposalClaimId === claimId || journal.ackClaimId === claimId));
        deviceRotationStores.add(this);
    }
    static restart(snapshot, outbound) {
        if (!outbound || !("outbound" in snapshot) || !("journals" in snapshot))
            return super.restart(snapshot);
        return new DeterministicDeviceRotationStore({
            security: { credential: snapshot.credential, initialConnection: snapshot.connection, keyRings: snapshot.keyRings },
            outbound,
            outboundSnapshot: snapshot.outbound,
            journalSnapshots: snapshot.journals,
        });
    }
    snapshot() {
        return Object.freeze({
            ...super.snapshot(),
            outbound: this.#outbound.snapshot(),
            journals: Object.freeze([...this.#journals.values()]),
        });
    }
    prepareOrdinaryAtomically(input, context) {
        return this.#outbound.prepareOrdinaryAtomically(input, context);
    }
    prepareRotationAtomically(input, context) {
        return this.#outbound.prepareRotationAtomically(input, context);
    }
    loadOrdinarySendable(space, messageId) {
        return this.#outbound.loadOrdinarySendable(space, messageId);
    }
    loadRotationHandle(space, messageId) {
        return this.#outbound.loadRotationHandle(space, messageId);
    }
    async #withSerial(operation) {
        const previous = this.#serialTail;
        let release;
        this.#serialTail = new Promise((resolve) => { release = resolve; });
        await previous;
        try {
            return await operation();
        }
        finally {
            release();
        }
    }
    async applyAtomic(input, clock) {
        return this.#withSerial(() => this.#applyAtomic(input, clock));
    }
    async #applyAtomic(input, clock) {
        const snapshot = await this.load(input.id);
        const existing = input.kind === "prepare_local" || input.kind === "accept_remote_proposal" || input.kind === "accept_ack"
            ? this.#journals.get(input.kind === "prepare_local" ? input.transcript.rotationId
                : input.kind === "accept_remote_proposal" ? String(input.proposal.envelope.payload.rotation_id)
                    : String(input.ack.envelope.payload.rotation_id))
            : undefined;
        if (existing && input.kind !== "revoke" && input.kind !== "retire_due") {
            if (input.kind === "accept_remote_proposal" && existing.status === "remote_ack_committed") {
                return { ok: true, kind: "already_applied", cached: existing.cachedAck };
            }
            if (input.kind === "accept_ack" && existing.status === "local_activated") {
                return existing.activation ? { ok: true, kind: "activated", activation: existing.activation }
                    : { ok: false, error: "INVALID_STATE_TRANSITION" };
            }
            if (input.kind === "prepare_local" && existing.status === "local_prepared") {
                return existing.localProposal ? { ok: true, kind: "prepared", proposal: existing.localProposal }
                    : { ok: false, error: "INVALID_STATE_TRANSITION" };
            }
            return { ok: false, error: "AUTH_BINDING_MISMATCH" };
        }
        if (input.kind === "revoke") {
            const current = snapshot.state.keys.get(input.keyId);
            if (!current)
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            const keys = new Map(snapshot.state.keys);
            keys.set(input.keyId, Object.freeze({ ...current, lifecycle: "revoked" }));
            if (this.#currentSigningKey.keyId === input.keyId) {
                this.#currentSigningKey = Object.freeze({ ...this.#currentSigningKey, lifecycle: "revoked" });
            }
            this.referenceReplaceKeyRing(input.id, Object.freeze({
                storeRevision: snapshot.storeRevision + 1n,
                state: Object.freeze({
                    ...snapshot.state,
                    activeKeyId: snapshot.state.activeKeyId === input.keyId ? null : snapshot.state.activeKeyId,
                    keys,
                }),
            }));
            return { ok: true, kind: "revoked" };
        }
        if (input.kind === "retire_due") {
            const now = clock.wallNow().getTime();
            const keys = new Map(snapshot.state.keys);
            let changed = false;
            for (const [keyId, record] of keys) {
                if (record.lifecycle === "grace_verify_only" && record.liveVerifyUntil !== null
                    && Date.parse(record.liveVerifyUntil) <= now) {
                    keys.set(keyId, Object.freeze({ ...record, lifecycle: "archived", liveVerifyUntil: null }));
                    changed = true;
                }
            }
            if (changed)
                this.referenceReplaceKeyRing(input.id, Object.freeze({
                    storeRevision: snapshot.storeRevision + 1n,
                    state: Object.freeze({ ...snapshot.state, keys }),
                }));
            return { ok: true, kind: "retired" };
        }
        if (input.kind === "prepare_local") {
            if (!this.referenceOwnsContext(input.context) || !this.#outbound.ownsRotationHandle(input.proposal)
                || snapshot.state.pending !== null || snapshot.state.activeKeyId !== input.transcript.oldKeyId
                || snapshot.state.keys.has(input.transcript.newPublicJwk.kid)
                || input.proposal.envelopeDigest !== input.transcript.proposalDigest) {
                return { ok: false, error: "AUTH_BINDING_MISMATCH" };
            }
            const candidateWire = this.#outbound.rotationEnvelopeBytes(input.proposal);
            const parsedProposal = candidateWire ? parseRetainedEnvelope(candidateWire) : null;
            if (!parsedProposal)
                return { ok: false, error: "INTEGRITY_FAILED" };
            const proposalPayload = parsedProposal.payload;
            const proposedNew = proposalPayload.new_public_jwk;
            if (stringField(proposalPayload, "rotation_id") !== input.transcript.rotationId
                || stringField(proposalPayload, "old_key_id") !== input.transcript.oldKeyId
                || stringField(proposalPayload, "new_key_thumbprint") !== input.transcript.newKeyThumbprint
                || keyThumbprint(input.transcript.newPublicJwk) !== input.transcript.newKeyThumbprint
                || stringField(proposalPayload, "challenge") !== input.transcript.challenge
                || !sameJwk(proposedNew, input.transcript.newPublicJwk)
                || input.proposal.space.kind !== "device"
                || input.proposal.space.credentialId !== input.id.credentialId
                || input.proposal.space.pairingGeneration !== input.context.pairingGeneration) {
                return { ok: false, error: "AUTH_BINDING_MISMATCH" };
            }
            const keys = new Map(snapshot.state.keys);
            keys.set(input.transcript.newPublicJwk.kid, Object.freeze({
                keyId: input.transcript.newPublicJwk.kid,
                publicJwk: input.transcript.newPublicJwk,
                lifecycle: "pending",
                activatedAt: null,
                signingNotAfter: null,
                liveVerifyUntil: null,
            }));
            const journal = Object.freeze({
                journalId: input.transcript.rotationId,
                id: input.id,
                transcript: input.transcript,
                status: "local_prepared",
                localProposal: null,
                cachedAck: null,
                proposalClaimId: null,
                ackClaimId: null,
                activation: null,
            });
            this.#journals.set(journal.journalId, journal);
            const proposal = this.#outbound.commitRotationHandle(input.proposal);
            if (!proposal) {
                this.#journals.delete(journal.journalId);
                return { ok: false, error: "INTEGRITY_FAILED" };
            }
            this.#journals.set(journal.journalId, Object.freeze({ ...journal, localProposal: proposal }));
            this.referenceReplaceKeyRing(input.id, Object.freeze({
                storeRevision: snapshot.storeRevision + 1n,
                state: Object.freeze({
                    ...snapshot.state,
                    keys,
                    pending: Object.freeze({ transcript: input.transcript, localProposal: proposal, cachedAck: null, proposalClaimId: null, ackClaimId: null }),
                }),
            }));
            return { ok: true, kind: "prepared", proposal };
        }
        if (input.kind === "accept_remote_proposal") {
            const framePayload = input.proposal.envelope.payload;
            const rotationId = stringField(framePayload, "rotation_id");
            const oldKeyId = stringField(framePayload, "old_key_id");
            const challenge = stringField(framePayload, "challenge");
            const newThumbprint = stringField(framePayload, "new_key_thumbprint");
            const newJwk = framePayload.new_public_jwk;
            if (!rotationId || !oldKeyId || !challenge || !newThumbprint || !newJwk
                || !this.referenceOwnsContext(input.proposal.context)
                || input.proposal.context.credentialId !== input.id.credentialId
                || input.proposal.claim.space.kind !== "device"
                || input.proposal.claim.space.credentialId !== input.id.credentialId
                || input.proposal.envelope.messageType.endsWith("_ack")) {
                return { ok: false, error: "AUTH_BINDING_MISMATCH" };
            }
            const ack = this.#outbound.commitRotationHandle(input.ack);
            if (!ack)
                return { ok: false, error: "INTEGRITY_FAILED" };
            const parsedAck = parseRetainedEnvelope(ack);
            if (!parsedAck)
                return { ok: false, error: "INTEGRITY_FAILED" };
            const ackPayload = parsedAck.payload;
            if (!parsedAck.header || !stringField(ackPayload, "rotation_id")
                || stringField(ackPayload, "rotation_id") !== rotationId
                || stringField(ackPayload, "old_key_id") !== oldKeyId
                || stringField(ackPayload, "new_key_thumbprint") !== newThumbprint
                || stringField(ackPayload, "challenge") !== challenge
                || stringField(ackPayload, "proposal_digest") !== input.proposal.envelope.envelopeDigest
                || !this.#outbound.ownsRotationHandle(input.ack)
                || ack.space.kind !== "device"
                || ack.space.credentialId !== input.id.credentialId) {
                return { ok: false, error: "AUTH_BINDING_MISMATCH" };
            }
            const newKeyId = stringField(ackPayload, "new_key_id");
            if (!newKeyId || snapshot.state.activeKeyId !== oldKeyId || snapshot.state.keys.has(newKeyId)) {
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            }
            const transcript = Object.freeze({
                rotationId, oldKeyId, newPublicJwk: newJwk,
                newKeyThumbprint: newThumbprint, challenge,
                proposalDigest: input.proposal.envelope.envelopeDigest,
                pairingGeneration: input.proposal.context.pairingGeneration,
                oldAdapterCredentialGeneration: null, nextAdapterCredentialGeneration: null,
            });
            const activation = activationFact(input.id, transcript, ackPayload, String(parsedAck.header.issued_at));
            if (!activation)
                return { ok: false, error: "INTEGRITY_FAILED" };
            const keys = new Map(snapshot.state.keys);
            const old = keys.get(oldKeyId);
            if (!old)
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            keys.set(oldKeyId, Object.freeze({ ...old, lifecycle: "grace_verify_only", signingNotAfter: activation.activatedAt, liveVerifyUntil: activation.retireAt }));
            keys.set(newKeyId, Object.freeze({ keyId: newKeyId, publicJwk: newJwk, lifecycle: "active", activatedAt: activation.activatedAt, signingNotAfter: null, liveVerifyUntil: null }));
            const journal = Object.freeze({
                journalId: rotationId, id: input.id, transcript, status: "remote_ack_committed",
                localProposal: null, cachedAck: ack, proposalClaimId: input.proposal.claim.claimId,
                ackClaimId: null, activation,
            });
            const finalized = await this.finalize(input.proposal.claim, receiptFor("device", rotationId));
            if (finalized.kind === "rejected")
                return { ok: false, error: "INTEGRITY_FAILED" };
            this.referenceReplaceKeyRing(input.id, Object.freeze({ storeRevision: snapshot.storeRevision + 1n,
                state: Object.freeze({ ...snapshot.state, activeKeyId: newKeyId, keys, pending: null,
                    activationOutbox: new Map([...snapshot.state.activationOutbox, [activation.factId, activation]]) }),
            }));
            this.#journals.set(rotationId, journal);
            this.#currentSigningKey = keys.get(newKeyId);
            return { ok: true, kind: "acknowledged", ack, activation };
        }
        if (input.kind === "accept_ack") {
            if (!this.referenceOwnsContext(input.ack.context)
                || input.ack.context.credentialId !== input.id.credentialId
                || input.ack.envelope.messageType.endsWith("_rotation")
                || !snapshot.state.pending)
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            const payload = input.ack.envelope.payload;
            const pending = snapshot.state.pending.transcript;
            const rotationId = stringField(payload, "rotation_id");
            const newKeyId = stringField(payload, "new_key_id");
            if (!rotationId || !newKeyId || rotationId !== pending.rotationId
                || stringField(payload, "old_key_id") !== pending.oldKeyId
                || stringField(payload, "proposal_digest") !== pending.proposalDigest
                || stringField(payload, "challenge") !== pending.challenge
                || newKeyId !== pending.newPublicJwk.kid)
                return { ok: false, error: "AUTH_BINDING_MISMATCH" };
            const activation = activationFact(input.id, pending, payload, String(input.ack.envelope.header.issued_at));
            if (!activation)
                return { ok: false, error: "INTEGRITY_FAILED" };
            const old = snapshot.state.keys.get(pending.oldKeyId);
            const next = snapshot.state.keys.get(newKeyId);
            if (!old || !next || next.lifecycle !== "pending")
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            const keys = new Map(snapshot.state.keys);
            keys.set(old.keyId, Object.freeze({ ...old, lifecycle: "grace_verify_only", signingNotAfter: activation.activatedAt, liveVerifyUntil: activation.retireAt }));
            keys.set(newKeyId, Object.freeze({ ...next, lifecycle: "active", activatedAt: activation.activatedAt, signingNotAfter: null, liveVerifyUntil: null }));
            const journal = Object.freeze({
                journalId: rotationId, id: input.id, transcript: pending, status: "local_activated",
                localProposal: snapshot.state.pending.localProposal, cachedAck: null,
                proposalClaimId: snapshot.state.pending.proposalClaimId, ackClaimId: input.ack.claim.claimId, activation,
            });
            const finalized = await this.finalize(input.ack.claim, receiptFor("device", rotationId));
            if (finalized.kind === "rejected")
                return { ok: false, error: "INTEGRITY_FAILED" };
            this.referenceReplaceKeyRing(input.id, Object.freeze({ storeRevision: snapshot.storeRevision + 1n,
                state: Object.freeze({ ...snapshot.state, activeKeyId: newKeyId, keys, pending: null,
                    activationOutbox: new Map([...snapshot.state.activationOutbox, [activation.factId, activation]]) }),
            }));
            this.#journals.set(rotationId, journal);
            this.#currentSigningKey = keys.get(newKeyId);
            return { ok: true, kind: "activated", activation };
        }
        return { ok: false, error: "INVALID_STATE_TRANSITION" };
    }
    async loadJournal(journalId) {
        return this.#journals.get(journalId) ?? null;
    }
    async resumeJournal(reconciler, journalId, _clock) {
        return this.#withSerial(async () => {
            if (!this.ownsTrustedReconciler(reconciler))
                return { ok: false, error: "AUTH_FAILED" };
            const journal = this.#journals.get(journalId);
            if (!journal)
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            const snapshot = await this.load(journal.id);
            const transcript = journal.transcript;
            if (journal.journalId !== transcript.rotationId
                || transcript.pairingGeneration === null || transcript.oldAdapterCredentialGeneration !== null
                || transcript.nextAdapterCredentialGeneration !== null
                || transcript.proposalDigest.length === 0 || transcript.oldKeyId.length === 0
                || !snapshot.state.keys.has(transcript.oldKeyId)) {
                return { ok: false, error: "INTEGRITY_FAILED" };
            }
            const outboundMatches = async (envelope) => {
                const handle = await this.#outbound.loadRotationHandle(envelope.space, envelope.messageId);
                const retained = handle ? this.#outbound.rotationEnvelopeBytes(handle) : null;
                return retained !== null && sameBytes(retained, envelope.rawWire.copy())
                    && sha256B64Url(retained) === envelope.envelopeDigest;
            };
            const finalizedReference = (claimId, digest, expectedType, expectedDirection) => {
                if (!claimId)
                    return null;
                const reference = this.replayClaimForReconciler(reconciler, claimId);
                if (!reference || reference.status !== "finalized" || reference.receipt === null)
                    return null;
                const expectedReceipt = receiptFor("device", journalId);
                const parsedReference = parseRetainedEnvelope(reference.frame.envelope.rawWire.copy());
                if (!sameBytes(reference.receipt.copy(), expectedReceipt.copy())
                    || reference.frame.context.kind !== "device"
                    || reference.frame.context.credentialId !== journal.id.credentialId
                    || reference.frame.claim.claimId !== claimId
                    || (digest !== null && (reference.frame.claim.envelopeDigest !== digest
                        || reference.frame.envelope.envelopeDigest !== digest))
                    || reference.frame.envelope.messageType !== expectedType
                    || reference.frame.envelope.registryEntry.direction !== expectedDirection
                    || !parsedReference
                    || !rotationPayloadMatches(parsedReference, transcript, expectedType, expectedDirection))
                    return null;
                return reference;
            };
            const restoreCommitted = (activation) => {
                if (activation.rotationId !== transcript.rotationId || activation.owner !== journal.id.owner
                    || activation.credentialId !== journal.id.credentialId || activation.pairingGeneration !== transcript.pairingGeneration
                    || activation.oldAdapterCredentialGeneration !== null || activation.nextAdapterCredentialGeneration !== null
                    || activation.oldKeyId !== transcript.oldKeyId || activation.newKeyId !== transcript.newPublicJwk.kid
                    || activation.proposalDigest !== transcript.proposalDigest
                    || keyThumbprint(transcript.newPublicJwk) !== transcript.newKeyThumbprint)
                    return false;
                const old = snapshot.state.keys.get(transcript.oldKeyId);
                if (!old)
                    return false;
                const expectedOld = Object.freeze({ ...old, lifecycle: "grace_verify_only", signingNotAfter: activation.activatedAt, liveVerifyUntil: activation.retireAt });
                const expectedNew = Object.freeze({ keyId: activation.newKeyId, publicJwk: transcript.newPublicJwk, lifecycle: "active", activatedAt: activation.activatedAt, signingNotAfter: null, liveVerifyUntil: null });
                const keys = new Map(snapshot.state.keys);
                let changed = !keyRecordMatches(old, expectedOld);
                keys.set(transcript.oldKeyId, expectedOld);
                const currentNew = keys.get(activation.newKeyId);
                if (!keyRecordMatches(currentNew, expectedNew))
                    changed = true;
                keys.set(activation.newKeyId, expectedNew);
                const currentFact = snapshot.state.activationOutbox.get(activation.factId);
                if (!sameActivation(currentFact ?? null, activation))
                    changed = true;
                const activationOutbox = new Map(snapshot.state.activationOutbox);
                activationOutbox.set(activation.factId, activation);
                if (snapshot.state.activeKeyId !== activation.newKeyId || snapshot.state.pending !== null)
                    changed = true;
                if (changed)
                    this.referenceReplaceKeyRing(journal.id, Object.freeze({
                        storeRevision: snapshot.storeRevision + 1n,
                        state: Object.freeze({ ...snapshot.state, activeKeyId: activation.newKeyId, keys, pending: null, activationOutbox }),
                    }));
                this.#currentSigningKey = expectedNew;
                return true;
            };
            if (journal.status === "local_prepared") {
                if (!journal.localProposal || !(await outboundMatches(journal.localProposal)) || snapshot.state.activeKeyId !== transcript.oldKeyId
                    || !rotationPayloadMatches(journal.localProposal, transcript, "device_key_rotation", "app-to-bridge")) {
                    return { ok: false, error: "INTEGRITY_FAILED" };
                }
                const existingNew = snapshot.state.keys.get(transcript.newPublicJwk.kid);
                if (existingNew && !sameJwk(existingNew.publicJwk, transcript.newPublicJwk)) {
                    return { ok: false, error: "INTEGRITY_FAILED" };
                }
                const expectedPendingKey = Object.freeze({ keyId: transcript.newPublicJwk.kid, publicJwk: transcript.newPublicJwk, lifecycle: "pending", activatedAt: null, signingNotAfter: null, liveVerifyUntil: null });
                const pending = Object.freeze({ transcript, localProposal: journal.localProposal, cachedAck: null, proposalClaimId: null, ackClaimId: null });
                const keys = new Map(snapshot.state.keys);
                keys.set(transcript.newPublicJwk.kid, expectedPendingKey);
                const pendingMatches = snapshot.state.pending !== null
                    && snapshot.state.pending.transcript.rotationId === transcript.rotationId
                    && snapshot.state.pending.transcript.proposalDigest === transcript.proposalDigest
                    && snapshot.state.pending.localProposal !== null
                    && sameWire(snapshot.state.pending.localProposal, journal.localProposal);
                if (!pendingMatches)
                    this.referenceReplaceKeyRing(journal.id, Object.freeze({
                        storeRevision: snapshot.storeRevision + 1n,
                        state: Object.freeze({ ...snapshot.state, keys, pending, }),
                    }));
                return { ok: true, kind: "prepared", proposal: journal.localProposal };
            }
            if (journal.status === "remote_ack_committed") {
                if (!journal.cachedAck || !journal.activation || !journal.proposalClaimId) {
                    return { ok: false, error: "INTEGRITY_FAILED" };
                }
                if (!(await outboundMatches(journal.cachedAck))
                    || !rotationPayloadMatches(journal.cachedAck, transcript, "device_key_rotation_ack", "bridge-to-app")
                    || stringField(parseRetainedEnvelope(journal.cachedAck)?.payload ?? {}, "proposal_digest") !== transcript.proposalDigest
                    || stringField(parseRetainedEnvelope(journal.cachedAck)?.payload ?? {}, "new_key_id") !== journal.activation.newKeyId
                    || !finalizedReference(journal.proposalClaimId, transcript.proposalDigest, "bridge_key_rotation", "bridge-to-app")
                    || !restoreCommitted(journal.activation)) {
                    return { ok: false, error: "INVALID_STATE_TRANSITION" };
                }
                return { ok: true, kind: "already_applied", cached: journal.cachedAck };
            }
            if (journal.status === "local_activated") {
                if (!journal.activation || !journal.ackClaimId || !journal.localProposal) {
                    return { ok: false, error: "INTEGRITY_FAILED" };
                }
                if (!(await outboundMatches(journal.localProposal))
                    || !rotationPayloadMatches(journal.localProposal, transcript, "device_key_rotation", "app-to-bridge")
                    || !finalizedReference(journal.ackClaimId, null, "bridge_key_rotation_ack", "bridge-to-app")
                    || !restoreCommitted(journal.activation)) {
                    return { ok: false, error: "INVALID_STATE_TRANSITION" };
                }
                return { ok: true, kind: "activated", activation: journal.activation };
            }
            return journal.status === "retired"
                ? { ok: true, kind: "retired" }
                : { ok: true, kind: "revoked" };
        });
    }
}
/** Adapter-branch durable rotation backend. It deliberately extends the
 * authenticated adapter admission capability so credential generation, lease
 * fencing, replay claims, key-ring rows and rotation journal share one owner. */
export class DeterministicAdapterRotationStore extends DeterministicAdapterSecurityBackend {
    [adapterRotationBackendBrand] = true;
    #outbound;
    #journals = new Map();
    #serialTail = Promise.resolve();
    #currentSigningKey;
    constructor(options) {
        super(options.security);
        this.#currentSigningKey = options.outbound.keyRecord;
        this.#outbound = new DeterministicOutboundEnvelopeStore({
            ...options.outbound,
            keyRecordFor: () => this.#currentSigningKey,
        }, options.outboundSnapshot);
        for (const journal of options.journalSnapshots ?? [])
            this.#journals.set(journal.journalId, journal);
        this.setReplayReferenceCheck((claimId) => [...this.#journals.values()].some((journal) => journal.proposalClaimId === claimId || journal.ackClaimId === claimId));
        adapterRotationStores.add(this);
    }
    static restart(snapshot, outbound) {
        if (!outbound || !("outbound" in snapshot) || !("journals" in snapshot))
            return super.restart(snapshot);
        return new DeterministicAdapterRotationStore({
            security: snapshot,
            outbound,
            outboundSnapshot: snapshot.outbound,
            journalSnapshots: snapshot.journals,
        });
    }
    snapshot() {
        return Object.freeze({
            ...super.snapshot(),
            outbound: this.#outbound.snapshot(),
            journals: Object.freeze([...this.#journals.values()]),
        });
    }
    prepareOrdinaryAtomically(input, context) { return this.#outbound.prepareOrdinaryAtomically(input, context); }
    prepareRotationAtomically(input, context) { return this.#outbound.prepareRotationAtomically(input, context); }
    loadOrdinarySendable(space, messageId) {
        return this.#outbound.loadOrdinarySendable(space, messageId);
    }
    loadRotationHandle(space, messageId) {
        return this.#outbound.loadRotationHandle(space, messageId);
    }
    async #withSerial(operation) {
        const previous = this.#serialTail;
        let release;
        this.#serialTail = new Promise((resolve) => { release = resolve; });
        await previous;
        try {
            return await operation();
        }
        finally {
            release();
        }
    }
    async applyAtomic(input, clock) {
        return this.#withSerial(() => this.#applyAtomic(input, clock));
    }
    async #applyAtomic(input, clock) {
        const snapshot = await this.load(input.id);
        const rotationId = input.kind === "prepare_local" ? input.transcript.rotationId
            : input.kind === "accept_remote_proposal" ? stringField(input.proposal.envelope.payload, "rotation_id")
                : input.kind === "accept_ack" ? stringField(input.ack.envelope.payload, "rotation_id") : null;
        const existing = rotationId ? this.#journals.get(rotationId) : undefined;
        if (existing && input.kind === "accept_remote_proposal" && existing.status === "remote_ack_committed") {
            return { ok: true, kind: "already_applied", cached: existing.cachedAck };
        }
        if (existing && input.kind === "accept_ack" && existing.status === "local_activated" && existing.activation) {
            return { ok: true, kind: "activated", activation: existing.activation };
        }
        if (existing && input.kind === "prepare_local" && existing.status === "local_prepared" && existing.localProposal) {
            return { ok: true, kind: "prepared", proposal: existing.localProposal };
        }
        if (input.kind === "revoke") {
            const current = snapshot.state.keys.get(input.keyId);
            if (!current)
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            const keys = new Map(snapshot.state.keys);
            keys.set(input.keyId, Object.freeze({ ...current, lifecycle: "revoked" }));
            if (this.#currentSigningKey.keyId === input.keyId)
                this.#currentSigningKey = Object.freeze({ ...this.#currentSigningKey, lifecycle: "revoked" });
            this.referenceReplaceKeyRing(input.id, Object.freeze({ storeRevision: snapshot.storeRevision + 1n,
                state: Object.freeze({ ...snapshot.state, activeKeyId: snapshot.state.activeKeyId === input.keyId ? null : snapshot.state.activeKeyId, keys }),
            }));
            return { ok: true, kind: "revoked" };
        }
        if (input.kind === "retire_due") {
            const now = clock.wallNow().getTime();
            const keys = new Map(snapshot.state.keys);
            let changed = false;
            for (const [keyId, record] of keys)
                if (record.lifecycle === "grace_verify_only" && record.liveVerifyUntil !== null && Date.parse(record.liveVerifyUntil) <= now) {
                    keys.set(keyId, Object.freeze({ ...record, lifecycle: "archived", liveVerifyUntil: null }));
                    changed = true;
                }
            if (changed)
                this.referenceReplaceKeyRing(input.id, Object.freeze({ storeRevision: snapshot.storeRevision + 1n, state: Object.freeze({ ...snapshot.state, keys }) }));
            return { ok: true, kind: "retired" };
        }
        if (input.kind === "prepare_local") {
            if (!this.referenceOwnsContext(input.context) || !this.#outbound.ownsRotationHandle(input.proposal)
                || snapshot.state.pending !== null || snapshot.state.activeKeyId !== input.transcript.oldKeyId
                || snapshot.state.keys.has(input.transcript.newPublicJwk.kid) || input.proposal.envelopeDigest !== input.transcript.proposalDigest
                || input.proposal.space.kind !== "adapter" || input.proposal.space.credentialId !== input.id.credentialId
                || input.proposal.space.adapterCredentialGeneration !== input.context.adapterCredentialGeneration)
                return { ok: false, error: "AUTH_BINDING_MISMATCH" };
            const candidateWire = this.#outbound.rotationEnvelopeBytes(input.proposal);
            const parsed = candidateWire ? parseRetainedEnvelope(candidateWire) : null;
            if (!parsed || stringField(parsed.payload, "rotation_id") !== input.transcript.rotationId
                || stringField(parsed.payload, "old_key_id") !== input.transcript.oldKeyId
                || stringField(parsed.payload, "new_key_thumbprint") !== input.transcript.newKeyThumbprint
                || keyThumbprint(input.transcript.newPublicJwk) !== input.transcript.newKeyThumbprint
                || stringField(parsed.payload, "challenge") !== input.transcript.challenge
                || !sameJwk(parsed.payload.new_public_jwk, input.transcript.newPublicJwk)
                || stringField(parsed.payload, "next_adapter_credential_generation") !== input.transcript.nextAdapterCredentialGeneration?.toString())
                return { ok: false, error: "AUTH_BINDING_MISMATCH" };
            const keys = new Map(snapshot.state.keys);
            keys.set(input.transcript.newPublicJwk.kid, Object.freeze({ keyId: input.transcript.newPublicJwk.kid, publicJwk: input.transcript.newPublicJwk, lifecycle: "pending", activatedAt: null, signingNotAfter: null, liveVerifyUntil: null }));
            const journal = Object.freeze({ journalId: input.transcript.rotationId, id: input.id, transcript: input.transcript, status: "local_prepared", localProposal: null, cachedAck: null, proposalClaimId: null, ackClaimId: null, activation: null });
            this.#journals.set(journal.journalId, journal);
            const proposal = this.#outbound.commitRotationHandle(input.proposal);
            if (!proposal) {
                this.#journals.delete(journal.journalId);
                return { ok: false, error: "INTEGRITY_FAILED" };
            }
            this.#journals.set(journal.journalId, Object.freeze({ ...journal, localProposal: proposal }));
            this.referenceReplaceKeyRing(input.id, Object.freeze({ storeRevision: snapshot.storeRevision + 1n, state: Object.freeze({ ...snapshot.state, keys, pending: Object.freeze({ transcript: input.transcript, localProposal: proposal, cachedAck: null, proposalClaimId: null, ackClaimId: null }) }) }));
            return { ok: true, kind: "prepared", proposal };
        }
        if (input.kind === "accept_remote_proposal") {
            const payload = input.proposal.envelope.payload;
            const id = stringField(payload, "rotation_id");
            const oldKeyId = stringField(payload, "old_key_id");
            const challenge = stringField(payload, "challenge");
            const thumb = stringField(payload, "new_key_thumbprint");
            const nextGeneration = stringField(payload, "next_adapter_credential_generation");
            if (!id || !oldKeyId || !challenge || !thumb || !nextGeneration || !payload.new_public_jwk || !this.referenceOwnsContext(input.proposal.context)
                || input.proposal.context.credentialId !== input.id.credentialId || input.proposal.claim.space.kind !== "adapter" || input.proposal.claim.space.adapterCredentialGeneration !== snapshot.state.bindingGeneration
                || input.proposal.envelope.messageType.endsWith("_ack"))
                return { ok: false, error: "AUTH_BINDING_MISMATCH" };
            const ack = this.#outbound.commitRotationHandle(input.ack);
            if (!ack)
                return { ok: false, error: "INTEGRITY_FAILED" };
            const parsed = parseRetainedEnvelope(ack);
            if (!parsed)
                return { ok: false, error: "INTEGRITY_FAILED" };
            if (stringField(parsed.payload, "rotation_id") !== id || stringField(parsed.payload, "old_key_id") !== oldKeyId || stringField(parsed.payload, "new_key_thumbprint") !== thumb || stringField(parsed.payload, "challenge") !== challenge || stringField(parsed.payload, "proposal_digest") !== input.proposal.envelope.envelopeDigest || stringField(parsed.payload, "next_adapter_credential_generation") !== nextGeneration || ack.space.kind !== "adapter" || ack.space.adapterCredentialGeneration !== snapshot.state.bindingGeneration)
                return { ok: false, error: "AUTH_BINDING_MISMATCH" };
            const newKeyId = stringField(parsed.payload, "new_key_id");
            const next = BigInt(nextGeneration);
            if (!newKeyId || next !== snapshot.state.bindingGeneration + 1n || snapshot.state.activeKeyId !== oldKeyId || snapshot.state.keys.has(newKeyId))
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            const transcript = Object.freeze({ rotationId: id, oldKeyId, newPublicJwk: payload.new_public_jwk, newKeyThumbprint: thumb, challenge, proposalDigest: input.proposal.envelope.envelopeDigest, pairingGeneration: null, oldAdapterCredentialGeneration: snapshot.state.bindingGeneration, nextAdapterCredentialGeneration: next });
            const activation = activationFact(input.id, transcript, parsed.payload, String(parsed.header.issued_at));
            if (!activation)
                return { ok: false, error: "INTEGRITY_FAILED" };
            const old = snapshot.state.keys.get(oldKeyId);
            if (!old)
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            const keys = new Map(snapshot.state.keys);
            keys.set(oldKeyId, Object.freeze({ ...old, lifecycle: "grace_verify_only", signingNotAfter: activation.activatedAt, liveVerifyUntil: activation.retireAt }));
            keys.set(newKeyId, Object.freeze({ keyId: newKeyId, publicJwk: payload.new_public_jwk, lifecycle: "active", activatedAt: activation.activatedAt, signingNotAfter: null, liveVerifyUntil: null }));
            const journal = Object.freeze({ journalId: id, id: input.id, transcript, status: "remote_ack_committed", localProposal: null, cachedAck: ack, proposalClaimId: input.proposal.claim.claimId, ackClaimId: null, activation });
            const finalized = await this.finalize(input.proposal.claim, receiptFor("adapter", id));
            if (finalized.kind === "rejected")
                return { ok: false, error: "INTEGRITY_FAILED" };
            this.referenceReplaceKeyRing(input.id, Object.freeze({ storeRevision: snapshot.storeRevision + 1n, state: Object.freeze({ ...snapshot.state, activeKeyId: newKeyId, bindingGeneration: next, keys, pending: null, activationOutbox: new Map([...snapshot.state.activationOutbox, [activation.factId, activation]]) }) }));
            this.runCredentialRotationTransaction(() => this.commitCredentialGeneration(next));
            this.#journals.set(id, journal);
            this.#currentSigningKey = keys.get(newKeyId);
            return { ok: true, kind: "acknowledged", ack, activation };
        }
        if (input.kind === "accept_ack") {
            if (!this.referenceOwnsContext(input.ack.context) || input.ack.context.credentialId !== input.id.credentialId || input.ack.envelope.messageType.endsWith("_rotation") || !snapshot.state.pending)
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            const payload = input.ack.envelope.payload;
            const pending = snapshot.state.pending.transcript;
            const id = stringField(payload, "rotation_id");
            const newKeyId = stringField(payload, "new_key_id");
            const nextGeneration = stringField(payload, "next_adapter_credential_generation");
            if (!id || !newKeyId || !nextGeneration || id !== pending.rotationId || stringField(payload, "old_key_id") !== pending.oldKeyId || stringField(payload, "proposal_digest") !== pending.proposalDigest || stringField(payload, "challenge") !== pending.challenge || newKeyId !== pending.newPublicJwk.kid || BigInt(nextGeneration) !== snapshot.state.bindingGeneration + 1n)
                return { ok: false, error: "AUTH_BINDING_MISMATCH" };
            const activation = activationFact(input.id, pending, payload, String(input.ack.envelope.header.issued_at));
            if (!activation)
                return { ok: false, error: "INTEGRITY_FAILED" };
            const old = snapshot.state.keys.get(pending.oldKeyId);
            const next = snapshot.state.keys.get(newKeyId);
            if (!old || !next || next.lifecycle !== "pending")
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            const keys = new Map(snapshot.state.keys);
            keys.set(old.keyId, Object.freeze({ ...old, lifecycle: "grace_verify_only", signingNotAfter: activation.activatedAt, liveVerifyUntil: activation.retireAt }));
            keys.set(newKeyId, Object.freeze({ ...next, lifecycle: "active", activatedAt: activation.activatedAt, signingNotAfter: null, liveVerifyUntil: null }));
            const journal = Object.freeze({ journalId: id, id: input.id, transcript: pending, status: "local_activated", localProposal: snapshot.state.pending.localProposal, cachedAck: null, proposalClaimId: snapshot.state.pending.proposalClaimId, ackClaimId: input.ack.claim.claimId, activation });
            const finalized = await this.finalize(input.ack.claim, receiptFor("adapter", id));
            if (finalized.kind === "rejected")
                return { ok: false, error: "INTEGRITY_FAILED" };
            this.referenceReplaceKeyRing(input.id, Object.freeze({ storeRevision: snapshot.storeRevision + 1n, state: Object.freeze({ ...snapshot.state, activeKeyId: newKeyId, bindingGeneration: snapshot.state.bindingGeneration + 1n, keys, pending: null, activationOutbox: new Map([...snapshot.state.activationOutbox, [activation.factId, activation]]) }) }));
            this.runCredentialRotationTransaction(() => this.commitCredentialGeneration(snapshot.state.bindingGeneration + 1n));
            this.#journals.set(id, journal);
            this.#currentSigningKey = keys.get(newKeyId);
            return { ok: true, kind: "activated", activation };
        }
        return { ok: false, error: "INVALID_STATE_TRANSITION" };
    }
    async loadJournal(journalId) { return this.#journals.get(journalId) ?? null; }
    async resumeJournal(reconciler, journalId, _clock) {
        return this.#withSerial(async () => {
            if (!this.ownsTrustedReconciler(reconciler))
                return { ok: false, error: "AUTH_FAILED" };
            const journal = this.#journals.get(journalId);
            if (!journal)
                return { ok: false, error: "INVALID_STATE_TRANSITION" };
            const snapshot = await this.load(journal.id);
            const transcript = journal.transcript;
            if (journal.journalId !== transcript.rotationId || journal.id.owner !== "adapter"
                || transcript.pairingGeneration !== null || transcript.oldAdapterCredentialGeneration === null
                || transcript.nextAdapterCredentialGeneration === null
                || transcript.proposalDigest.length === 0 || transcript.oldKeyId.length === 0
                || !snapshot.state.keys.has(transcript.oldKeyId)) {
                return { ok: false, error: "INTEGRITY_FAILED" };
            }
            const outboundMatches = async (envelope) => {
                const handle = await this.#outbound.loadRotationHandle(envelope.space, envelope.messageId);
                const retained = handle ? this.#outbound.rotationEnvelopeBytes(handle) : null;
                return retained !== null && sameBytes(retained, envelope.rawWire.copy())
                    && sha256B64Url(retained) === envelope.envelopeDigest;
            };
            const finalizedReference = (claimId, digest, expectedType, expectedDirection) => {
                if (!claimId)
                    return null;
                const reference = this.replayClaimForReconciler(reconciler, claimId);
                if (!reference || reference.status !== "finalized" || reference.receipt === null)
                    return null;
                const expectedReceipt = receiptFor("adapter", journalId);
                const parsedReference = parseRetainedEnvelope(reference.frame.envelope.rawWire.copy());
                if (!sameBytes(reference.receipt.copy(), expectedReceipt.copy())
                    || reference.frame.context.kind !== "adapter"
                    || reference.frame.context.credentialId !== journal.id.credentialId
                    || reference.frame.claim.claimId !== claimId
                    || (digest !== null && (reference.frame.claim.envelopeDigest !== digest
                        || reference.frame.envelope.envelopeDigest !== digest))
                    || reference.frame.envelope.messageType !== expectedType
                    || reference.frame.envelope.registryEntry.direction !== expectedDirection
                    || !parsedReference
                    || !rotationPayloadMatches(parsedReference, transcript, expectedType, expectedDirection))
                    return null;
                return reference;
            };
            const restoreCommitted = (activation) => {
                const nextGeneration = transcript.nextAdapterCredentialGeneration;
                const oldGeneration = transcript.oldAdapterCredentialGeneration;
                if (nextGeneration === null || oldGeneration === null)
                    return false;
                if (activation.rotationId !== transcript.rotationId || activation.owner !== "adapter"
                    || activation.credentialId !== journal.id.credentialId || activation.pairingGeneration !== null
                    || activation.oldAdapterCredentialGeneration !== oldGeneration
                    || activation.nextAdapterCredentialGeneration !== nextGeneration
                    || activation.oldKeyId !== transcript.oldKeyId || activation.newKeyId !== transcript.newPublicJwk.kid
                    || activation.proposalDigest !== transcript.proposalDigest
                    || keyThumbprint(transcript.newPublicJwk) !== transcript.newKeyThumbprint)
                    return false;
                const currentGeneration = this.currentCredentialGeneration();
                if (currentGeneration > nextGeneration || currentGeneration < oldGeneration
                    || snapshot.state.bindingGeneration > nextGeneration || snapshot.state.bindingGeneration < oldGeneration)
                    return false;
                const old = snapshot.state.keys.get(transcript.oldKeyId);
                if (!old)
                    return false;
                const expectedOld = Object.freeze({ ...old, lifecycle: "grace_verify_only", signingNotAfter: activation.activatedAt, liveVerifyUntil: activation.retireAt });
                const expectedNew = Object.freeze({ keyId: activation.newKeyId, publicJwk: transcript.newPublicJwk, lifecycle: "active", activatedAt: activation.activatedAt, signingNotAfter: null, liveVerifyUntil: null });
                const keys = new Map(snapshot.state.keys);
                let changed = !keyRecordMatches(old, expectedOld);
                keys.set(transcript.oldKeyId, expectedOld);
                const currentNew = keys.get(activation.newKeyId);
                if (!keyRecordMatches(currentNew, expectedNew))
                    changed = true;
                keys.set(activation.newKeyId, expectedNew);
                const currentFact = snapshot.state.activationOutbox.get(activation.factId);
                if (!sameActivation(currentFact ?? null, activation))
                    changed = true;
                const activationOutbox = new Map(snapshot.state.activationOutbox);
                activationOutbox.set(activation.factId, activation);
                if (snapshot.state.activeKeyId !== activation.newKeyId || snapshot.state.bindingGeneration !== nextGeneration
                    || snapshot.state.pending !== null)
                    changed = true;
                if (changed)
                    this.referenceReplaceKeyRing(journal.id, Object.freeze({
                        storeRevision: snapshot.storeRevision + 1n,
                        state: Object.freeze({ ...snapshot.state, activeKeyId: activation.newKeyId, bindingGeneration: nextGeneration, keys, pending: null, activationOutbox }),
                    }));
                if (currentGeneration === oldGeneration)
                    this.runCredentialRotationTransaction(() => this.commitCredentialGeneration(nextGeneration));
                if (this.currentCredentialGeneration() !== nextGeneration)
                    return false;
                this.#currentSigningKey = expectedNew;
                return true;
            };
            if (journal.status === "local_prepared") {
                if (!journal.localProposal || !(await outboundMatches(journal.localProposal)) || snapshot.state.activeKeyId !== transcript.oldKeyId
                    || snapshot.state.bindingGeneration !== transcript.oldAdapterCredentialGeneration
                    || !rotationPayloadMatches(journal.localProposal, transcript, "adapter_key_rotation", "adapter-to-bridge")) {
                    return { ok: false, error: "INTEGRITY_FAILED" };
                }
                const existingNew = snapshot.state.keys.get(transcript.newPublicJwk.kid);
                if (existingNew && !sameJwk(existingNew.publicJwk, transcript.newPublicJwk)) {
                    return { ok: false, error: "INTEGRITY_FAILED" };
                }
                const expectedPendingKey = Object.freeze({ keyId: transcript.newPublicJwk.kid, publicJwk: transcript.newPublicJwk, lifecycle: "pending", activatedAt: null, signingNotAfter: null, liveVerifyUntil: null });
                const pending = Object.freeze({ transcript, localProposal: journal.localProposal, cachedAck: null, proposalClaimId: null, ackClaimId: null });
                const keys = new Map(snapshot.state.keys);
                keys.set(transcript.newPublicJwk.kid, expectedPendingKey);
                const pendingMatches = snapshot.state.pending !== null
                    && snapshot.state.pending.transcript.rotationId === transcript.rotationId
                    && snapshot.state.pending.localProposal !== null
                    && sameWire(snapshot.state.pending.localProposal, journal.localProposal);
                if (!pendingMatches)
                    this.referenceReplaceKeyRing(journal.id, Object.freeze({
                        storeRevision: snapshot.storeRevision + 1n,
                        state: Object.freeze({ ...snapshot.state, keys, pending, }),
                    }));
                return { ok: true, kind: "prepared", proposal: journal.localProposal };
            }
            if (journal.status === "remote_ack_committed") {
                if (!journal.cachedAck || !journal.activation || !journal.proposalClaimId) {
                    return { ok: false, error: "INTEGRITY_FAILED" };
                }
                const cached = parseRetainedEnvelope(journal.cachedAck);
                if (!cached || !(await outboundMatches(journal.cachedAck))
                    || !rotationPayloadMatches(journal.cachedAck, transcript, "adapter_key_rotation_ack", "bridge-to-adapter")
                    || stringField(cached.payload, "proposal_digest") !== transcript.proposalDigest
                    || stringField(cached.payload, "new_key_id") !== journal.activation.newKeyId
                    || !finalizedReference(journal.proposalClaimId, transcript.proposalDigest, "adapter_key_rotation", "adapter-to-bridge")
                    || !restoreCommitted(journal.activation)) {
                    return { ok: false, error: "INVALID_STATE_TRANSITION" };
                }
                return { ok: true, kind: "already_applied", cached: journal.cachedAck };
            }
            if (journal.status === "local_activated") {
                if (!journal.activation || !journal.ackClaimId || !journal.localProposal) {
                    return { ok: false, error: "INTEGRITY_FAILED" };
                }
                if (!(await outboundMatches(journal.localProposal))
                    || !rotationPayloadMatches(journal.localProposal, transcript, "adapter_key_rotation", "adapter-to-bridge")
                    || !finalizedReference(journal.ackClaimId, null, "adapter_key_rotation_ack", "bridge-to-adapter")
                    || !restoreCommitted(journal.activation)) {
                    return { ok: false, error: "INVALID_STATE_TRANSITION" };
                }
                return { ok: true, kind: "activated", activation: journal.activation };
            }
            return journal.status === "retired"
                ? { ok: true, kind: "retired" }
                : { ok: true, kind: "revoked" };
        });
    }
}
const isDeviceRotationInput = (input) => input.id.owner !== "adapter";
const isAdapterRotationInput = (input) => input.id.owner === "adapter";
const isDeviceRotationStore = (store) => deviceRotationStores.has(store);
const isAdapterRotationStore = (store) => adapterRotationStores.has(store);
export function applyKeyRotation(input, store, clock) {
    if (isDeviceRotationStore(store)) {
        if (!isDeviceRotationInput(input))
            return Promise.resolve({ ok: false, error: "AUTH_BINDING_MISMATCH" });
        return store.applyAtomic(input, clock);
    }
    if (isAdapterRotationStore(store)) {
        if (!isAdapterRotationInput(input))
            return Promise.resolve({ ok: false, error: "AUTH_BINDING_MISMATCH" });
        return store.applyAtomic(input, clock);
    }
    return Promise.resolve({ ok: false, error: "AUTH_FAILED" });
}
