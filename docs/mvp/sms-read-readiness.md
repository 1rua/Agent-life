# SMS read vertical-slice readiness

Status: **host and TypeScript contract evidence only; Android SDK, device, and
native AAR validation remain outstanding.**

## Delivered boundary

The Android collector reads only the inbox URI,
`Telephony.Sms.Inbox.CONTENT_URI`. It has no MMS, sent, draft, or outbox read
path and no SMS-send path. The manifest requests `READ_SMS`, does not request
`RECEIVE_SMS` or `SEND_SMS`, and registers `SmsSyncJobService` as a
non-exported `BIND_JOB_SERVICE` service.

The complete SMS body text, including an empty body, crosses the payload boundary
only after local user authorization has produced a matching SMS scope. The
local user controls the permission prompt, grant, on-demand/auto-send choices,
Agent-request choice, history policy, maximum records, and schedule. History
start and maximum-record settings are local, with the max bounded to 1 through
10,000 records; the interval contract is manual, 15, 30, or 60 minutes.

Auto-sync is best-effort Android scheduling, not a promise of exact timing,
execution, capture, or delivery. A locally authorized batch uses the paired
Bridge boundary and an encrypted outbox. An accepted wire event remains in the
outbox until a verifier-approved acknowledgement; unavailable pairing,
transport failure, invalid acknowledgement, cancellation, and a revoked
egress policy retain it for later recovery.

## Bridge and agent operations

The only SMS operations are `mobile.sms.query`, `mobile.sms.subscribe`, and
`mobile.sms.unsubscribe`. The Bridge validates the closed SMS wire shape and
its policy revision. The shared adapter keeps model-supplied identity fields
and arbitrary capability names out of tool input. Hermes and OpenClaw expose
the same frozen operations and preserve complete body text; neither adds an
MMS, attachment, endpoint, or permission-control operation.

## Evidence and limits

The host static checks protect source boundaries such as module registration,
the inbox-only URI, permissions, non-exported job service, prohibited
VPN/socket/platform-listener/URL/process surfaces, content release, metadata,
history policy, and interval types. They are not a claim of real Android
provider behavior. Kotlin tests cover the Android source contracts and
TypeScript tests cover the protocol, Bridge, Hermes, and OpenClaw contracts.

Run the available verification from the repository root:

```sh
python3 -m unittest discover -s apps/android/tools -p 'test_*.py'
./tools/run-node24 npm test
./tools/run-node24 npm run typecheck
```

Then, with a valid Android SDK and the locked native inputs available:

```sh
cd apps/android
./gradlew --no-daemon check
```

This checkout must not claim Android SDK, emulator/device permission, live SMS
provider, deployment, or native AAR evidence from the host/static suites. The
Android SDK and the project-built Tailscale AAR are environment dependencies;
their absence is a verification blocker, not a passing substitute.

An unresolved policy conflict remains: the source configures persisted periodic
JobScheduler work while the permission allowlist forbids
`RECEIVE_BOOT_COMPLETED`. Android ordinarily needs that permission to restore
scheduled work after reboot. Until a reviewed policy/design resolves this
persisted JobScheduler versus `RECEIVE_BOOT_COMPLETED` conflict, scheduling is
only a best-effort in-process/installed-app configuration and must not be
called reboot-resilient.
