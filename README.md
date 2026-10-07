# hive-bambu

A host-neutral `IAddon` exposing BambuStudio slicer and Bambu LAN-printer command vocabulary as **data and pure values**. Wave one does not connect to printers, upload files or execute a slicer. MIT, published as `io.github.hive-agi/hive-bambu` to Clojars.

## Interface

One `bambu` tool has `catalog`, `doctor` and `call` commands. `catalog` reads the extracted slicer, MQTT or blocked G-code descriptors. `doctor` says which catalogs are present and which transports are absent. `call` validates and sends through an injected `PrinterTransport`; absent adapters answer `:bambu/no-transport` with a fix. Unknown commands and unsafe data are refused, not approximated.

The portable core returns `{:ok value}` or `{:error {:kind ... :hint ...}}`: slicer argv (not shell text), MQTT topic/payload (transport handles JSON encoding/signatures), decoded report projection, G-code safeguards and a minimal view of already-unzipped 3MF XML. It never reads files or sends messages.

## Layout and transports

| Path | JVM | cljw | cljrs | cljs/node |
|---|---|---|---|---|
| `src/hive_bambu/core.cljc` | portable | portable | portable | portable |
| `dev/hive_bambu/portability.cljc` | 83 assertions | same | same | same |
| `src/hive_bambu/{catalog,ports,addon}.clj` | resource boundary, port, IAddon | — | — | — |
| `test/hive_bambu/stub.clj` | recording/fault injection | — | — | — |
| `resources/hive_bambu/*.edn` | 57 slicer options (18 actions), 17 MQTT commands, 6 blocked codes | injected values | injected values | injected values |

Present transport: **test-only in-memory stub**. Planned, not implemented: `:python` via libpython-clj/paho-mqtt; `:cljs` via native npm imports (`mqtt`, `basic-ftp`); `:cljrs` via Rust cdylib MQTT/TLS; `:slicer` via BambuStudio CLI built from source (separate AGPL subprocess only). No Python or Rust shell-outs; no direct dependency on `hive-mcp`.

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

No live printer, credentials, firmware signing, FTPS, whole-archive ZIP reading, full XML 3MF parser, actual BambuStudio executable or slicing output. MQTT publishes are observed only through the test stub. `doctor` does not assert binary presence, only reports it unverified. Do not treat an extracted command name as proof it is supported by every firmware revision.

## Licensing

This addon is MIT (see LICENSE). BambuStudio is AGPL-3.0 and remains separate; its CLI must never be linked into this addon. The read-only bambu-mcp and mcp-bambu references are MIT (see their LICENSE files). There is no vendored reference implementation here.
