/// <reference types="node" />
import { createPublicKey, verify as verifySignature, } from "node:crypto";
import { p256 } from "@noble/curves/nist.js";
const P256_N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551n;
const P256_HALF_N = P256_N >> 1n;
const PUBLIC_JWK_FIELDS = ["alg", "crv", "kid", "kty", "use", "x", "y"];
const PRIVATE_JWK_FIELDS = [...PUBLIC_JWK_FIELDS, "d"].sort();
const isRecord = (value) => typeof value === "object" && value !== null && !Array.isArray(value);
const hasExactFields = (jwk, expected) => {
    const actual = Object.keys(jwk).sort();
    const sortedExpected = [...expected].sort();
    return actual.length === sortedExpected.length && actual.every((field, index) => field === sortedExpected[index]);
};
const decodeBase64Url = (value, byteLength) => {
    if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value))
        return undefined;
    const decoded = Uint8Array.from(Buffer.from(value, "base64url"));
    if (decoded.byteLength !== byteLength || Buffer.from(decoded).toString("base64url") !== value)
        return undefined;
    return decoded;
};
const validCommonJwk = (jwk) => jwk.kty === "EC"
    && jwk.crv === "P-256"
    && jwk.alg === "ES256"
    && jwk.use === "sig"
    && typeof jwk.kid === "string"
    && /^[A-Za-z0-9._~-]{1,128}$/.test(jwk.kid);
const parsePublicJwk = (value) => {
    if (!isRecord(value) || !hasExactFields(value, PUBLIC_JWK_FIELDS) || !validCommonJwk(value))
        return undefined;
    const x = decodeBase64Url(value.x, 32);
    const y = decodeBase64Url(value.y, 32);
    if (!x || !y)
        return undefined;
    const point = Uint8Array.from([0x04, ...x, ...y]);
    try {
        p256.Point.fromBytes(point).assertValidity();
        createPublicKey({ key: value, format: "jwk" });
    }
    catch {
        return undefined;
    }
    return { jwk: value, point };
};
export function isValidP256PublicJwk(value) {
    return parsePublicJwk(value) !== undefined;
}
const parsePrivateJwk = (value) => {
    if (!isRecord(value) || !hasExactFields(value, PRIVATE_JWK_FIELDS) || !validCommonJwk(value))
        return undefined;
    const x = decodeBase64Url(value.x, 32);
    const y = decodeBase64Url(value.y, 32);
    const secret = decodeBase64Url(value.d, 32);
    if (!x || !y || !secret)
        return undefined;
    try {
        const derived = p256.getPublicKey(secret, false);
        const supplied = Uint8Array.from([0x04, ...x, ...y]);
        if (!Buffer.from(derived).equals(Buffer.from(supplied)))
            return undefined;
    }
    catch {
        return undefined;
    }
    return { jwk: value, secret };
};
const bytesToBigInt = (bytes) => BigInt(`0x${Buffer.from(bytes).toString("hex")}`);
const parseSignature = (signature) => {
    const bytes = decodeBase64Url(signature, 64);
    if (!bytes)
        return undefined;
    const r = bytesToBigInt(bytes.slice(0, 32));
    const s = bytesToBigInt(bytes.slice(32));
    if (r < 1n || r >= P256_N || s < 1n || s >= P256_N || s > P256_HALF_N)
        return undefined;
    return bytes;
};
export function isLowS(signature) {
    return parseSignature(signature) !== undefined;
}
export function signTestOnly(privateJwk, preimage) {
    const parsed = parsePrivateJwk(privateJwk);
    if (!parsed)
        throw new Error("INVALID_PRIVATE_JWK");
    const signature = p256.sign(preimage, parsed.secret, {
        extraEntropy: false,
        format: "compact",
        lowS: true,
        prehash: true,
    });
    const encoded = Buffer.from(signature).toString("base64url");
    if (!isLowS(encoded))
        throw new Error("INVALID_SIGNATURE_GENERATED");
    return encoded;
}
export function verifyEs256(publicJwk, preimage, signature) {
    const parsedJwk = parsePublicJwk(publicJwk);
    const parsedSignature = parseSignature(signature);
    if (!parsedJwk || !parsedSignature)
        return false;
    try {
        const key = createPublicKey({ key: parsedJwk.jwk, format: "jwk" });
        return verifySignature("sha256", preimage, { key, dsaEncoding: "ieee-p1363" }, parsedSignature);
    }
    catch {
        return false;
    }
}
