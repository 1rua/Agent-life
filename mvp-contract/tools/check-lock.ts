/**
 * Controller dependency-lock validator.
 *
 * This file deliberately contains no package dependencies and uses syntax that
 * Node 22+ can execute with `--experimental-strip-types`.  Vitest imports the
 * same functions for the contract tests.  A lock row is either fully evidenced
 * (`locked`) or explicitly `pending`; pending is always fail-closed.
 */
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const EXPECTED_LOCK_IDS = [
  "MVP-DEP-ANDROID",
  "MVP-DEP-TSNET",
  "MVP-DEP-BRIDGE",
  "MVP-DEP-HERMES",
  "MVP-DEP-OPENCLAW",
  "MVP-DEP-MODEL",
  "MVP-DEP-ARTIFACT",
];

/** Work packets that must be covered by at least one dependency row. */
export const EXPECTED_BLOCKS = [
  "WP-02",
  "WP-03",
  "WP-05",
  "WP-06",
  "WP-07",
  "WP-08",
  "WP-09",
  "WP-10",
];

export const LOCK_COLUMNS = [
  "decision_id",
  "official_reference",
  "immutable_version",
  "integrity",
  "license_review",
  "reviewer_time",
  "evidence_expires_at",
  "verify_command",
  "status",
  "blocks",
];

const stripMarkdown = (value) => value.trim().replace(/^`|`$/g, "").trim();

const parseTableLine = (line) => {
  const trimmed = line.trim();
  if (!trimmed.startsWith("|")) return null;
  const body = trimmed.endsWith("|") ? trimmed.slice(1, -1) : trimmed.slice(1);
  return body.split("|").map(stripMarkdown);
};

/**
 * Parse the first markdown table in a lock document.
 *
 * Parsing is intentionally strict: a prose list or a table with renamed
 * columns cannot accidentally become an accepted lock.
 */
export const parseDependencyLock = (markdown) => {
  const lines = String(markdown).split(/\r?\n/);
  const headerIndex = lines.findIndex((line) => {
    const cells = parseTableLine(line);
    return cells && LOCK_COLUMNS.every((column) => cells.includes(column));
  });
  if (headerIndex < 0) return { rows: [], errors: ["lock table header is missing"] };

  const header = parseTableLine(lines[headerIndex]);
  const indexes = Object.fromEntries(LOCK_COLUMNS.map((column) => [column, header.indexOf(column)]));
  const rows = [];
  for (let index = headerIndex + 1; index < lines.length; index += 1) {
    const cells = parseTableLine(lines[index]);
    if (!cells) {
      if (rows.length > 0) break;
      continue;
    }
    // Ignore the markdown separator row. Any other short row is malformed and
    // is retained as a row so validation can report the missing evidence.
    if (cells.every((cell) => /^:?-{3,}:?$/.test(cell))) continue;
    const row = {};
    for (const column of LOCK_COLUMNS) row[column] = cells[indexes[column]] ?? "";
    rows.push(row);
  }
  return { rows, errors: [] };
};

/** Canonical tuple covered by a row's integrity reference (excluding integrity). */
export const canonicalEvidence = (row) => JSON.stringify(LOCK_COLUMNS
  .filter((column) => column !== "integrity")
  .map((column) => [column, String(row[column] ?? "")]),
);

export const sha256Evidence = (row) => createHash("sha256").update(canonicalEvidence(row), "utf8").digest("hex");

const addError = (errors, message) => {
  if (!errors.includes(message)) errors.push(message);
};

const isPending = (value) => /^pending\b/i.test(String(value).trim());

/**
 * Validate a dependency-lock markdown document. `ok` means every row is
 * syntactically immutable, integrity-bound, unexpired and packet coverage is
 * complete. The local row hash is not provenance; the controller still owns
 * upstream artifact verification and license review before marking a row
 * locked.
 * `pending` lists explicit fail-closed rows separately from malformed rows.
 */
