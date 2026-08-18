import { Ajv2020 } from "ajv/dist/2020.js";
import addFormatsImport from "ajv-formats";
import { PROTOCOL_SCHEMA_DOCUMENTS, REQUIRED_PROTOCOL_SCHEMA_IDS } from "./schema-catalog.js";
const UINT64_MAX = 18446744073709551615n;
const addFormats = addFormatsImport;
const ajv = new Ajv2020({
    allErrors: true,
    strict: true,
    validateFormats: true,
});
addFormats(ajv);
ajv.addFormat("decimal-u64", {
    type: "string",
    validate(value) {
        return /^(0|[1-9][0-9]*)$/.test(value) && BigInt(value) <= UINT64_MAX;
    },
});
ajv.addFormat("lowercase-uuid-v4", {
    type: "string",
    validate: (value) => /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value),
});
ajv.addFormat("rfc3339-utc-milliseconds", {
    type: "string",
    validate(value) {
        if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value))
            return false;
        const milliseconds = Date.parse(value);
        return Number.isFinite(milliseconds) && new Date(milliseconds).toISOString() === value;
    },
});
const canonicalBase64UrlLength = (value, byteLength) => {
    if (!/^[A-Za-z0-9_-]+$/.test(value))
        return false;
    const decoded = Buffer.from(value, "base64url");
    return decoded.byteLength === byteLength && decoded.toString("base64url") === value;
};
ajv.addFormat("base64url-32", { type: "string", validate: (value) => canonicalBase64UrlLength(value, 32) });
ajv.addFormat("base64url-64", { type: "string", validate: (value) => canonicalBase64UrlLength(value, 64) });
for (const schema of PROTOCOL_SCHEMA_DOCUMENTS)
    ajv.addSchema(schema);
for (const schemaId of REQUIRED_PROTOCOL_SCHEMA_IDS) {
    if (!ajv.getSchema(schemaId))
        throw new Error(`UNRESOLVED_REQUIRED_SCHEMA_ID: ${schemaId}`);
}
const schemaFor = (schemaId) => {
    const validator = ajv.getSchema(schemaId);
    if (!validator)
        throw new Error("UNKNOWN_SCHEMA_ID");
    return validator;
};
export function validateSchema(schemaId, value) {
    const validator = schemaFor(schemaId);
    if (!validator(value)) {
        throw new Error(`SCHEMA_INVALID: ${ajv.errorsText(validator.errors, { separator: "; " })}`);
    }
    // JSON Schema can validate the canonical decimal-u64 representation but
    // cannot express the cross-field ordering rule for a loss range. Keep that
    // semantic rule at the same closed-schema boundary so a malformed range
    // cannot enter the event reducer through this helper.
    if (schemaId === "urn:agent-life:protocol:v1:message:device_event"
        && typeof value === "object" && value !== null && !Array.isArray(value)) {
        const event = value;
        if (event.event_kind === "loss_marker" && typeof event.loss === "object" && event.loss !== null && !Array.isArray(event.loss)) {
            const loss = event.loss;
            if (typeof loss.lost_from_cursor === "string" && typeof loss.lost_to_cursor === "string"
                && BigInt(loss.lost_from_cursor) > BigInt(loss.lost_to_cursor)) {
                throw new Error("SCHEMA_INVALID: loss range is descending");
            }
        }
    }
}
