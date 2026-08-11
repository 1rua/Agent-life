# Android artifact ports (MVP source-only)

This module is the typed boundary for selected image/file attachments.  It
models the sequence **user-granted selection → digest/size summary → temporary
encrypted scratch copy → Bridge commit → deletion** without opening a generic
file location or network sink.

The module does not yet implement Android Photo Picker/SAF, encryption,
multipart upload, an object store, or device tests.  `ArtifactReadGrant`,
`ArtifactTicket`, and `EncryptedArtifactCopy` are opaque hand-off records; a
future adapter must supply the real provider and crypto implementations behind
these ports.  Deletion is represented by `deleteAfterCommit` (or an explicit
interrupted-copy discard), so a pre-commit delete cannot be mistaken for a
successful message.
