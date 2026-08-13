# Final-review fix report: SMS read vertical slice

## Status

One consolidated fix wave addressed every Critical and Important finding in
`final-review.md`, plus the deferred cursor-row Minor. The Bridge, wire,
adapter, Android host/JVM, and static test gates pass. Android unit tests and a
debug APK build are available with the repository-local Android 35 SDK.

The aggregate Gradle `check` remains blocked only by the previously documented
product/spec conflict: periodic jobs call `setPersisted(true)`, while the
approved manifest permission allowlist forbids `RECEIVE_BOOT_COMPLETED`.

## Fixes

1. **Bridge session fence**
   - `SmsService.query`, `subscribe`, and `unsubscribe` recheck the paired
     session immediately after asynchronous authorization.
   - `query` also awaits the operation dispatcher and rechecks immediately
     before returning records, covering cached/asynchronous result egress.
   - Added blocked-authorizer generation-rotation regressions for query,
     subscribe, and unsubscribe, plus a delayed-operation query egress test.
   - The existing `publishAuthorized` post-authorizer fence is unchanged.

2. **Bridge owner isolation**
   - `SmsStore` record and position maps now consume a full owner key.
   - `SmsService.ingest` and `query` pass `identityKey(session)`, which includes
     tenant ID, human principal ID, and device ID.
   - Added same-device cross-tenant record-isolation and cross-human
     cursor-isolation regressions.

3. **First-enable UI defaults**
   - `SmsSettingsPresenter` explicitly marks a pristine revision-zero snapshot
     as `firstEnable` and derives its selected history mode/start, record limit,
     and interval from `SmsSettingsDefaults.firstEnable()`.
   - The activity initializes controls from that state and uses the
     presenter's `savePayload`, so the first enable saves a 90-day start, 500
     records, and `MINUTES_30` unless the local user edits them.
   - Added a presenter test for the actual initial state and save payload.

4. **Closed SMS identity/cursor contract**
   - JSON Schema and runtime validators require
     `sms:<positive-decimal-u64>` IDs; MMS, arbitrary, zero, leading-zero, and
     out-of-range IDs are rejected.
   - TypeScript wire validation/encoding requires the record-ID suffix to equal
     `cursor_provider_id` and `cursor_message_at_epoch_ms` to equal
     `metadata.message_at_epoch_ms`.
   - Bridge and shared-adapter validators require record-ID/provider-cursor
     equality. Their normalized record shape has a single message cursor time,
     so it cannot represent the wire time mismatch.
   - Kotlin `SmsMetadata` enforces the same ID grammar/u64 bound; `SmsWireCodec`
     rejects event/metadata ID mismatch and derives both cursor fields from the
     validated metadata/ID.
   - Added schema-execution, TypeScript, Bridge, adapter, and Kotlin regressions.

5. **Bridge monotonicity**
   - Append and subscription publish ordering now compare
     `(messageAtEpochMs, cursorProviderId)` unconditionally.
   - `sourceEpoch` remains validated and retained for event/ACK identity, but
     no longer bypasses or dominates cursor order.
   - Added higher-source-epoch/older-cursor rejection tests for both ledgers.

6. **Android cursor-row Minor**
   - Added cursor-backed tests proving `sub_id = null` maps to
     `subscriptionId = null` and the cursor closes after successful mapping and
     after a missing-column failure.
   - Narrowed the host-test query seam to a closed `SmsProviderTarget.INBOX`
     enum. The production constructor still maps that sole target to
     `Telephony.Sms.Inbox.CONTENT_URI`; the static suite verifies the concrete
     framework URI and prohibited-provider boundary.

## Changed files

