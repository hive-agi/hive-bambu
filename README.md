# hive-bambu

An agent can slice a model with BambuStudio through hive, compare settings, export a print-order bundle and inspect printer commands without bypassing the print gate.

![First layer of the hive geometry slab, drawn from the actual BambuStudio G-code](docs/showcase/plate-first-layer.png)

This plate image plots extrusion moves from the first layer of a real `.gcode.3mf` produced by BambuStudio. The CLI archive did **not** include an embedded PNG thumbnail. See the [run walkthrough](docs/showcase/README.md) and the [reproducible plate renderer](dev/showcase/render_plate.clj).

## What it looks like

| Stage | Result |
|---|---|
| ![Blender geometry](docs/showcase/blender-hive-geometry.png) | Blender `execute_code` built the hive geometry before STL export. |
| ![Blender final stage](docs/showcase/blender-stage-5.png) | Blender `execute_code` produced the final staged scene used for the model. |
| ![Sliced first layer](docs/showcase/plate-first-layer.png) | `bambu-slicer` `slice` produced the `.gcode.3mf`; the plate view plots its first-layer G-code, not a screenshot of BambuStudio. |
| ![All layers by height](docs/showcase/plate-all-layers.png) | The same G-code, all 81 layers, colored by Z: brighter slabs are taller. |

## Try it

Install BambuStudio's Flatpak CLI (`flatpak install flathub com.bambulab.BambuStudio`). In hive-mcp's `~/.config/hive-mcp/config.edn`, declare the addon and its print gate:

```edn
{:addons {"hive.bambu" {:print-gate {:require-confirm true
                                      :max-report-age-ms 15000
                                      :nozzle-max-c 300
                                      :bed-max-c 120}}}}
```

Mount the addon in hive-mcp. `bambu-slicer` launches the installed Flatpak CLI on demand; you do not need to start the GUI to slice. Ask `bambu-slicer` for `presets`, then fingerprint your local model with `hive-bambu.slicer.domain/artifact` and pass its returned `:path`, `:format`, `:sha256` and `:bytes` as `request.model`. For example, after replacing the model fields with that result and confirming the stock profile names from `presets`:

```json
{"command":"slice","request":{"model":{"path":"/absolute/path/model.stl","format":"stl","sha256":"<sha256 from artifact>","bytes":12345},"preset":{"printer":"Bambu Lab X1 Carbon 0.4 nozzle.json","process":"0.20mm Standard @BBL X1C.json","filament":"Bambu PLA Basic @BBL X1C.json"}}}
```

This is the `bambu-slicer` tool payload. A mismatched fingerprint or missing stock profile is refused. Output goes into `~/.cache/hive-bambu/out`, not `/tmp` (Flatpak isolates its temporary directory). `bambu` `catalog` and `doctor` expose printer command vocabulary, but no live printer transport ships by default. Printing is refused unless a config-declared `:print-gate` permits it; a slicer result is not permission to print.

## Measured

A Blender 5.2 STL of the hive geometry slab (91 slabs, about 130 mm after source-side `global_scale=10.0`) was sliced with hive-bambu's `FlatpakCliSlicer` and BambuStudio 2.8.2.61 CLI. Stock profiles: X1 Carbon 0.4 nozzle, 0.20mm Standard and Bambu PLA Basic. The run took **15.1 s** wall time, produced a **3.14 MB** `.gcode.3mf`, and reported **9,822 s** (2 h 44 min) and **19.4 m** of filament. These are slicer estimates, not an actual print. The archive reports zero grams because its filament density is zero; do not treat that as a material measurement.

## Interface

One `bambu` tool has `catalog`, `doctor` and `call` commands. `catalog` reads the extracted slicer, MQTT or blocked G-code descriptors. `doctor` says which catalogs are present and which transports are absent. `call` validates and sends through an injected `PrinterTransport`; absent adapters answer `:bambu/no-transport` with a fix. Unknown commands and unsafe data are refused, not approximated. A separate `bambu-slicer` tool offers `slice`, `compare`, `presets`, `settings`, `export` and `show`.

