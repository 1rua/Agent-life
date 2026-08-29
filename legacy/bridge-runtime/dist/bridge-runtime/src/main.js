import { createServer } from "node:http";
import { BridgeHealth } from "./health.js";
const PORT = Number(process.env.BRIDGE_INGRESS_PORT) || 3000;
const health = new BridgeHealth();
const server = createServer(async (req, res) => {
    if (req.method === "GET" && req.url === "/health/ready") {
        const result = await health.ready();
        res.writeHead(result.status === "ready" ? 200 : 503, {
            "Content-Type": "application/json",
        });
        res.end(JSON.stringify(result));
        return;
    }
    if (req.method === "GET" && req.url === "/health/live") {
        const result = health.live();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(result));
        return;
    }
    res.writeHead(404);
    res.end();
});
server.listen(PORT, "0.0.0.0", () => {
    console.log(`bridge-runtime listening on port ${PORT}`);
});
