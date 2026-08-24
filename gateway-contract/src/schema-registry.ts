import {
  Ajv2020,
  type ErrorObject,
  type ValidateFunction,
} from "ajv/dist/2020.js";
import addFormatsImport from "ajv-formats";

import attachmentDocument from "../schemas/attachment.schema.json" with { type: "json" };
import conversationDocument from "../schemas/conversation.schema.json" with { type: "json" };
import deviceRequestDocument from "../schemas/device-request.schema.json" with { type: "json" };
import envelopeDocument from "../schemas/envelope.schema.json" with { type: "json" };
import eventDocument from "../schemas/event.schema.json" with { type: "json" };
import negotiateDocument from "../schemas/negotiate.schema.json" with { type: "json" };
import sessionDocument from "../schemas/session.schema.json" with { type: "json" };

export type GatewaySchemaName =
  | "negotiate.request"
  | "negotiate.response"
  | "session.password"
  | "session.refresh"
  | "session.device"
  | "conversation.create"
  | "message.create"
  | "attachment.create"
  | "event"
  | "device.request";

export type ValidationResult =
  | { readonly ok: true }
  | { readonly ok: false; readonly errors: readonly string[] };

type SchemaObject = Record<string, unknown>;
type SchemaDocument = SchemaObject & {
  readonly $id: string;
  readonly $defs: Record<string, SchemaObject>;
};

const documents = [
  envelopeDocument,
  negotiateDocument,
  sessionDocument,
  conversationDocument,
  attachmentDocument,
  eventDocument,
  deviceRequestDocument,
] as unknown as readonly SchemaDocument[];

const addFormats = addFormatsImport as unknown as (ajv: Ajv2020) => Ajv2020;

const definitions: Record<GatewaySchemaName, readonly [SchemaDocument, string]> = {
  "negotiate.request": [negotiateDocument as SchemaDocument, "request"],
  "negotiate.response": [negotiateDocument as SchemaDocument, "response"],
  "session.password": [sessionDocument as SchemaDocument, "password"],
  "session.refresh": [sessionDocument as SchemaDocument, "refresh"],
  "session.device": [sessionDocument as SchemaDocument, "device"],
  "conversation.create": [conversationDocument as SchemaDocument, "create"],
  "message.create": [conversationDocument as SchemaDocument, "messageCreate"],
  "attachment.create": [attachmentDocument as SchemaDocument, "create"],
  event: [eventDocument as SchemaDocument, "event"],
  "device.request": [deviceRequestDocument as SchemaDocument, "request"],
};

const ajv = new Ajv2020({
  strict: true,
  allErrors: true,
  coerceTypes: false,
  removeAdditional: false,
  useDefaults: false,
});
addFormats(ajv);
for (const document of documents) ajv.addSchema(document);

const schemaRef = ([document, definition]: readonly [SchemaDocument, string]): string =>
  `${document.$id}#/$defs/${definition}`;

const validators = new Map<GatewaySchemaName, ValidateFunction>(
  (Object.entries(definitions) as [GatewaySchemaName, readonly [SchemaDocument, string]][]).map(
    ([name, target]) => {
      const validate = ajv.getSchema(schemaRef(target));
      if (validate === undefined) throw new Error(`SCHEMA_NOT_REGISTERED:${name}`);
      return [name, validate];
    },
  ),
);

const normalizeAjvErrors = (
  errors: readonly ErrorObject[] | null | undefined,
): readonly string[] =>
  Object.freeze(
    (errors ?? [])
      .map(({ instancePath, keyword, message }) =>
        `${instancePath === "" ? "/" : instancePath} ${keyword} ${message ?? "validation failed"}`,
      )
      .sort(),
  );

const registeredDefinition = (name: GatewaySchemaName): SchemaObject => {
  const target = definitions[name];
  if (target === undefined) throw new Error(`SCHEMA_NOT_REGISTERED:${String(name)}`);
  const schema = target[0].$defs[target[1]];
  if (schema === undefined) throw new Error(`SCHEMA_NOT_REGISTERED:${name}`);
  return schema;
};

export const schemaFor = (name: GatewaySchemaName): object =>
  structuredClone(registeredDefinition(name));

export const validateGatewayValue = (
  name: GatewaySchemaName,
  value: unknown,
): ValidationResult => {
  const validate = validators.get(name);
  if (validate === undefined) throw new Error(`SCHEMA_NOT_REGISTERED:${String(name)}`);
  return validate(value)
    ? { ok: true }
    : { ok: false, errors: normalizeAjvErrors(validate.errors) };
};