export const validateDependencyLock = (markdown, now = new Date()) => {
  const parsed = parseDependencyLock(markdown);
  const errors = [...parsed.errors];
  const pending = [];
  const counts = new Map();
  const rows = parsed.rows;

  for (const row of rows) counts.set(row.decision_id, (counts.get(row.decision_id) ?? 0) + 1);
  for (const id of EXPECTED_LOCK_IDS) {
    const count = counts.get(id) ?? 0;
    if (count === 0) addError(errors, `missing decision_id ${id}`);
    if (count > 1) addError(errors, `duplicate decision_id ${id}`);
  }
  for (const id of counts.keys()) {
    if (!EXPECTED_LOCK_IDS.includes(id)) addError(errors, `unknown decision_id ${id || "<empty>"}`);
  }

  const covered = new Set();
  const nowEpoch = now instanceof Date ? now.getTime() : new Date(now).getTime();
  if (!Number.isFinite(nowEpoch)) addError(errors, "validator clock is invalid");

  for (const row of rows) {
    const id = row.decision_id || "<empty>";
    const missing = LOCK_COLUMNS.filter((column) => !String(row[column] ?? "").trim());
    for (const column of missing) addError(errors, `${id} missing ${column}`);

    const blocks = String(row.blocks ?? "").split(",").map((value) => value.trim()).filter(Boolean);
    for (const block of blocks) {
      if (!EXPECTED_BLOCKS.includes(block)) addError(errors, `${id} unknown blocks id ${block}`);
      covered.add(block);
    }
    if (blocks.length === 0) addError(errors, `${id} has no blocks`);

    if (isPending(row.status) || LOCK_COLUMNS.some((column) => isPending(row[column]))) {
      if (!pending.includes(id)) pending.push(id);
      continue;
    }

    if (row.status !== "locked") addError(errors, `${id} status must be locked or pending`);
    if (!/^https:\/\//i.test(row.official_reference)) addError(errors, `${id} official_reference must be https`);
    if (/\b(?:pending|latest|main|master|floating)\b/i.test(row.immutable_version)) {
      addError(errors, `${id} immutable_version is not immutable`);
    }
    if (!/^sha256:[a-f0-9]{64}$/i.test(row.integrity)) {
      addError(errors, `${id} integrity must be sha256:<64 hex>`);
    } else if (row.integrity.slice(7).toLowerCase() !== sha256Evidence(row)) {
      addError(errors, `${id} integrity mismatch`);
    }
    if (/\b(?:pending|unreviewed|unknown)\b/i.test(row.license_review)) addError(errors, `${id} license_review is incomplete`);
    const reviewerEpoch = Date.parse(row.reviewer_time);
    if (!Number.isFinite(reviewerEpoch)) addError(errors, `${id} reviewer_time must be an ISO timestamp`);
    const expiryEpoch = Date.parse(row.evidence_expires_at);
    if (!Number.isFinite(expiryEpoch)) addError(errors, `${id} evidence_expires_at must be an ISO timestamp`);
    else if (expiryEpoch <= nowEpoch) addError(errors, `${id} evidence is expired`);
    if (isPending(row.verify_command)) addError(errors, `${id} verify_command is incomplete`);
  }
  for (const block of EXPECTED_BLOCKS) if (!covered.has(block)) addError(errors, `missing blocks id ${block}`);

  pending.sort();
  errors.sort();
  return { ok: errors.length === 0 && pending.length === 0, errors, pending, rows };
};

const runCli = () => {
  const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
  const lockPath = process.env.MVP_DEPENDENCY_LOCK ?? resolve(projectRoot, "docs/mvp/mvp-dependency-lock.md");
  let markdown;
  try {
    markdown = readFileSync(lockPath, "utf8");
  } catch (error) {
    console.error(`MVP dependency lock: FAIL (${lockPath} cannot be read)`);
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
    return;
  }
  const result = validateDependencyLock(markdown);
  if (result.ok) {
    console.log(`MVP dependency lock: PASS (${result.rows.length} rows)`);
    return;
  }
  console.error(`MVP dependency lock: FAIL (${result.rows.length} rows)`);
  for (const message of result.errors) console.error(`- ${message}`);
  for (const id of result.pending) console.error(`- ${id} is pending; production work remains fail-closed`);
  process.exitCode = 1;
};

// Node executes this file directly for the lock gate; Vitest imports the
// exports above and does not set argv[1] to this module.
if (process.argv[1]?.endsWith("mvp-contract/tools/check-lock.ts")) runCli();
