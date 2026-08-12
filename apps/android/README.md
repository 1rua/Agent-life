# Android MVP skeleton

This tree is deliberately production-shaped but does not include a vendored
Android SDK or Tailscale AAR. The only network seam is
`PairedBridgeTransport.open(VerifiedPairingTransportBinding)`: callers cannot
provide an endpoint or obtain a generic socket operation.

The `tailnet-core` module is an integration spike for the confirmed project-built
minimal `tsnet-android` gomobile AAR. `TsnetLibTailscaleCore` accepts an injected
binding with `startNode`/`openPairedBridge`/`stopNode` methods and keeps node
state behind `NoBackupTailnetStateStore`; replacing that adapter must preserve
the same narrow interface.

`artifact-ports`, `capability-ports` and `control-ports` are source-only closed
contracts for selected attachments, per-capability reads, typed writes,
semantic screen actions and reviewed restricted templates. They are registered
Gradle modules and included in the root no-VPN scan even though they contain no
Android provider, screen service, encryption implementation or command
executor. The assistant-holder and main APK share a bounded text/opaque-grant
handoff contract whose default gate denies until a local user setting enables
it; no implicit IPC is implemented in this source-only slice.

The `sms-collector` module is a separate inbox-only read boundary. It has no
SMS-send, MMS, default-SMS, broadcast-receiver/platform-listener, VPN, generic
socket, URL, or process-execution surface. A local user controls `READ_SMS`, history start,
maximum records, on-demand/auto-send permission, Agent-request permission,
and the closed manual/15/30/60-minute interval choices. Periodic work is
best-effort JobScheduler work, with accepted event wire retained in an
encrypted outbox until a verified Bridge acknowledgement. See
[`docs/mvp/sms-read-readiness.md`](../../docs/mvp/sms-read-readiness.md) for
the evidence boundary and unresolved reboot-scheduling conflict.

Run the SDK-free source gate from the repository root:

```sh
python3 apps/android/tools/test_transport_boundary.py
python3 -m unittest discover -s apps/android/tools -p 'test_*.py'
```

With the pinned Android toolchain installed, run:

```sh
cd apps/android
./gradlew --no-daemon check
```

The source scaffold currently declares provisional Gradle 8.12, AGP 8.9.2,
Kotlin 2.1.20, compile/target SDK 35 and min SDK 34 values; these are not a
controller-approved dependency lock. This checkout does not contain the
wrapper JAR, SDK, or native AAR, so a toolchain-enabled environment must supply
them according to `MVP-DEP-ANDROID` and `MVP-DEP-TSNET` before claiming a
build or P0t device result.

The SMS slice therefore has no Android SDK/device or native AAR validation in
this checkout. Its persisted JobScheduler configuration also conflicts with
the deliberate absence of `RECEIVE_BOOT_COMPLETED`; do not represent periodic
work as reboot-resilient until that reviewed policy decision is made.
