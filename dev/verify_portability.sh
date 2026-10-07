#!/usr/bin/env bash
# Sequential, bounded four-host check. The JVM leg belongs to the cider REPL.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo"
seconds=${PORTABILITY_TIMEOUT:-120}
report=${PORTABILITY_REPORT_DIR:-$(mktemp -d -t bambu-portability.XXXXXX)}
mkdir -p "$report"
printf 'host\tstatus\tseconds\n' > "$report/results.tsv"
failed=0
run() {
  local host=$1; shift
  local start=$SECONDS code=0
  timeout --kill-after=5s "${seconds}s" "$@" > "$report/$host.log" 2>&1 || code=$?
  local status=passed
  if (( code != 0 )); then status=failed; failed=1; fi
  printf '%s\t%s\t%s\n' "$host" "$status" "$((SECONDS-start))" >> "$report/results.tsv"
  echo "$host: $status ($((SECONDS-start))s, exit $code)"
  tail -n 5 "$report/$host.log"
}
if (( $# == 0 )); then set -- cljw cljrs cljs; fi
for host in "$@"; do
  case "$host" in
    cljw) command -v cljw >/dev/null || { echo 'Install cljw first' >&2; exit 2; }
          run cljw cljw -cp src:dev dev/portability.cljw ;;
    cljrs) binary=${CLJRS:-/home/klein/PP/clojurust/target/debug/cljrs}
           [[ -x $binary ]] || { echo "Build cljrs first: $binary" >&2; exit 2; }
           run cljrs "$binary" run --src-path src --src-path dev dev/portability.cljrs ;;
    cljs) command -v clojure >/dev/null || { echo 'Install Clojure CLI first' >&2; exit 2; }
          command -v node >/dev/null || { echo 'Install Node first' >&2; exit 2; }
          run cljs-build clojure -J-Xmx2g -M:cljs -m shadow.cljs.devtools.cli compile portability
          if [[ -f target/portability.js ]]; then run cljs node target/portability.js; else failed=1; fi ;;
    jvm) echo 'JVM: use the cider REPL: (require (quote hive-bambu.portability)) (hive-bambu.portability/run-gate)' ;;
    *) echo "Unknown host $host; choose jvm, cljw, cljrs or cljs" >&2; exit 2 ;;
  esac
done
printf 'Report: %s\n' "$report/results.tsv"
exit "$failed"