- `.superpowers/sdd/2026-08-12-sms-read-vertical-slice/final-review-fix-report.md`
- `bridge-contract/src/sms-service.ts`
- `bridge-contract/src/sms-store.ts`
- `bridge-contract/src/sms-subscription-store.ts`
- `bridge-contract/test/sms-service.test.ts`
- `mvp-contract/schemas/v1/sms-record.schema.json`
- `mvp-contract/src/wire-codec.ts`
- `mvp-contract/test/sms-contract.test.ts`
- `integrations/shared/adapter.ts`
- `integrations/shared/sms-contract.test.ts`
- `apps/android/app/src/main/kotlin/com/agentlife/mobile/MainActivity.kt`
- `apps/android/app/src/test/kotlin/com/agentlife/mobile/SmsSettingsPresenterTest.kt`
- `apps/android/capability-ports/src/main/kotlin/com/agentlife/capability/SmsCapabilityContracts.kt`
- `apps/android/capability-ports/src/test/kotlin/com/agentlife/capability/CapabilityProviderContractsTest.kt`
- `apps/android/sms-collector/src/main/kotlin/com/agentlife/sms/AndroidSmsInboxReader.kt`
- `apps/android/sms-collector/src/main/kotlin/com/agentlife/sms/SmsAutoSyncCoordinator.kt`
- `apps/android/sms-collector/src/main/kotlin/com/agentlife/sms/SmsWireCodec.kt`
- `apps/android/sms-collector/src/test/kotlin/com/agentlife/sms/AndroidSmsInboxReaderTest.kt`
- `apps/android/sms-collector/src/test/kotlin/com/agentlife/sms/SmsAutoSyncCoordinatorTest.kt`
- `apps/android/sms-collector/src/test/kotlin/com/agentlife/sms/SmsTestFixtures.kt`
- `apps/android/sms-collector/src/test/kotlin/com/agentlife/sms/SmsWireCodecTest.kt`

The small `SmsWireCodec` local-value change fixes a Kotlin cross-module smart
cast compilation error exposed once the SDK was supplied. `SmsTestFixtures`
routes test content through the real public authorizer/normalizer instead of
directly constructing an internal `NormalizedContent.Released` value.

## TDD evidence

- Bridge authorizer race RED: three stale requests resolved and mutated or
  returned data; GREEN: all reject with `CONNECTION_FENCED`.
- Query egress RED: a delayed operation result escaped after generation
  rotation; GREEN: the final pre-return fence rejects it.
- Owner isolation RED: cross-tenant queries returned both owners' records and
  a cross-human append failed with `SMS_CURSOR_REPLAY`; GREEN: each owner sees
  and advances only its own ledger.
- First-enable RED: presenter test failed to compile because `nowEpochMs` and
  `savePayload` did not exist; GREEN: exact 90-day/500/30-minute state and
  first-save payload pass.
- Contract RED: four Node contract tests failed on malformed IDs/cursor
  mismatch, and the Kotlin metadata rejection assertion failed; GREEN: all
  relevant schema/runtime/Kotlin suites pass.
- Monotonicity RED: both higher-epoch/older-cursor records were accepted;
  GREEN: append and publish reject them as cursor replay.
- Cursor-row coverage initially exposed Android mockable-jar URI nulls in the
  old test seam; after the closed target seam, all four inbox-reader tests run
  and pass on the host, including success/failure closure.

## Verification evidence

From the repository root:

```sh
./tools/run-node24 npm test -- bridge-contract/test/sms-service.test.ts mvp-contract/test/sms-contract.test.ts integrations/shared/sms-contract.test.ts integrations/hermes/adapter.test.ts integrations/openclaw/adapter.test.ts
```

Passed: `5` files, `28` tests.

```sh
./tools/run-node24 npm test
```

Passed: `55` files, `475` tests.

```sh
./tools/run-node24 npm run typecheck
(cd bridge-contract && ../tools/run-node24 npm run typecheck)
(cd integrations && ../tools/run-node24 npm run typecheck)
```

All three exited 0 with no diagnostics.

```sh
python3 -m unittest discover -s apps/android/tools -p 'test_*.py'
```

Passed: `Ran 60 tests ... OK`.

From `apps/android`, with
`ANDROID_HOME=/home/djbd/项目/Agent-life/.toolchains/android-sdk`:

```sh
./gradlew --no-daemon :capability-ports:testDebugUnitTest :sms-collector:testDebugUnitTest :app:testDebugUnitTest
```

Passed.

```sh
./gradlew --no-daemon test :app:assembleDebug
```

Passed: `BUILD SUCCESSFUL`; all debug/release JVM tests completed and the debug
APK assembled.

```sh
./gradlew --no-daemon check :app:assembleDebug
```

The APK assembled, but aggregate `check` exited 1 at `:sms-collector:lintDebug`:

```text
SmsSyncScheduler.kt:68: Error: Missing permissions required by
Builder.setPersisted: android.permission.RECEIVE_BOOT_COMPLETED
```

This is the existing persisted-job versus permission-allowlist product/spec
decision recorded in the plan ledger and readiness documentation. This fix
wave does not add the forbidden permission or suppress the lint finding.

