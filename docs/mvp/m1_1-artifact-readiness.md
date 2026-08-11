# M1.1 attachment readiness

Status: **source contract GREEN; production artifact path PENDING**.

`artifact-contract/` now covers the user-selected image/file lifecycle without
accepting arbitrary filesystem paths or URLs. It enforces digest-before-ticket,
proof binding, four-file/25 MiB/50 MiB bounds, fresh tickets after interrupted
uploads, post-`message_committed` deletion eligibility and 24-hour orphan
reclamation. `apps/android/artifact-ports/` mirrors the same sequence as a
typed Android seam: granted Photo Picker/SAF selection, digest summary,
temporary encrypted-copy handle, commit receipt and explicit post-commit or
interrupted-copy deletion.
The assistant-holder now emits no synthetic attachment grant: without a
reviewed `AssistantAttachmentGrantIssuer`, its attachment list is empty.

This is not an upload implementation. The Android picker/SAF adapter, actual
digesting and encrypted temporary storage, object-store/scanner, durable
message commit, migration and physical interruption/restart evidence remain
blocked by `MVP-DEP-ARTIFACT` and the Android/Bridge dependency rows. No bytes
are retained by these source contracts or sent to a provider.
