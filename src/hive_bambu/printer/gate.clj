(ns hive-bambu.printer.gate
  "Required print safety installer and boundary pipeline."
  (:require [hive-bambu.core :as core]
            [hive-bambu.printer.port :as port]
            [hive-bambu.printer.promote :as promote]
            [hive-bambu.printer.schema :as schema]
            [malli.core :as m]))

(defprotocol PrintGate
  (authorize! [gate observation now request] "Inspect freshness, idle state, faults and every G-code line."))

(defrecord Guard [blocked]
  PrintGate
  (authorize! [_ observation now request]
    (promote/authorize-print blocked observation now request)))

(defn guard
  "Install a print gate from an extracted blocked-opcode table."
  [blocked]
  (when (and (sequential? blocked) (seq blocked) (every? string? blocked))
    (->Guard blocked)))
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
