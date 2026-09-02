/// <reference types="node" />
import { timingSafeEqual } from "node:crypto";
import messagesFixture from "../registries/v1/messages.json" with { type: "json" };
import { isValidP256PublicJwk, verifyEs256 } from "./crypto.js";
import { canonicalBytes, parseCanonicalJson, sha256B64Url, signingPreimage } from "./encoding.js";
import { parseSignatureDomain } from "./profile.js";
import { validateSchema } from "./schema-validator.js";
import { parseProtocolVersion } from "./version-negotiation.js";
const deepFreeze = (value) => {
    if (typeof value === "object" && value !== null && !Object.isFrozen(value)) {
        for (const member of Object.values(value))
            deepFreeze(member);
        Object.freeze(value);
    }
    return value;
};
validateSchema("urn:open-android-intelligence:protocol:v1:messages-registry", messagesFixture);
const lockedRegistry = deepFreeze(messagesFixture);
export function loadMessageRegistry() {
    return lockedRegistry;
}
const isRecord = (value) => typeof value === "object" && value !== null && !Array.isArray(value);
const parseWire = (wire) => {
    let parsed;
    try {
        parsed = parseCanonicalJson(wire);
    }
    catch (error) {
        if (error instanceof Error && error.message === "MESSAGE_TOO_LARGE")
            throw error;
        throw new Error("SCHEMA_INVALID");
    }
    if (!isRecord(parsed) || !isRecord(parsed.header) || !isRecord(parsed.payload) || typeof parsed.signature !== "string") {
        throw new Error("SCHEMA_INVALID");
    }
    return parsed;
};
const validateAdmissionSchema = (schemaId, value) => {
    try {
        validateSchema(schemaId, value);
    }
    catch {
        throw new Error("SCHEMA_INVALID");
    }
};
const isSorted = (values) => values.every((value, index) => index === 0 || values[index - 1] < value);
const registryEntry = (messageType) => {
    const entry = lockedRegistry.messages.find((candidate) => candidate.message_type === messageType);
    if (!entry)
        throw new Error("SCHEMA_INVALID");
    return entry;
};
const assertRegistryTuple = (wire, entry) => {
    if (wire.header.message_type !== entry.message_type
        || wire.header.message_schema !== entry.schema_id
        || wire.header.direction !== entry.direction)
        throw new Error("SCHEMA_INVALID");
};
const equalText = (left, right) => {
    const a = Buffer.from(left);
    const b = Buffer.from(right);
    return a.byteLength === b.byteLength && timingSafeEqual(a, b);
};
const jwkThumbprint = (jwk) => sha256B64Url(canonicalBytes({
    crv: jwk.crv,
    kty: jwk.kty,
    x: jwk.x,
    y: jwk.y,
}));
const unsignedValue = (wire) => ({ header: wire.header, payload: wire.payload });
const assertDigest = (wire) => {
    const actual = sha256B64Url(canonicalBytes(wire.payload));
    if (!equalText(actual, wire.header.payload_digest))
        throw new Error("INTEGRITY_FAILED");
};
const assertNotExpired = (wire, clock) => {
    const now = clock.wallNow().getTime();
    if (now >= Date.parse(wire.header.expires_at))
        throw new Error("MESSAGE_EXPIRED");
};
const verifyWithPort = async (verifier, role, keyId, wire, domain) => {
    if (wire.header.key_id !== keyId)
        throw new Error("AUTH_FAILED");
    const valid = await verifier.verify(role, keyId, signingPreimage(parseSignatureDomain(domain), unsignedValue(wire)), wire.signature);
    if (!valid)
        throw new Error("AUTH_FAILED");
};
export async function verifyEnrollmentBridgeMessage(rawWire, context) {
    const wire = parseWire(rawWire);
    const allowedTypes = context.phase === "challenge" ? ["enrollment_challenge"] : ["enrollment_complete", "enrollment_error"];
    if (!allowedTypes.includes(wire.header.message_type))
        throw new Error("SCHEMA_INVALID");
    validateAdmissionSchema("urn:open-android-intelligence:protocol:v1:envelope:enrollment_bridge_to_app", wire);
    if (wire.header.message_type === "enrollment_complete") {
        const scopes = wire.payload.enrollment_scope_ceiling;
        if (!Array.isArray(scopes) || !scopes.every((scope) => typeof scope === "string") || !isSorted(scopes)) {
            throw new Error("SCHEMA_INVALID");
        }
    }
    const entry = registryEntry(wire.header.message_type);
    assertRegistryTuple(wire, entry);
    if (context.phase === "challenge") {
        const payload = wire.payload;
        const thumbprint = jwkThumbprint(payload.bridge_command_public_jwk);
        if (!equalText(thumbprint, context.qrPinnedBridgeFingerprint)) {
            throw new Error("AUTH_BINDING_MISMATCH");
        }
        if (wire.header.key_id !== payload.bridge_command_public_jwk.kid
            || !verifyEs256(payload.bridge_command_public_jwk, signingPreimage(parseSignatureDomain(entry.signature_domain), unsignedValue(wire)), wire.signature)) {
            throw new Error("AUTH_FAILED");
        }
    }
    else {
        await verifyWithPort(context.verifier, "bridge-command", context.expectedKeyId, wire, entry.signature_domain);
    }
    assertDigest(wire);
    assertNotExpired(wire, context.clock);
    if (!wire.header.enrollment_ticket_digest
        || !equalText(wire.header.enrollment_ticket_digest, context.expectedTicketDigest)) {
        throw new Error("AUTH_BINDING_MISMATCH");
    }
    if (context.phase === "pinned"
        && !equalText(context.expectedTicketDigest, context.pendingTranscript.ticket_digest)) {
        throw new Error("AUTH_BINDING_MISMATCH");
    }
    if (context.phase === "challenge") {
        const payload = wire.payload;
        if (!equalText(payload.challenge, context.expectedChallenge)
            || !equalText(payload.bridge_fingerprint, context.qrPinnedBridgeFingerprint)) {
            throw new Error("AUTH_BINDING_MISMATCH");
        }
        for (const version of payload.supported_versions)
            parseProtocolVersion(version);
        return deepFreeze({ type: "enrollment_challenge", header: wire.header, payload });
    }
    if (wire.header.message_type === "enrollment_complete") {
        const payload = wire.payload;
        const transcript = context.pendingTranscript;
        const bindings = [
            [payload.client_nonce, transcript.client_nonce],
            [payload.bridge_nonce, transcript.bridge_nonce],
            [payload.bridge_fingerprint, transcript.bridge_fingerprint],
            [payload.device_jwk_thumbprint, transcript.device_jwk_thumbprint],
            [payload.selected_protocol, transcript.selected_protocol],
        ];
        if (bindings.some(([actual, expected]) => typeof actual !== "string" || !equalText(actual, expected))) {
            throw new Error("AUTH_BINDING_MISMATCH");
        }
        parseProtocolVersion(payload.selected_protocol);
        return deepFreeze({ type: "enrollment_complete", header: wire.header, payload });
    }
    return deepFreeze({ type: "enrollment_error", header: wire.header, payload: wire.payload });
}
export async function verifyConnectMessage(rawWire, expectedType, context) {
    const wire = parseWire(rawWire);
    if (wire.header.message_type !== expectedType)
        throw new Error("SCHEMA_INVALID");
    validateAdmissionSchema(`urn:open-android-intelligence:protocol:v1:envelope:${expectedType}`, wire);
    const entry = registryEntry(expectedType);
    assertRegistryTuple(wire, entry);
    const requiredRole = expectedType === "connect_hello" ? "device" : "bridge-command";
    if (context.expectedSignerRole !== requiredRole)
        throw new Error("AUTH_FAILED");
    if (expectedType === "connect_welcome") {
        const payload = wire.payload;
        if (payload.command_key_set.current.kid !== wire.header.key_id
            || (payload.command_key_set.next !== null && payload.command_key_set.next.kid === payload.command_key_set.current.kid)) {
            throw new Error("SCHEMA_INVALID");
        }
    }
    await verifyWithPort(context.verifier, requiredRole, context.expectedKeyId, wire, entry.signature_domain);
    assertDigest(wire);
    assertNotExpired(wire, context.clock);
    if (expectedType === "connect_welcome") {
        const keys = wire.payload.command_key_set;
        if (!isValidP256PublicJwk(keys.current)
            || (keys.next !== null && !isValidP256PublicJwk(keys.next))) {
            throw new Error("SCHEMA_INVALID");
        }
    }
    if (wire.header.device_id !== context.expectedDeviceId
        || wire.header.pairing_generation !== context.expectedPairingGeneration) {
        throw new Error("AUTH_BINDING_MISMATCH");
    }
    if (expectedType === "connect_hello") {
        const payload = wire.payload;
        for (const version of payload.supported_versions)
            parseProtocolVersion(version);
        return deepFreeze({ type: "connect_hello", header: wire.header, payload });
    }
    const payload = wire.payload;
    parseProtocolVersion(payload.selected_protocol);
    return deepFreeze({ type: "connect_welcome", header: wire.header, payload });
}
