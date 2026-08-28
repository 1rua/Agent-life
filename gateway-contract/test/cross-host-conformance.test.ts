import { readFileSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

import {
  conformanceContractRoot,
  ensureConformanceArtifacts,
  CONFORMANCE_VECTOR_FILE_NAMES,
  type ConformanceRecord,
} from "../tools/conformance-artifacts.js";

const contractRoot = conformanceContractRoot();
const runnerTimeoutMs = 120_000;

type ProjectedResult = readonly [string, string, string];

const projectResult = (record: ConformanceRecord): ProjectedResult => [
  record.vectorId,
  record.operation,
  record.resultHash,
];

const expectedVectorIds = (): string[] => {
  const ids: string[] = [];
  for (const fileName of CONFORMANCE_VECTOR_FILE_NAMES) {
    const document = JSON.parse(
      readFileSync(join(contractRoot, "vectors", fileName), "utf8"),
    ) as { cases: ReadonlyArray<{ id: string }> };
    for (const vectorCase of document.cases) ids.push(vectorCase.id);
  }
  return ids;
};

describe("Gateway Protocol v2 cross-host conformance", () => {
  it(
    "produces identical result hashes from the OpenClaw and Hermes runners",
    () => {
      const artifacts = ensureConformanceArtifacts();
      const openClaw = artifacts["openclaw-typescript"].records;
      const hermes = artifacts["hermes-python"].records;

      const expectedIds = expectedVectorIds();
      expect(expectedIds.length).toBeGreaterThan(0);
      expect(new Set(expectedIds).size).toBe(expectedIds.length);

      expect(openClaw.map((record) => record.vectorId)).toEqual(expectedIds);
      expect(hermes.map((record) => record.vectorId)).toEqual(expectedIds);

      expect(openClaw.map(projectResult)).toEqual(hermes.map(projectResult));
    },
    runnerTimeoutMs,
  );

  it(
    "keeps both runners on the shared vectors with pass-only normalized results",
    () => {
      const artifacts = ensureConformanceArtifacts();

      for (const [implementation, artifact] of Object.entries(artifacts)) {
        expect(artifact.manifest.implementation, implementation).toBe(implementation);
        expect(artifact.manifest.formatVersion, implementation).toBe("1.0.0");
        expect(artifact.records.length, implementation).toBe(artifact.manifest.caseCount);
        expect(
          artifact.records.map((record) => record.implementation),
          implementation,
        ).toEqual(artifact.records.map(() => implementation));
        expect(
          new Set(artifact.records.map((record) => record.status)),
          implementation,
        ).toEqual(new Set(["pass"]));
        for (const record of artifact.records) {
          expect(record.resultHash, `${implementation}:${record.vectorId}`).toMatch(
            /^sha256:[0-9a-f]{64}$/,
          );
        }
      }
    },
    runnerTimeoutMs,
  );

  it(
    "hashes only the contract-observable result, never host identity or diagnostics",
    () => {
      const artifacts = ensureConformanceArtifacts();
      const openClaw = artifacts["openclaw-typescript"].records;
      const hermes = artifacts["hermes-python"].records;

      // The two runners differ in implementation id, language runtime and
      // process, yet every hash is equal: implementation, status, host id,
      // time, path and diagnostics are excluded from the hashed projection.
      expect(openClaw.length).toBe(hermes.length);
      for (let index = 0; index < openClaw.length; index += 1) {
        const left = openClaw[index]!;
        const right = hermes[index]!;
        expect(left.vectorId).toBe(right.vectorId);
        expect(left.operation).toBe(right.operation);
        expect(left.implementation).not.toBe(right.implementation);
        expect(left.resultHash).toBe(right.resultHash);
      }
    },
    runnerTimeoutMs,
  );
});
