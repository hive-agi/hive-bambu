(ns hive-bambu.boundary-trifecta-test
  "Golden, property and mutation checks on remaining public boundaries."
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-bambu.core :as core]
            [hive-bambu.catalog :as catalog]
            [hive-bambu.ports :as ports]
            [hive-bambu.addon :as addon]
            [hive-bambu.stub :as stub]))

(deftrifecta refusal-trifecta #'core/refusal
  {:golden-path "test/golden/refusal.edn" :apply? true
   :cases {:missing [:bambu/missing "Provide binary"] :unknown [:bambu/unknown "Check catalog"]}
   :gen (gen/tuple (gen/return :bambu/missing) gen/string-alphanumeric)
   :pred #(and (= :bambu/missing (get-in % [:error :kind])) (string? (get-in % [:error :hint])))
   :num-tests 80
   :mutations [["drop-hint" (fn [kind _] {:error {:kind kind :hint nil}})]]})

(def commands [{:name "print.pause"} {:name "print.gcode_line"}])
(deftrifecta mqtt-request-trifecta #'core/mqtt-request
  {:golden-path "test/golden/mqtt-request.edn" :apply? true
   :cases {:pause [commands ["M112"] "S1" "7" "print.pause" {}]
           :blocked [commands ["M112"] "S1" "8" "print.gcode_line" {:param "M112"}]}
   :gen (gen/tuple (gen/return commands) (gen/return ["M112"]) (gen/return "S1")
                   (gen/return "7") (gen/return "print.pause") (gen/return {}))
   :pred #(= "device/S1/request" (get-in % [:ok :topic])) :num-tests 80
   :mutations [["missing-topic" (fn [& _] {:ok {:topic nil :payload {}}})]]})

(deftrifecta report-trifecta #'core/report
  {:golden-path "test/golden/report.edn" :apply? true
   :cases {:matching ["S1" "device/S1/report" {:print {:sequence_id "7"}}]
           :wrong ["S1" "device/S2/report" {}]}
   :gen (gen/tuple (gen/return "S1") (gen/return "device/S1/report")
                   (gen/return {:print {:sequence_id "7"}}))
   :pred #(= "7" (get-in % [:ok :sequence-id])) :num-tests 80
   :mutations [["discard-sequence" (fn [& _] {:ok {:sequence-id nil}})]]})

(deftrifecta catalog-trifecta #'catalog/read-catalog
  {:golden-path "test/golden/catalog.edn" :apply? true
   :cases {:bad ["bogus"] :blocked ["blocked_gcode"]}
   :gen (gen/tuple (gen/return "blocked_gcode"))
   :pred #(= 6 (count (:ok %))) :num-tests 80
   :mutations [["empty" (fn [_] {:ok []})]]})

(deftrifecta dispatch-trifecta #'ports/dispatch
  {:golden-path "test/golden/dispatch.edn" :apply? true
   :cases {:missing [nil commands ["M112"] "S1" "7" "print.pause" {}]
           :invalid [nil commands ["M112"] "S+" "7" "print.pause" {}]}
   :gen (gen/tuple (gen/return nil) (gen/return commands) (gen/return ["M112"])
                   (gen/return "S1") (gen/return "7") (gen/return "print.pause") (gen/return {}))
   :pred #(= :bambu/no-transport (get-in % [:error :kind])) :num-tests 80
   :mutations [["skip-transport-refusal" (fn [& _] {:ok :sent})]]})

(deftrifecta doctor-trifecta #'addon/doctor
  {:golden-path "test/golden/doctor.edn" :apply? true
   :cases {:diagnostic []}
   :gen (gen/return []) :pred #(= 57 (get-in % [:catalog :slicer])) :num-tests 80
   :mutations [["missing-catalog" (fn [] {:catalog {:slicer 0}})]]})

(deftrifecta execute-trifecta #'addon/execute
  {:golden-path "test/golden/execute.edn" :apply? true
   :cases {:unknown [nil {:command "bogus"}]
           :missing [nil {:command "call" :name "print.pause" :serial "S1" :sequence-id "7"}]}
   :gen (gen/tuple (gen/return nil) (gen/return {:command "bogus"}))
   :pred #(= :bambu/unknown-command (get-in % [:error :kind])) :num-tests 80
   :mutations [["always-success" (fn [& _] {:ok :ignored})]]})

(deftrifecta tool-trifecta #'addon/tool
  {:golden-path "test/golden/tool.edn" :apply? true
   :cases {:unconfigured [nil]}
   :xf #(select-keys % [:name :description :annotations])
   :gen (gen/tuple (gen/return nil))
   :pred #(and (= "bambu" (:name %)) (fn? (:handler %))) :num-tests 80
   :mutations [["empty-tool" (fn [_] {:name "missing" :handler identity})]]})

(deftrifecta ctor-trifecta #'addon/addon-ctor
  {:golden-path "test/golden/ctor.edn" :apply? true
   :cases {:default [nil] :configured [{:transport :injected}]}
   :xf #(select-keys (:config %) [:transport])
   :gen (gen/tuple (gen/return nil))
   :pred #(= :down @(:state %)) :num-tests 80
   :mutations [["missing-default-config" (fn [_] (hive-bambu.addon/->BambuAddon (atom :ready) {:transport :invalid}))]]})
