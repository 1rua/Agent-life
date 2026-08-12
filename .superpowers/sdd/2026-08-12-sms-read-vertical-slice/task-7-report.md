# Task 7 report: Bridge SMS service and adapter operations

Implementation commit: `a3800d4e73d230154ce11cdf1ac34204e81a2971` (`feat: add SMS Bridge and agent integrations`)

## Changed files

- `bridge-contract/src/index.ts`
- `bridge-contract/src/service-types.ts`
- `bridge-contract/src/sms-service.ts`
- `bridge-contract/src/sms-store.ts`
- `bridge-contract/src/sms-subscription-store.ts`
- `bridge-contract/test/sms-service.test.ts`
- `integrations/hermes/adapter.test.ts`
- `integrations/hermes/adapter.ts`
- `integrations/hermes/plugin-manifest.json`
- `integrations/openclaw/adapter.test.ts`
- `integrations/openclaw/adapter.ts`
- `integrations/openclaw/plugin-manifest.json`
- `integrations/shared/adapter-contract.test.ts`
- `integrations/shared/adapter.ts`
- `integrations/shared/sms-contract.test.ts`
- `integrations/skills/android-device-bridge/SKILL.md`

This report is recorded separately after the implementation commit so it can contain the immutable implementation SHA.

## Implementation summary

- Added the exact 12-field `SmsRecordV1`/`SmsRecord` shape, u64-backed bigint and `sms:<positive decimal>` validation, nullable sender/thread/SIM handling, complete body preservation including `""`, and closed-field rejection.
- Added monotonic `(sourceEpoch, messageAtEpochMs, cursorProviderId)` append fencing, ascending `(messageAtEpochMs, cursorProviderId)` reads, clone retention, conflict detection, and the `1..10_000` query bound.
- Added paired SMS query/subscription/event/ACK service behavior with policy revision checks, operation idempotency, cross-session binding, and a final pairing-generation recheck immediately before event egress.
- Added byte-equivalent shared Hermes/OpenClaw SMS query/subscription/event behavior and exactly three frozen SMS tool names. Tool inputs reject `operationId`, runtime identity/model fields, arbitrary capability fields, MMS/attachment fields, and unknown SMS operations.
- Added matching provider manifests and Android bridge guidance for local grants, complete bodies, no MMS, and the absence of endpoint/socket/VPN/shell/generic capability surfaces.

## TDD and verification results

RED command:

```sh
./tools/run-node24 npm test -- bridge-contract/test/sms-service.test.ts integrations/shared/sms-contract.test.ts integrations/hermes/adapter.test.ts integrations/openclaw/adapter.test.ts
```

- Exit: `1`, as required before implementation.
- Result: all four target files failed; six adapter tests failed and four existing provider tests passed. The Bridge suite could not import the not-yet-created `sms-store.js`; adapter failures reported the missing `FROZEN_SMS_TOOLS`, `querySms`, and SMS tool routing.

Final focused GREEN command:

```sh
./tools/run-node24 npm test -- bridge-contract/test/sms-service.test.ts integrations/shared/sms-contract.test.ts integrations/hermes/adapter.test.ts integrations/openclaw/adapter.test.ts
```

- Exit: `0`.
- Result: `4` test files passed; `15` tests passed; `0` failed.

Typechecks:

```sh
(cd bridge-contract && ../tools/run-node24 npm run typecheck)
(cd integrations && ../tools/run-node24 npm run typecheck)
```

- Both exited `0` with no TypeScript diagnostics.

Full Node regression suite:

```sh
./tools/run-node24 npm test
```

- Exit: `0`.
- Result: `55` test files passed; `466` tests passed; `0` failed.

Additional checks:

- Both plugin manifests parsed as valid JSON.
- `git diff --check` exited `0`.

## Concerns

- The integrations typecheck exposed an existing nullable test annotation for notification `emitAutoSend`. The test uses a loss marker, which the subscribed path always emits, so the fixture now marks that result non-null without changing notification runtime behavior.
- SMS body egress uses the existing current zero-retention evidence gate in addition to the Android local grant. No unresolved implementation concern remains in the Task 7 scope.
