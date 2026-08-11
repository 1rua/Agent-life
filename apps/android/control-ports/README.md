# Android control ports (source-only contract)

This module freezes the high-risk command boundary without implementing an
Android provider or executor. Typed writes, semantic screen actions and a
small set of reviewed restricted templates all require a local grant, exact
revision/generation fencing, an unexpired parameter-bound confirmation and a
server-issued operation identity. The ports fail closed; they do not run a
process, accept a command string or expose a generic automation surface.

The source is intentionally independent of platform services. A later packet
may implement one backend behind these ports only after permission/device
tests, visible user confirmation and the P0a execution ledger are available.

```sh
python3 -m unittest apps/android/tools/test_control_ports_static.py
```

Passing this check proves only the closed source boundary, not a working
screen controller or background command executor.
