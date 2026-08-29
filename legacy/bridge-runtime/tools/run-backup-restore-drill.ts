import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import {
  cleanupRealBridgeBackupRestoreDrill,
  runRealBridgeBackupRestoreDrill,
} from "../src/real-backup-restore-drill.js";

const usage = "usage: node --import tsx tools/run-backup-restore-drill.ts --output <evidence.json>";

const argument = (name: string): string | null => {
  const index = process.argv.indexOf(name);
  if (index === -1) return null;
  const value = process.argv[index + 1];
  return typeof value === "string" && value.length > 0 && !value.startsWith("--") ? value : null;
};

const output = argument("--output");
if (output === null || process.argv.some((value) => value === "--help" || value === "-h")) {
  console.error(usage);
  process.exitCode = 1;
} else {
  const workDir = await mkdtemp(join(tmpdir(), "agent-life-bridge-drill-"));
  try {
    const result = await runRealBridgeBackupRestoreDrill({ workDir });
    const removedPaths = await cleanupRealBridgeBackupRestoreDrill(result);
    const evidence = { ...result, cleanup: { removedPaths } };
    await mkdir(dirname(output), { recursive: true });
    await writeFile(output, `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o644 });
    console.log(`BRIDGE_BACKUP_RESTORE_DRILL_PASS schema=${result.report.schemaVersion} namespaces=${result.report.namespaces.length} digest=${result.report.digest}`);
  } finally {
    await rm(workDir, { recursive: true, force: true });
  }
}
