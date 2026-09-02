import profileFixture from "../profile/v1.json" with { type: "json" };
import { validateSchema } from "./schema-validator.js";
export const PROFILE_SCHEMA_ID = "urn:open-android-intelligence:protocol:v1:profile";
validateSchema(PROFILE_SCHEMA_ID, profileFixture);
Object.freeze(profileFixture.signature_domains);
const profile = Object.freeze(profileFixture);
export function loadProtocolProfile() {
    return profile;
}
const signatureDomainSet = new Set(profile.signature_domains);
export function parseSignatureDomain(value) {
    if (!signatureDomainSet.has(value))
        throw new Error("UNKNOWN_SIGNATURE_DOMAIN");
    return value;
}
export const SIGNATURE_DOMAINS = Object.freeze(profile.signature_domains.map(parseSignatureDomain));
export const CRYPTO_PROFILE = {
    profileId: profile.profile_id,
    canonicalization: profile.canonicalization,
    digest: profile.digest,
    signature: profile.signature,
    curve: profile.curve,
    signatureEncoding: profile.signature_encoding,
    maxEnvelopeBytes: Number(profile.max_envelope_bytes),
};
