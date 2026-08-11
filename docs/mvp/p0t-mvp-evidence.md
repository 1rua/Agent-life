# P0t userspace transport spike evidence

Status: source-level spike only (2026-08-11). No Android SDK, Java, Gradle or
physical API-34 device is available in this workspace, so this document does
not claim the P0t gate passed.

## Implemented boundary

- `apps/android/core-model` defines `PairedBridgeTransport`, `BridgeSession`
  and the constructor-private `VerifiedPairingTransportBinding`.
- `apps/android/tailnet-core` defines the narrow userspace-core seam,
  `TsnetLibTailscaleCore` integration adapter, encrypted no-backup state seam,
  and persisted connection-generation/reconnect state machine. The production
  adapter takes an injected `PersistentConnectionGenerationStore`; enrollment
  auth-key clearing is a separate seam and does not erase the node state used
  for process-death restore. The confirmed implementation target is the
  project-built minimal `tsnet-android` AAR; its source commit, build inputs and
  digest remain pending under `MVP-DEP-TSNET`.
- `apps/android/transport` provides a deterministic in-memory userspace fake
  and a real-shaped `TsnetPairedBridgeTransport` adapter. Both accept only a
  verified pairing binding and fence stale sessions by generation.
- `apps/android/gradle/mvp-forbidden-surfaces.gradle.kts` scans source and
  manifests for forbidden system tunnel, route/DNS, proxy/listener and generic
  socket surfaces.

## Host verification run

```text
python3 apps/android/tools/test_transport_boundary.py
......
OK
```

This host gate is intentionally SDK-free. It checks module/APK boundaries,
source presence, no-endpoint method signatures, binding/generation use, and
distinct manifest packages. The Kotlin/JUnit behavior tests live in
`apps/android/transport/src/test` and run when the locked Android toolchain is
available.

## Current environment result (2026-08-11)

The required device/toolchain probe was attempted: `adb` is not installed, no
`java`, `gradle`, or `kotlinc` executable is available, and
`apps/android/gradlew --no-daemon check` exits with the wrapper's
“Gradle 8.9 is required” message. This is recorded as **SKIPPED / not a P0t
pass**, not as a build failure that can be worked around with another transport.

## Required device evidence before declaring P0t pass

Run the locked toolchain on arm64-v8a and x86_64 API 34+ environments:

```text
apps/android/gradlew --no-daemon :tailnet-core:connectedAndroidTest :transport:connectedAndroidTest
apps/android/gradlew --no-daemon check
```

Attach redacted evidence for direct and DERP paths, process-death restore,
network switch, Doze, another-system-VPN coexistence, and blocked split/full
tunnel or always-on+lockdown paths. Verify merged manifests and `dumpsys vpn`
show no product VPN, route/DNS snapshots are unchanged, and egress contains
only approved control/STUN/DERP plus the ticket-bound Bridge. Any failure is a
gate failure; the product must not fall back to public HTTPS or a system VPN.
