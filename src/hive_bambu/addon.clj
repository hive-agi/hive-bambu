(ns hive-bambu.addon
  "JVM IAddon boundary. Wave one has no live printer or slicer transport."
  (:require [hive-addon.protocol :as addon]
            [hive-bambu.catalog :as catalog]
            [hive-bambu.core :as core]
            [hive-bambu.ports :as ports]
            [malli.core :as m]
            [hive-bambu.schema :as schema]
            [hive-bambu.printer.gate :as gate]
            [hive-bambu.printer.port :as printer-port]
            [hive-bambu.slicer.facade :as slicer]))

(defn doctor
  "Diagnose packaged vocabulary and explicitly report unavailable live transports."
  []
  (let [slicer (catalog/read-catalog "slicer")
        mqtt (catalog/read-catalog "mqtt")]
    {:catalog {:slicer (count (:ok slicer)) :mqtt (count (:ok mqtt))}
     :transport :not-configured
     :slicer-binary :not-verified
     :hint "No live transports ship in wave one. Install a PrinterTransport adapter for MQTT; build BambuStudio's CLI to slice."}))

(m/=> doctor [:=> [:cat] map?])

(defn execute
  "Dispatch one of catalog, doctor, call; return only values, not exceptions."
  [transport {:keys [command catalog name serial sequence-id params]}]
  (case command
    "catalog" (let [rows (catalog/read-catalog (or catalog "mqtt"))]
                (if (and name (:ok rows)) (core/lookup (:ok rows) name) rows))
    "doctor" {:ok (doctor)}
    "call" (let [mqtt (catalog/read-catalog "mqtt")
                 blocked (catalog/read-catalog "blocked_gcode")]
             (if (:error mqtt) mqtt
                 (if (:error blocked) blocked
                     (if (= name "print.project_file")
                       (core/refusal :printer/missing-gate "Use the confirmed print pipeline with a fresh idle report and inspected G-code lines.")
                       (ports/dispatch transport (:ok mqtt) (:ok blocked)
                                       serial sequence-id name params)))))
    (core/refusal :bambu/unknown-command "Choose catalog, doctor or call.")))

(m/=> execute [:=> [:cat :any map?] schema/response])

(defn tool
  "One consolidated MCP tool with a closed top-level command vocabulary."
  [transport]
  {:name "bambu"
   :description "Discover BambuStudio and printer commands, diagnose readiness, or call through an injected printer port. Wave one has no live transport."
   :inputSchema {:type "object" :additionalProperties false
                 :properties {"command" {:type "string" :enum ["catalog" "doctor" "call"]}
                              "catalog" {:type "string" :enum ["slicer" "mqtt" "blocked_gcode"]}
                              "name" {:type "string"} "serial" {:type "string"}
                              "sequence-id" {:type "string"}
                              "params" {:type "object"}}
                 :required ["command"]}
   :annotations {:readOnlyHint false :destructiveHint true}
   :handler (fn [args]
              (let [args (into {} (map (fn [[k v]] [(keyword k) v])) args)
                    outcome (execute transport args)]
                {:isError (boolean (:error outcome))
                 :content [{:type "text" :text (pr-str (or (:ok outcome) (:error outcome)))}]}))})

(m/=> tool [:=> [:cat :any] map?])

(defrecord BambuAddon [state config]
  addon/IAddon
  (addon-id [_] "hive.bambu")
  (addon-type [_] :external)
  (capabilities [_] #{:tools :health-reporting})
  (initialize! [_ cfg]
    (let [policy (if (contains? cfg :print-gate) (:print-gate cfg) (:print-gate config))
          print-gate (if (satisfies? gate/PrintGate policy) policy (gate/config-gate policy))]
      (if print-gate
        (do (reset! state {:gate print-gate}) {:success? true :errors []})
        {:success? false :errors [{:kind :printer/missing-gate
                                   :hint "Invalid or missing :print-gate; supply a PrintGate or {:require-confirm true :max-report-age-ms 15000 :nozzle-max-c 300 :bed-max-c 120}."}]})))
  (shutdown! [_] (reset! state :down) nil)
  (tools [_] (if (map? @state) [(tool (:transport config)) (slicer/default-tool)] []))
  (schema-extensions [_] {})
  (excluded-tools [_] #{})
  (hooks [_] {})
  (health [_] {:status (if (map? @state) :degraded :down)
               :details (doctor)}))

(defn addon-ctor
  "Manifest entry point; cfg may inject :transport for an embedding host."
  [cfg]
  (->BambuAddon (atom :down) (or cfg {})))

(m/=> addon-ctor [:=> [:cat :any] :any])
