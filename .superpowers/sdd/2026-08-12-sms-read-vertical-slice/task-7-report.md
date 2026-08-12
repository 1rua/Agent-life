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

## Fix round 1

Fix commit: `132c21529f3a4445f2cd56358e0c76f11261b8e7` (`fix: preserve notification tool discovery`)

Review resolution:

- Kept `FROZEN_SMS_TOOLS` frozen and unchanged at exactly `mobile.sms.query`, `mobile.sms.subscribe`, and `mobile.sms.unsubscribe`.
- Exported one frozen `FROZEN_PROVIDER_TOOLS` list containing the three existing notification names followed by the three SMS names.
- Reused that combined list for shared runtime discovery and both Hermes/OpenClaw TypeScript manifest constants.
- Updated both JSON manifests to advertise all six closed tools. No arbitrary tool or capability routing was added.
- Updated provider/shared tests to require notification discovery and require the SMS-filtered subset to remain exactly the three frozen SMS names.

Fix-round RED command:

```sh
./tools/run-node24 npm test -- bridge-contract/test/sms-service.test.ts integrations/shared/sms-contract.test.ts integrations/hermes/adapter.test.ts integrations/openclaw/adapter.test.ts
```

- Exit: `1` before the fix.
- Result: `3` provider-manifest assertions failed because each manifest exposed only the SMS names; the other `12` focused tests passed.

Final fix-round verification:

```sh
set -e
./tools/run-node24 npm test -- bridge-contract/test/sms-service.test.ts integrations/shared/sms-contract.test.ts integrations/hermes/adapter.test.ts integrations/openclaw/adapter.test.ts
(cd bridge-contract && ../tools/run-node24 npm run typecheck)
(cd integrations && ../tools/run-node24 npm run typecheck)
./tools/run-node24 npm test
```

- Focused tests: exit `0`; `4` files and `15` tests passed.
- Bridge typecheck: exit `0`; no diagnostics.
- Integrations typecheck: exit `0`; no diagnostics.
- Full Node suite: exit `0`; `55` files and `466` tests passed.
- Both JSON manifests parsed successfully and matched the exact six-name notification-plus-SMS list.
- `git diff --check` exited `0`.

Fix-round concern:

- An initial non-fail-fast verification attempt added a `node:fs` import to the integrations test and exposed that package's intentional lack of Node ambient types. The filesystem assertion was removed; TypeScript manifest behavior remains covered by Vitest, and on-disk JSON manifests are validated separately with Node. The final fail-fast verification above passed completely.
