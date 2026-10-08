(ns hive-bambu.printer.gate
  "Required print safety installer and boundary pipeline."
  (:require [hive-bambu.core :as core]
            [hive-bambu.printer.port :as port]
            [hive-bambu.printer.promote :as promote]
            [hive-bambu.printer.schema :as schema]
            [malli.core :as m]
            [hive-bambu.catalog :as catalog]))

(defprotocol PrintGate
  (authorize! [gate observation now request] "Inspect freshness, idle state, faults and every G-code line."))

(def GateConfig [:map {:closed true}
                 [:require-confirm [:= true]]
                 [:max-report-age-ms [:int {:min 1 :max 60000}]]
                 [:nozzle-max-c [:int {:min 1 :max 300}]]
                 [:bed-max-c [:int {:min 1 :max 120}]]])

(defrecord Guard [blocked policy]
  PrintGate
  (authorize! [_ observation now request]
    (promote/authorize-print blocked observation now request policy)))

(defn guard
  "Install a print gate from an extracted blocked-opcode table."
  [blocked]
  (when (and (sequential? blocked) (seq blocked) (every? string? blocked))
    (->Guard blocked {:require-confirm true :max-report-age-ms 15000 :nozzle-max-c 300 :bed-max-c 120})))

(defn config-gate
  "Build a PrintGate from a closed confirmed safety policy; return nil for invalid data."
  [config]
  (when (m/validate GateConfig config)
    (when-let [blocked (:ok (catalog/read-catalog "blocked_gcode"))]
      (->Guard blocked config))))
(m/=> config-gate [:=> [:cat :any] [:maybe :any]])
(m/=> guard [:=> [:cat sequential?] [:maybe :any]])

(defn print!
  "Publish an authorized job; a success reports publication, not execution."
  [gate link printer now request publication]
  (cond
    (not (satisfies? PrintGate gate))
    (core/refusal :printer/missing-gate "Install PrintGate before mounting or sending print jobs.")
    (not (m/validate schema/PrinterRef printer))
    (core/refusal :printer/invalid-ref "Supply PrinterRef with a secret reference, never a credential value.")
    (not (satisfies? port/PrinterLink link))
    (core/refusal :cljs/unavailable "Build the shadow-cljs node-library adapter and install Node mqtt and basic-ftp.")
    :else (let [observation (port/report link printer)]
            (if (:error observation) observation
                (let [allowed (authorize! gate (:ok observation) now request)]
                  (if (:error allowed) allowed
                      (let [sent (port/send! link printer publication)]
                        (if (:error sent) sent
                            (if (= {:published true} (:ok sent)) sent
                                (core/refusal :printer/invalid-publish "PrinterLink must attest publication only."))))))))))
(m/=> print! [:=> [:cat :any :any :any :int map? map?] schema/Verdict])
