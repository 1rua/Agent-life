/**
 * OpenClaw conformance runner.
 *
 * Consumes the shared `gateway-contract/vectors/*.json` documents through the
 * OpenClaw TypeScript Gateway Core and emits standard JSONL plus a manifest.
 * The Hermes side is a separate Python process; the two runners share no
 * runtime binary.
 */

import { createGatewayCore } from "../../integrations/openclaw/src/core/gateway-core.js";
import {
  conformanceArtifactDirectory,
  conformanceContractRoot,
  writeConformanceArtifacts,
  type ConformanceRecord,
} from "./conformance-artifacts.js";

const openClawImplementation = "openclaw-typescript" as const;

const main = (): void => {
  const contractRoot = conformanceContractRoot();
  const directory = conformanceArtifactDirectory();

  const results = createGatewayCore().runSharedVectors(contractRoot);
  const records: ConformanceRecord[] = results.map((result) => ({
    vectorId: result.vectorId,
    operation: result.operation,
    implementation: result.implementation,
    status: result.status,
    resultHash: result.resultHash,
  }));

  writeConformanceArtifacts(openClawImplementation, records, directory, contractRoot);

  const failed = records.filter((record) => record.status !== "pass");
  process.stdout.write(
    `${openClawImplementation}: ${records.length - failed.length}/${records.length} pass\n`,
  );
  for (const record of records) {
    process.stdout.write(
      `${record.status}\t${record.vectorId}\t${record.operation}\t${record.resultHash}\n`,
    );
  }
  if (failed.length > 0) {
    process.stderr.write(`${openClawImplementation}: ${failed.length} vector case(s) failed\n`);
    process.exitCode = 1;
  }
};

main();
