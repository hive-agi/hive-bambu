# Reference surface (measured 2026-10-07)

Read-only reference checkouts at `/home/klein/PP/hive/clones-ref`. No reference was modified or executed.

| Surface | Evidence | Wave-one choice |
|---|---|---|
| BambuStudio CLI | `BambuStudio/src/libslic3r/PrintConfig.cpp:9941-10090`: `CLIActionsConfigDef` describes slice (`0` all plates), export_3mf, info, etc. | `dev/extract_catalog.clj` extracts 57 active actions/transforms/misc options (18 actions) including name, type, tooltip and optional CLI spelling to `resources/hive_bambu/slicer.edn`; pure argv builder, no subprocess yet. |
| Printer reports | `BambuStudio/src/slic3r/GUI/DeviceManager.cpp:2560-2600` handles full and delta `print.push_status` messages, requests pushall when diff reconstruction fails. | `report` projects an already-decoded report; no false claim of complete state reconstruction. |
| LAN MQTT | `bambu-mcp/src/mqtt-client.ts:222-280,318-340,365-388,390-553` publishes `device/<id>/request`, matches response sequence IDs, uses `pushing.pushall`, `print.pause/resume/stop/print_speed/gcode_line`, AMS, LED, project_file. `mcp-bambu/bambu_mcp/printer.py:36-78,121-147` confirms TLS 8883, `device/<serial>/report`, pushall, basic print commands. | Extract 17 `sendCommand` names with `dev/extract_mqtt.clj`; portable topic/payload/report values, injected `PrinterTransport` only. No MQTT connection, TLS or signing in wave one. |
| Printer authorization | `bambu-mcp/src/mqtt-client.ts:285-313`: optional RSA-SHA256 signing header (`sign_ver`, `sign_alg`, `cert_id`, `payload_len`) when credentials present. | Signing belongs to a future adapter; unsigned payload value is NOT an authorization claim. |
| G-code safeguards | `bambu-mcp/src/safety.ts:4-54`: six blocked commands; nozzle >300 C, bed >120 C. | `dev/extract_safety.clj` emits six tokens, portable core checks those, temperatures and refuses multiline commands. |
| Python bridge | `mcp-bambu/bambu_mcp/printer.py:1-42` imports paho-mqtt; its FTPS path calls curl via subprocess. | Future `:python` adapter imports Python via libpython-clj; do not call its curl shell path. |
| 3MF model | `bambu-mcp/src/mqtt-client.ts:420-470` uses `Metadata/plate_<n>.gcode` inside uploaded 3MF. | Minimal object-ID view over already-unzipped XML text. DTD/entity declarations refused; this is **not** a general XML parser or a ZIP reader. |

The references are BambuStudio (AGPL-3.0, only permitted as separate CLI program), bambu-mcp (MIT; LICENSE:1-21), mcp-bambu (MIT; LICENSE:1-21). The new addon itself is MIT, does not link AGPL code and contains only extracted descriptive facts, not copied implementations. No printer, FTPS endpoint, slicer executable or 3MF archive was exercised here.

Planned adapters: JVM `:python` via libpython-clj importing `bambu_mcp`, cljs Node requiring npm `mqtt` and `basic-ftp`, cljrs Rust cdylib MQTT/TLS, and `:slicer` invoking the BambuStudio CLI (the single allowed subprocess, built from source). Each must be separately implemented, tested and authorized before use.
