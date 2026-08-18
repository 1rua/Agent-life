# P0t userspace transport spike evidence

Status: source-level spike and reproducible AAR-input gate only (2026-08-11).
The host now has a Go toolchain, gomobile command, JDK and Android SDK command
line tools, but it does not contain a reviewed Tailscale Go source checkout or
an installed NDK. This document does not claim the P0t gate passed.

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

## Reproducible AAR input gate (2026-08-11)

`apps/android/tailnet-core/tools/verify-tsnet-aar-inputs.sh` deliberately
accepts only a local, clean Tailscale Git checkout with all of the following:

- `go.mod` declaring exactly `module tailscale.com` and `tsnet/tsnet.go`;
- `TSNET_SOURCE_COMMIT` matching the checkout's exact `HEAD`;
- explicit Go, gomobile, SDK and installed-NDK paths.

It neither downloads source nor writes a dependency lock. Its blocked result is
therefore a reproducible supply-chain result rather than a substitute AAR:

```text
$ PATH="$PWD/.toolchains/go/bin:$PATH" \
  GO_BIN="$PWD/.toolchains/go/bin/go" \
  GOMOBILE_BIN="$PWD/.toolchains/go/bin/gomobile" \
  ANDROID_HOME="$PWD/.toolchains/android-sdk" \
  ANDROID_NDK_HOME="$PWD/.toolchains/android-sdk/ndk/27.0.12077973" \
  TSNET_SOURCE_DIR="$PWD/third_party/tailscale" \
  TSNET_SOURCE_COMMIT=unlocked \
  bash apps/android/tailnet-core/tools/verify-tsnet-aar-inputs.sh
TSNET_AAR_INPUTS_BLOCKED: TSNET_SOURCE_DIR is not a directory: .../third_party/tailscale
MVP-DEP-TSNET remains pending; no AAR was built.
$ echo $?
2
```

The focused SDK-free regression command is:

```text
bash apps/android/tailnet-core/tools/test_verify_tsnet_aar_inputs.sh
```

It verifies that a missing source directory cannot be silently accepted.

## Current host probe (2026-08-11)

- Go is `go1.25.12`; `gomobile help bind` is available when that Go bin directory
  is on `PATH` and documents Android AAR output.
- JDK is Temurin `17.0.20`; `adb` is platform-tools `37.0.1`.
- `ANDROID_NDK_HOME=.toolchains/android-sdk/ndk/27.0.12077973` is not an
  installed NDK: it currently contains only `.installer/.installData` (4 KiB).
- There is no local `tailscale.com` Go module, `tsnet/tsnet.go`, or reviewed
  source commit. No `tsnet-android` AAR was created, so there is no artifact
  checksum to record.

This is recorded as **BLOCKED / not a P0t pass**, not as a build failure that
can be worked around with another transport.

## Required device evidence before declaring P0t pass

After the controller provides the reviewed Tailscale checkout, installs the
full NDK and freezes its inputs, run the input gate first, then the exact
project build command selected for the narrow bound-client Go package. Record
the AAR SHA-256 only after that command succeeds. Then run the locked toolchain
on arm64-v8a and x86_64 API 34+ environments:

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

## Physical Gate C/D attempt (2026-08-17): BLOCKED

A real `arm64-v8a` API 36 device (`R5CY32BXV8N`, Android 16, 4096-byte pages)
was available. The locked AAR verifier, native tests, Android module checks,
SDK-free no-VPN boundary, dependency-lock validator, and the two empty
connected tasks all exited successfully. Those are not P0t matrix coverage:
both `tailnet-core` and `transport` have zero `androidTest` source files.

The required `p0t/device` runner/framing/collector/validator/secret scanner,
controller descriptor, distinct one-time five-minute enrollment-key inputs,
real Tailnet/Bridge controller, forced DERP and approval policies, controlled
network/Doze/VPN transitions, egress capture, and budget collector are absent.
On this Android 16 build, `dumpsys vpn` reports `Can't find service: vpn`; a
sanitized connectivity baseline observed zero VPN agents and no product
`BIND_VPN_SERVICE`, but cannot replace per-case VPN/route/DNS auditing.

All Gate C/D matrix rows remain **BLOCKED**. See
`docs/mvp/evidence/p0t/2026-08-17T11-35-26Z-r5cy32bxv8n/BLOCKED.md` and
`inventory.json`. The overall P0t gate is **BLOCKED**, not partially passed.
