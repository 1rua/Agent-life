# MVP dependency lock

This is the controller gate for real Android, userspace Tailnet, Bridge,
Hermes/OpenClaw, model-egress and artifact work. A row is intentionally
`pending` until a controller records the exact official release/commit,
integrity digest, license review, expiry and executable verification command.
The validator fails closed while any row is pending; contract fakes and static
tests remain runnable.

| decision_id | official_reference | immutable_version | integrity | license_review | reviewer_time | evidence_expires_at | verify_command | status | blocks |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MVP-DEP-ANDROID | AGP 8.9.2 / Gradle 8.12 / Kotlin 2.1.20 / compileSdk 35 / targetSdk 35 / minSdk 34 / JDK 17 | AGP 8.9.2, Gradle 8.12, Kotlin 2.1.20, Android SDK 35, AndroidX (latest) | PENDING | PENDING | PENDING | PENDING | PENDING: `./gradlew --no-daemon check` on locked CI | pending | WP-02,WP-03,WP-08,WP-09 |
| MVP-DEP-TSNET | [Tailscale open-source](https://github.com/tailscale/tailscale) latest stable release, gomobile-built AAR for Android (arm64) | latest Tailscale stable, gomobile + NDK, arm64 AABI | PENDING | PENDING | PENDING | PENDING | PENDING: `./gradlew :tailnet-core:check` with real AAR | pending | WP-05,WP-09 |
| MVP-DEP-BRIDGE | Self-implemented: Android SQLite (Room) + Android Keystore + tsnet listener | project-internal, no external runtime dependency | PENDING | PENDING | PENDING | PENDING | PENDING: `./gradlew :bridge-runtime:connectedCheck` | pending | WP-06,WP-09 |
| MVP-DEP-HERMES | Hermes latest stable release | latest Hermes release/tag | PENDING | PENDING | PENDING | PENDING | PENDING: locked plugin-load + smoke test | pending | WP-07,WP-09 |
| MVP-DEP-OPENCLAW | OpenClaw latest stable release | latest OpenClaw release/tag | PENDING | PENDING | PENDING | PENDING | PENDING: locked plugin-load + smoke test | pending | WP-07,WP-09 |
| MVP-DEP-MODEL | Out of scope: model provider is configured on the agent side (Hermes/OpenClaw), not in this project | N/A (agent-side concern) | N/A | N/A | N/A | N/A | N/A: agent-side verification | confirmed | WP-06,WP-08,WP-09 |
| MVP-DEP-ARTIFACT | Self-implemented: local encrypted temp storage + P2P transfer via Bridge, no external object store | project-internal, no external service dependency | PENDING | PENDING | PENDING | PENDING | PENDING: `./gradlew :artifact:connectedCheck` | pending | WP-10 |

Do not replace a pending cell with a guessed version. The next production
checkpoint is to fill these rows from the controller-approved sources, then
run `npm run mvp:lock:check` and attach the resulting evidence.

The local SHA-256 field protects a row from accidental edits inside this
document; it is not provenance or a substitute for verifying the referenced
release/archive/commit. A controller must independently run each row's
`verify_command`, review its license and retain the upstream artifact evidence
before changing `status` to `locked`.