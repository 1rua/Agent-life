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

Run the SDK-free source gate from the repository root:

```sh
python3 apps/android/tools/test_transport_boundary.py
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