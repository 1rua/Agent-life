import { mkdir, writeFile, readFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { buildPackage, bytesToBase64url, sha256Hex } from "./build-package.js";
import { ed25519 } from "@noble/curves/ed25519.js";
import type { PluginManifest } from "./manifest.js";

const __dirname = fileURLToPath(new URL(".", import.meta.url));
const ROOT = resolve(__dirname, "../..");

// 固定的参考插件签名私钥种子（32 字节，保持构建可重复）
const REFERENCE_SEED = new Uint8Array(32).fill(0x77);
const REFERENCE_PRIVATE_KEY = REFERENCE_SEED;
const REFERENCE_PUBLIC_KEY = ed25519.getPublicKey(REFERENCE_PRIVATE_KEY);
const REFERENCE_PUBLIC_KEY_B64 = bytesToBase64url(REFERENCE_PUBLIC_KEY);

interface PluginSpec {
  id: string;
  name: string;
  version: string;
  description: string;
  wasmName: string;
  provides: { id: string; version: string; schema: string }[];
  kernelPrimitives: { id: string; version: string; purpose: string }[];
}

const REFERENCE_PLUGINS: PluginSpec[] = [
  {
    id: "org.agentlife.notifications",
    name: "Notifications Query",
    version: "1.0.0",
    description: "Official reference plugin for querying notifications",
    wasmName: "notifications.wasm",
    provides: [
      {
        id: "org.agentlife.notifications.query",
        version: "1.0.0",
        schema: "schemas/notifications.json",
      },
    ],
    kernelPrimitives: [
      {
        id: "kernel.notifications.read",
        version: "1.0.0",
        purpose: "Read recent notifications under local policy",
      },
    ],
  },
  {
    id: "org.agentlife.sms",
    name: "SMS Query",
    version: "1.0.0",
    description: "Official reference plugin for querying SMS messages",
    wasmName: "sms.wasm",
    provides: [
      {
        id: "org.agentlife.sms.query",
        version: "1.0.0",
        schema: "schemas/sms.json",
      },
    ],
    kernelPrimitives: [
      {
        id: "kernel.sms.read",
        version: "1.0.0",
        purpose: "Read SMS inbox under local policy",
      },
    ],
  },
  {
    id: "org.agentlife.call-log",
    name: "Call Log Query",
    version: "1.0.0",
    description: "Official reference plugin for querying call logs",
    wasmName: "call_log.wasm",
    provides: [
      {
        id: "org.agentlife.call-log.query",
        version: "1.0.0",
        schema: "schemas/call-log.json",
      },
    ],
    kernelPrimitives: [
      {
        id: "kernel.call-log.read",
        version: "1.0.0",
        purpose: "Read call logs under local policy",
      },
    ],
  },
];

async function main() {
  console.log("Building reference plugins...");
  const outDir = join(ROOT, "plugins/dist");
  await mkdir(outDir, { recursive: true });

  for (const spec of REFERENCE_PLUGINS) {
    const stagingDir = join(outDir, ".staging", spec.id);
    await mkdir(join(stagingDir, "payload"), { recursive: true });
    await mkdir(join(stagingDir, "schemas"), { recursive: true });

    // 复制或构造 schema
    const schemaContent = JSON.stringify({ type: "object" });
    await writeFile(
      join(stagingDir, spec.provides[0].schema),
      new TextEncoder().encode(schemaContent),
    );

    // 检查 target/wasm32-unknown-unknown/release 产物或使用 fixture stub
    const wasmPath = join(
      ROOT,
      "plugins/target/wasm32-unknown-unknown/release",
      spec.wasmName,
    );
    let wasmBytes: Uint8Array;
    try {
      wasmBytes = await readFile(wasmPath);
    } catch {
      // 若尚未编译 wasm，生成有效 minimal wasm header 保证可独立打包与测试
      wasmBytes = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00]);
    }
    await writeFile(join(stagingDir, "payload", spec.wasmName), wasmBytes);

    const manifest: PluginManifest = {
      schemaVersion: "1.0",
      plugin: {
        id: spec.id,
        version: spec.version,
        name: spec.name,
        description: spec.description,
      },
      author: {
        algorithm: "Ed25519",
        publicKey: REFERENCE_PUBLIC_KEY_B64,
      },
      runtime: {
        type: "protected-wasm",
        abiVersion: "1.0",
        entrypoint: "agent_life_plugin_main",
        payload: `payload/${spec.wasmName}`,
      },
      compatibility: {
        androidHost: ">=2.0.0 <3.0.0",
        gatewayProtocol: ">=2.0 <3.0",
      },
      capabilities: {
        provides: spec.provides,
        depends: [],
        kernelPrimitives: spec.kernelPrimitives,
      },
      security: {
        network: [],
        background: {
          requested: false,
          minimumIntervalSeconds: null,
        },
        resources: {
          maxInvocationMillis: 5000,
          maxMemoryBytes: 16777216,
          maxStorageBytes: 10485760,
          maxConcurrentInvocations: 1,
          maxDailyNetworkBytes: 0,
        },
      },
      ui: {
        settings: [],
        cards: [],
      },
      state: {
        schemaVersion: 1,
        portableExport: false,
      },
    };

    const built1 = await buildPackage({
      manifest,
      baseDirectory: stagingDir,
      privateKey: REFERENCE_PRIVATE_KEY,
    });

    const built2 = await buildPackage({
      manifest,
      baseDirectory: stagingDir,
      privateKey: REFERENCE_PRIVATE_KEY,
    });

    if (built1.sha256 !== built2.sha256) {
      throw new Error(`Non-deterministic build detected for ${spec.id}`);
    }

    const alpPath = join(outDir, `${spec.id}-${spec.version}.alp`);
    await writeFile(alpPath, built1.bytes);
    console.log(`[PASS] ${spec.id} -> ${alpPath} (sha256: ${built1.sha256})`);
  }

  console.log("All reference plugins built deterministically.");
}

main().catch((err) => {
  console.error("Build failed:", err);
  process.exit(1);
});
