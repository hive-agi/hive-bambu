(ns hive-bambu.core-test
  "Golden, property and mutation coverage of the portable decision layer."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-bambu.core :as core]))

(deftrifecta lookup-trifecta
  #'core/lookup
  {:golden-path "test/golden/lookup.edn"
   :apply? true
   :cases {:found [[{:name "pause"}] "pause"]
           :unknown [[{:name "pause"}] "nope"]}
   :gen (gen/tuple (gen/return [{:name "pause"}])
                   (gen/elements ["pause" "nope"]))
   :pred #(or (contains? % :ok) (= :bambu/unknown-command (get-in % [:error :kind])))
   :num-tests 80
   :mutations [["always-unknown" (fn [_ _] (core/refusal :bambu/unknown-command "bad"))]]})

(deftrifecta slice-argv-trifecta
  #'core/slice-argv
  {:golden-path "test/golden/slice-argv.edn"
   :apply? true
   :cases {:valid ["studio" "piece.3mf" "out" 0]
           :invalid-plate ["studio" "piece.3mf" "out" -1]}
   :gen (gen/tuple (gen/return "studio") (gen/return "piece.3mf")
                   (gen/return "out") (gen/choose -2 50))
   :pred #(or (and (:ok %) (= "--slice" (second (:ok %))))
              (= :bambu/invalid-plate (get-in % [:error :kind])))
   :num-tests 80
   :mutations [["always-refuse" (fn [& _] (core/refusal :bambu/invalid-plate "bad"))]]})

(deftrifecta topic-trifecta
  #'core/topic
  {:golden-path "test/golden/topic.edn"
   :apply? true
   :cases {:request ["S123" "request"] :wildcard ["S+" "request"]}
   :gen (gen/tuple (gen/elements ["S123" "S+"]) (gen/return "request"))
   :pred #(or (= "device/S123/request" (:ok %))
              (= :bambu/invalid-serial (get-in % [:error :kind])))
   :num-tests 80
   :mutations [["always-refuse" (fn [& _] (core/refusal :bambu/invalid-serial "bad"))]]})

(deftrifecta gcode-trifecta
  #'core/gcode-check
  {:golden-path "test/golden/gcode.edn"
   :apply? true
   :cases {:safe [["M112" "M502" "M500" "M501" "M997" "M999"] "G28"]
           :m112 [["M112"] "M112"]
           :m502 [["M502"] "M502"]
           :m500 [["M500"] "M500"]
           :m501 [["M501"] "M501"]
           :m997 [["M997"] "M997"]
           :m999 [["M999"] "M999"]
           :prefix [["M502"] "M5020"]
           :hot [["M112"] "M140 S121"]}
   :gen (gen/tuple (gen/return ["M112"])
                   (gen/elements ["G28" "M112" "M140 S121"]))
   :pred #(contains? #{:bambu/blocked-gcode :bambu/unsafe-temperature nil}
                      (get-in % [:error :kind]))
   :num-tests 80
   :mutations [["always-safe" (fn [_ line] {:ok line})]
               ["exact-only" (fn [blocked line]
                               (if (some #{line} blocked)
                                 (core/refusal :bambu/blocked-gcode "blocked")
                                 {:ok line}))]]})

(deftrifecta plate-model-trifecta
  #'core/plate-model
  {:golden-path "test/golden/plate-model.edn"
   :cases {:empty "<model></model>" :one "<model><object id=\"3\"/></model>"
           :entity "<!DOCTYPE model><model></model>"}
   :gen (gen/elements ["<model></model>" "<model><object id=\"3\"/></model>"
                       "<!DOCTYPE model><model></model>"])
   :pred #(or (map? (:ok %)) (= :bambu/unsafe-xml (get-in % [:error :kind])))
   :num-tests 80
   :mutations [["always-empty" (fn [_] {:ok {:object-ids [] :object-count 0}})]]})

(deftest codec-goldens
  (let [commands [{:name "print.pause"} {:name "print.gcode_line"}
                  {:name "print.print_speed"}]]
    (is (= {:topic "device/S123/request"
            :payload {:print {:sequence_id "7" :command "pause"}}}
           (:ok (core/mqtt-request commands ["M112"] "S123" "7" "print.pause" {}))))
    (is (= :bambu/blocked-gcode (get-in (core/mqtt-request commands ["M112"] "S123" "8" "print.gcode_line" {:param "M112"}) [:error :kind])))
    (is (= :bambu/invalid-speed (get-in (core/mqtt-request commands [] "S123" "9" "print.print_speed" {:param 200}) [:error :kind])))
    (is (= {:print {:gcode_state "RUNNING"} :sequence-id nil}
           (:ok (core/report "S123" "device/S123/report" {:print {:gcode_state "RUNNING"}}))))))
