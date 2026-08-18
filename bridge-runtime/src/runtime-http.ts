import type { BridgeHealth } from "./health.js";

export type RuntimeHttpRequest = Readonly<{
  method: string;
  path: string;
  headers: Readonly<Record<string, string | readonly string[] | undefined>>;
  body?: Uint8Array;
}>;

export type RuntimeControlRequest = Readonly<{
  method: string;
  path: string;
  peerFingerprint: string;
  headers: Readonly<Record<string, string | readonly string[] | undefined>>;
  body: string;
}>;

export type RuntimeHttpResponse = Readonly<{
  statusCode: number;
  headers?: Readonly<Record<string, string>>;
  body?: string;
}>;

export type RuntimeControl = (request: RuntimeControlRequest) => RuntimeHttpResponse | Promise<RuntimeHttpResponse>;

export type RuntimeHttpHandlerOptions = Readonly<{
  health: BridgeHealth;
  control: RuntimeControl;
}>;

const json = (statusCode: number, value: unknown): RuntimeHttpResponse => Object.freeze({
  statusCode,
  headers: Object.freeze({
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  }),
  body: JSON.stringify(value),
});

const header = (request: RuntimeHttpRequest, name: string): string | readonly string[] | undefined =>
  Object.entries(request.headers).find(([key]) => key.toLowerCase() === name.toLowerCase())?.[1];

const peerFingerprint = (request: RuntimeHttpRequest): string | "missing" | "invalid" => {
  const value = header(request, "x-agent-life-peer-fingerprint");
  if (value === undefined) return "missing";
  if (typeof value === "string") return value;
  if (value.length === 1 && typeof value[0] === "string") return value[0];
  return "invalid";
};

export const createRuntimeHttpHandler = (options: RuntimeHttpHandlerOptions) => async (
  request: RuntimeHttpRequest,
): Promise<RuntimeHttpResponse> => {
  if (request.method === "GET" && (request.path === "/health/live" || request.path === "/health/ready")) {
    if (request.path === "/health/live") return json(200, options.health.live());
    const result = await options.health.ready();
    return json(result.status === "ready" ? 200 : 503, result);
  }
  const fingerprint = peerFingerprint(request);
  if (fingerprint === "missing") return json(401, { error: "TAILNET_PEER_FINGERPRINT_REQUIRED" });
  if (fingerprint === "invalid" || !/^sha256:[0-9a-f]{64}$/.test(fingerprint)) {
    return json(400, { error: "TAILNET_PEER_FINGERPRINT_INVALID" });
  }
  try {
    return await options.control(Object.freeze({
      method: request.method,
      path: request.path,
      peerFingerprint: fingerprint,
      headers: request.headers,
      body: request.body === undefined ? "" : new TextDecoder().decode(request.body),
    }));
  } catch (caught) {
    if (typeof caught === "object" && caught !== null && "code" in caught) {
      return json(400, { error: String((caught as { code: string }).code) });
    }
    return json(500, { error: "BRIDGE_CONTROL_FAILED" });
  }
};
