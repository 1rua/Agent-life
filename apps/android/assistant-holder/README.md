# Assistant holder attachment boundary

This APK is only the Android default-assistant entry point. It accepts
user-granted `content://` streams from the explicit Photo Picker or Storage
Access Framework actions and emits a bounded typed payload:

- at most four `AssistantAttachmentSelection` records;
- each record carries only an opaque selection ID, source (`PHOTO_PICKER` or
  `SAF`), read-grant ID, and optional MIME hint;
- no provider location, URL, filesystem handle, bytes, network, shell, or
  upload implementation crosses this APK boundary.

The holder does not mint fake grants.  Without an installed
`AssistantAttachmentGrantIssuer` it emits no attachment selections; a reviewed
local adapter must bind the private provider handle and return the real opaque
selection before the main app can enter the digest/ticket flow.

The main app is responsible for digesting the granted selection and entering
the `artifact-ports` ticket/scratch lifecycle. This module does not claim a
real Photo Picker/SAF adapter, encryption implementation, or upload path.
