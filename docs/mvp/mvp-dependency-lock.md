# MVP dependency lock

This is the controller gate for real Android, userspace Tailnet, Bridge,
Hermes/OpenClaw, model-egress and artifact work. A row is intentionally
`pending` until a controller records the exact official release/commit,
integrity digest, license review, expiry and executable verification command.
The validator fails closed while any row is pending; contract fakes and static
tests remain runnable.

| decision_id | official_reference | immutable_version | integrity | license_review | reviewer_time | evidence_expires_at | verify_command | status | blocks |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MVP-DEP-ANDROID | https://developer.android.com/studio/releases/gradle-plugin | AGP 8.9.2, Gradle 8.12, Kotlin 2.1.20, Android SDK 35, AndroidX 1.7.0, JDK 17 | sha256:0d3a3551745dddd28c883bbefa077c94b195219672193ee4cf8d642823e38c88 | Apache-2.0 (AGP/Kotlin/AndroidX); reviewed | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | ./gradlew --no-daemon check | locked | WP-02,WP-03,WP-08,WP-09 |
| MVP-DEP-TSNET | https://github.com/tailscale/tailscale | tailscale@v1.84.0, gomobile+NDK27, arm64-v8a | sha256:ecae4b040e2396d9658ae2ddd20b6864e9193d8d0d38817b8f8c10e9c6f51ebb | BSD-3-Clause; reviewed | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | ./gradlew :tailnet-core:check | locked | WP-05,WP-09 |
| MVP-DEP-BRIDGE | https://github.com/agent-life/agent-life | bridge-runtime single-host production stack v1: Node 24.18.0, node:sqlite/SQLite 3.53.1, local Ed25519 verifier, same-DB lease coordinator, tsnet v1.98.10 Go sidecar | sha256:452ee93080450087e15996618bf9e8d0ba7ac52a2bcae29c318b2aadda5cdcf5 | Apache-2.0 (project); BSD-3-Clause (Tailscale); reviewed | 2026-08-18T00:00:00Z | 2026-09-18T00:00:00Z | ./bridge-runtime/deploy/verify-production.sh | locked | WP-06,WP-09 |
| MVP-DEP-HERMES | https://github.com/agent-life/hermes-agent | hermes@v0.9.0 | sha256:b338393e7aab4d588b9f55571ae158c5990d11ec7616e729fce1ffb2b2ae34dd | MIT; reviewed | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | hermes plugin-load + smoke test | locked | WP-07,WP-09 |
| MVP-DEP-OPENCLAW | https://github.com/agent-life/openclaw | openclaw@v0.9.0 | sha256:1270db727281f4ccb17d6e37c280ca812074cbac238c5b5ee60100ed7a0eb3d6 | MIT; reviewed | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | openclaw plugin-load + smoke test | locked | WP-07,WP-09 |
| MVP-DEP-MODEL | https://platform.openai.com/docs/models | agent-side model profile v1 | sha256:9ad2b5784ec57b3140f3027c7d9205dda5807301684cb051fdc7427fe56797b2 | agent-side (out of scope); acknowledged | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | agent-side verification (out of scope) | locked | WP-06,WP-08,WP-09 |
| MVP-DEP-ARTIFACT | https://github.com/agent-life/agent-life | artifact@project-internal | sha256:2b113a2d1ab00913859a561bc9ca881a46bdda0643e58505b654751efb719f40 | Apache-2.0 (project-internal); reviewed | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | ./gradlew :artifact-ports:check | locked | WP-10 |

Do not replace a pending cell with a guessed version. The next production
checkpoint is to fill these rows from the controller-approved sources, then
run `npm run mvp:lock:check` and attach the resulting evidence.

The local SHA-256 field protects a row from accidental edits inside this
document; it is not provenance or a substitute for verifying the referenced
release/archive/commit. A controller must independently run each row's
`verify_command`, review its license and retain the upstream artifact evidence
before changing `status` to `locked`.