## Remaining environment limits

- No emulator or physical-device SMS permission/provider test was performed.
- No live SMS provider, reboot persistence, deployment, or end-to-end paired
  transport evidence is claimed.
- Native/project-built Tailscale AAR behavior was not independently validated
  on a device.
- Existing Kotlin 2.2 forward-compatibility and manifest namespace warnings are
  outside this final-review fix scope; they did not fail tests or assembly.

## Final re-review follow-up: SMS provider IDs use signed Long range

### Scope and resolution

The remaining cross-layer contract split is resolved. SMS provider IDs now use
the exact positive signed-Long range `1..9223372036854775807` at every SMS ID
boundary. This applies only to `sms:<providerId>` record IDs and
`cursor_provider_id`/`cursorProviderId`; message/capture/observed times and
source/capture/policy revisions remain decimal u64 values through
`18446744073709551615`.

- Kotlin `SmsMetadata` parses its decimal suffix with `toLongOrNull()`;
  `SmsCursor` and inbox rows require a positive `Long`. The wire codec and
  auto-sync coordinator therefore cannot receive metadata that they cannot
  convert to the Android `Long` provider-ID representation.
- The MVP TypeScript validator/encoder limits the SMS record suffix and cursor
  provider ID to `Long.MAX_VALUE`, without changing its shared u64 helper.
- Bridge `SmsStore` and the shared Hermes/OpenClaw adapter apply the same bound
  to their record suffix and bigint cursor provider ID only.
- `sms-record.schema.json` now has auditable positive-i64 patterns for both
  `record_id` and `cursor_provider_id`; its independent u64 definitions are
  unchanged and AJV executes the boundary assertions.
- No notification protocol or non-SMS capability u64 rule changed, and no
  endpoint, socket, VPN, or shell surface was introduced.

### TDD evidence

The new boundary tests were written before the implementation change. RED
evidence showed the original defect:

- `SmsCapabilityContractsTest` accepted `sms:9223372036854775808`.
- `mvp-contract/test/sms-contract.test.ts` had two failures: the runtime wire
  validator and the compiled JSON Schema accepted the out-of-range provider ID.
- `bridge-contract/test/sms-service.test.ts` and
  `integrations/shared/sms-contract.test.ts` each accepted the same out-of-range
  record.

GREEN coverage accepts `Long.MAX_VALUE`, rejects `Long.MAX_VALUE + 1`, and
asserts that independent u64 timestamps/revisions still accept `ULong.MAX_VALUE`.
The Kotlin codec and cursor tests exercise `Long.MAX_VALUE` through Android's
signed-Long path.

### Verification evidence

Focused GREEN commands:

```sh
ANDROID_HOME=/home/djbd/项目/Agent-life/.toolchains/android-sdk \
  ./gradlew --no-daemon :capability-ports:testDebugUnitTest \
  :sms-collector:testDebugUnitTest \
  --tests 'com.agentlife.capability.SmsCapabilityContractsTest' \
  --tests 'com.agentlife.sms.SmsCursorTest' \
  --tests 'com.agentlife.sms.SmsWireCodecTest'
npm test -- mvp-contract/test/sms-contract.test.ts
(cd bridge-contract && npm test -- sms-service.test.ts)
(cd integrations && npm test -- shared/sms-contract.test.ts)
```

All passed. The Android SDK was available and Gradle ran the actual unit-test
tasks, not an SDK-free substitute.

Full relevant verification:

```sh
ANDROID_HOME=/home/djbd/项目/Agent-life/.toolchains/android-sdk \
  ./gradlew --no-daemon :capability-ports:testDebugUnitTest :sms-collector:testDebugUnitTest
npm test && npm run typecheck
(cd bridge-contract && npm test && npm run typecheck)
(cd integrations && npm test && npm run typecheck)
python3 -m unittest discover -s apps/android/tools -p 'test_*.py'
git diff --check
```

Passed:

- Android capability-port and SMS-collector unit-test tasks: `BUILD SUCCESSFUL`.
- Root Node suite: `55` files, `477` tests; root typecheck exited 0.
- Bridge suite: `6` files, `41` tests; typecheck exited 0.
- Integrations suite: `5` files, `26` tests; typecheck exited 0.
- Android Python static gates: `60` tests, `OK`.
- `git diff --check`: no whitespace errors.
