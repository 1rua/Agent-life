# MVP dependency lock

This is the controller gate for real Android, userspace Tailnet, Bridge,
Hermes/OpenClaw, model-egress and artifact work. A row is intentionally
`pending` until a controller records the exact official release/commit,
integrity digest, license review, expiry and executable verification command.
The validator fails closed while any row is pending; contract fakes and static
tests remain runnable.

| decision_id | official_reference | immutable_version | integrity | license_review | reviewer_time | evidence_expires_at | verify_command | status | blocks |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MVP-DEP-ANDROID | PENDING: official Android/AGP/Kotlin/AndroidX references | PENDING: controller must pin AGP, Gradle, Kotlin, SDK and AndroidX | PENDING | PENDING | PENDING | PENDING | PENDING: locked Android CI command | pending | WP-02,WP-03,WP-08,WP-09 |
| MVP-DEP-TSNET | [Tailscale tsnet userspace docs](https://tailscale.com/docs/features/tsnet) + [confirmed project transport decision](../superpowers/specs/2026-08-11-tsnet-android-decision.md) | PENDING: controller must pin source commit, gomobile/NDK/ABI | PENDING | PENDING | PENDING | PENDING | PENDING: reproducible project-built AAR/resource verification | pending | WP-05,WP-09 |
| MVP-DEP-BRIDGE | PENDING: selected Bridge runtime/database/secret-store references | PENDING: controller must pin runtime, migrations and health command | PENDING | PENDING | PENDING | PENDING | PENDING: locked migration/health command | pending | WP-06,WP-09 |
| MVP-DEP-HERMES | PENDING: official Hermes release/plugin reference | PENDING: controller must pin release/tag and profile | PENDING | PENDING | PENDING | PENDING | PENDING: locked plugin-load command | pending | WP-07,WP-09 |
| MVP-DEP-OPENCLAW | PENDING: official OpenClaw Gateway/plugin reference | PENDING: controller must pin release/tag and profile | PENDING | PENDING | PENDING | PENDING | PENDING: locked plugin-load command | pending | WP-07,WP-09 |
| MVP-DEP-MODEL | PENDING: selected provider/profile retention reference | PENDING: controller must pin model destination/profile | PENDING | PENDING | PENDING | PENDING | PENDING: locked zero-retention health command | pending | WP-06,WP-08,WP-09 |
| MVP-DEP-ARTIFACT | PENDING: selected object-store/scanner reference | PENDING: controller must pin artifact service and orphan policy | PENDING | PENDING | PENDING | PENDING | PENDING: locked artifact smoke command | pending | WP-10 |

Do not replace a pending cell with a guessed version. The next production
checkpoint is to fill these rows from the controller-approved sources, then
run `npm run mvp:lock:check` and attach the resulting evidence.

The local SHA-256 field protects a row from accidental edits inside this
document; it is not provenance or a substitute for verifying the referenced
release/archive/commit. A controller must independently run each row's
`verify_command`, review its license and retain the upstream artifact evidence
before changing `status` to `locked`.