The portable core returns `{:ok value}` or `{:error {:kind ... :hint ...}}`: slicer argv (not shell text), MQTT topic/payload (transport handles JSON encoding/signatures), decoded report projection, G-code safeguards and a minimal view of already-unzipped 3MF XML. It never reads files or sends messages.

## Layout and transports

| Path | JVM | cljw | cljrs | cljs/node |
|---|---|---|---|---|
| `src/hive_bambu/core.cljc` | portable | portable | portable | portable |
| `dev/hive_bambu/portability.cljc` | 83 assertions | same | same | same |
| `src/hive_bambu/{catalog,ports,addon}.clj` | resource boundary, port, IAddon | — | — | — |
| `test/hive_bambu/stub.clj` | recording/fault injection | — | — | — |
| `resources/hive_bambu/*.edn` | 57 slicer options (18 actions), 17 MQTT commands, 6 blocked codes | injected values | injected values | injected values |

Present transports: a local Flatpak BambuStudio CLI slicer and a test-only in-memory printer stub. Planned, not implemented: `:python` via libpython-clj/paho-mqtt; `:cljs` via native npm imports (`mqtt`, `basic-ftp`); `:cljrs` via Rust cdylib MQTT/TLS; `:slicer` via an in-process C ABI (`hive_call`/`hive_free`) linking libslic3r built from source. No Python or Rust shell-outs; no direct dependency on `hive-mcp`.

## Host config

In hive-mcp's `~/.config/hive-mcp/config.edn`, configure the required safety gate under `:addons`:

```edn
{:addons {"hive.bambu" {:print-gate {:require-confirm true
                                      :max-report-age-ms 15000
                                      :nozzle-max-c 300
                                      :bed-max-c 120}}}}
```

An absent or invalid `:print-gate` refuses mount; an injected `PrintGate` record is also accepted. The Flatpak slicer writes to `~/.cache/hive-bambu/out` by default and refuses `/tmp` roots.

## Verification

```sh
# Interactive: start a cider nREPL with :dev and -J-Xmx2g, then evaluate:
(require '[hive-bambu.core-test] '[hive-bambu.addon-test]
         '[hive-bambu.portability :as portability])
(clojure.test/run-tests 'hive-bambu.core-test 'hive-bambu.addon-test)
(portability/run-gate)

# Cold JVM unit gate (after the interactive session):
clojure -J-Xmx2g -M:test unit
# Other runtimes, sequential, 83 assertions apiece:
bash dev/verify_portability.sh cljw cljrs cljs
# Rebuild extracted descriptions against the checked-out references:
clojure -M:dev dev/extract_catalog.clj
clojure -M:dev dev/extract_mqtt.clj
clojure -M:dev dev/extract_safety.clj
```

The portability script refuses missing hosts and reports elapsed time per leg. JVM gate runs in the attached cider REPL to avoid a second JVM; the cljs build uses shadow-cljs with the 2 GiB heap. Source pointers and measured reference facts are in [docs/reference-surface.md](docs/reference-surface.md).

## Not verified here

No live printer, credentials, firmware signing or FTPS. MQTT publishes are observed only through the test stub. The Flatpak slicer has produced the output above, but the portable core does not parse whole ZIP archives or full XML 3MF. `bambu` `doctor` does not assert binary presence, only reports it unverified. Do not treat an extracted command name as proof it is supported by every firmware revision.

## Licensing

This addon is AGPL-3.0-or-later (see LICENSE), following BambuStudio. A future slicer transport may link libslic3r in-process through a C ABI (`hive_call`/`hive_free`); this is planned, not implemented. The read-only bambu-mcp and mcp-bambu references are MIT (see their LICENSE files). There is no vendored reference implementation here.
