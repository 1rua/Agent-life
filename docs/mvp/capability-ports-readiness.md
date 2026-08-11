# Capability-port readiness

Status: **contract only; no additional Android data source is implemented**.

The MVP now has a Kotlin `capability-ports` source tree that describes the
closed Android-side seam for the requested data families: notifications, SMS,
calls, contacts, clipboard, location, health, sensors, calendar, alarms,
current window and screen content. It intentionally does not register an
Android provider, request a platform permission, or retain a record.

Each request carries a typed capability filter, policy revision and one of two
sync modes: `ON_DEMAND` or `AUTO_SEND`. A local `CapabilityGrant` must already
exist, must match the exact filter and revision, and must explicitly allow the
requested mode and Agent initiation. Missing grant, stale revision, unavailable
platform support and disabled Agent requests are deterministic denials. The
two modes produce separate authorization objects and separate port methods.

Notifications reuse the existing closed package/field policy. Other data
families currently expose a typed no-extra-filter selection only; that is not a
claim that their Android provider is available. Provider-specific filters and
adapters require a later reviewed contract plus permission/device evidence.

`CapabilityProviderContracts.kt` adds one typed provider interface and payload
normalizer for SMS, calls, contacts, clipboard, location, health, sensors,
calendar, alarms, current window and screen content. Providers receive only an
internally minted, capability-matching on-demand or auto-send scope; the scope
checks the mode, capability, filter and policy revision before an adapter can
read. Raw provider records are internal and every exposed payload represents
content as `Withheld` or an internally-created `Released` value. Because no
non-notification content filter has yet been reviewed, all of those normalizers
currently withhold raw content by default. This is a conservative boundary, not
an implementation of any Android API or a decision to enable those sources.
Screen bytes additionally use an owned-copy snapshot wrapper, so a future
adapter cannot mutate a frame after it has crossed the authorized boundary.

No shell, generic process, UI-control or privileged-backend interface is
defined here. The original design's typed writes/control and restricted command
work remain separate, higher-risk work and cannot be reached through this read
port.

Host-only evidence:

```sh
python3 -m unittest apps/android/tools/test_capability_ports_static.py
```

This evidence verifies source shape only. It does not replace Android SDK,
Gradle, emulator, permission or P0t verification.
