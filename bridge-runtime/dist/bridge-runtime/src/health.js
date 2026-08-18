/** Small dependency-neutral health aggregator used by deployment probes. */
export class BridgeHealth {
    #checks;
    constructor(checks = []) {
        const names = new Set();
        for (const check of checks) {
            if (!check || typeof check.name !== "string" || check.name.length === 0 || typeof check.check !== "function") {
                throw new Error("HEALTH_CHECK_INVALID");
            }
            if (names.has(check.name))
                throw new Error("HEALTH_CHECK_DUPLICATE");
            names.add(check.name);
        }
        this.#checks = Object.freeze([...checks]);
    }
    live() {
        return { status: "ok" };
    }
    async ready() {
        const checks = await Promise.all(this.#checks.map(async (check) => {
            try {
                const result = await check.check();
                if (!result || typeof result.ok !== "boolean")
                    return { name: check.name, ok: false, reason: "HEALTH_CHECK_INVALID_RESULT" };
                return {
                    name: check.name,
                    ok: result.ok,
                    ...(result.reason === undefined ? {} : { reason: result.reason }),
                };
            }
            catch {
                // Never expose exception messages (which can contain credentials or payload text).
                return { name: check.name, ok: false, reason: "HEALTH_CHECK_FAILED" };
            }
        }));
        return {
            status: checks.every((check) => check.ok) ? "ready" : "not_ready",
            checks: Object.freeze(checks),
        };
    }
}
const jsonHeaders = Object.freeze({
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
});
/**
 * Framework-neutral route handler. A real HTTP server may adapt this function
 * to its Tailscale userspace port; it never serves health routes on a public
 * host by itself.
 */
export const createHealthHttpHandler = (health) => async (request) => {
    if (!request || request.method !== "GET")
        return { statusCode: 404, headers: jsonHeaders, body: "{}" };
    if (request.path === "/health/live") {
        return { statusCode: 200, headers: jsonHeaders, body: JSON.stringify(health.live()) };
    }
    if (request.path === "/health/ready") {
        const result = await health.ready();
        return { statusCode: result.status === "ready" ? 200 : 503, headers: jsonHeaders, body: JSON.stringify(result) };
    }
    return { statusCode: 404, headers: jsonHeaders, body: "{}" };
};
