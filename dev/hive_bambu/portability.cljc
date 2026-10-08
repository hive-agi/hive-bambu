(ns hive-bambu.portability
  "Same 83 pure assertions on JVM, cljw, cljrs and node/cljs."
  (:require [hive-bambu.core :as core]))

(defn checks
  "Return 83 labelled booleans; runner refuses when a host diverges."
  []
  (vec
   (concat
    (for [plate (range 20)]
      [(str "slice-" plate)
       (= ["studio" "--slice" (str plate) "--outputdir" "out" "file.3mf"]
          (:ok (core/slice-argv "studio" "file.3mf" "out" plate)))])
    (for [plate (range -20 0)]
      [(str "invalid-plate-" plate)
       (= :bambu/invalid-plate
          (get-in (core/slice-argv "studio" "file.3mf" "out" plate) [:error :kind]))])
    (for [i (range 20)]
      [(str "topic-" i)
       (= (str "device/S" i "/report") (:ok (core/topic (str "S" i) "report")))])
    (for [i (range 20)]
      [(str "mqtt-" i)
       (= {:topic "device/S123/request"
           :payload {:print {:sequence_id (str i) :command "pause"}}}
          (:ok (core/mqtt-request [{:name "print.pause"}] [] "S123" (str i)
                                  "print.pause" {})))])
    [["blocked-gcode" (= :bambu/blocked-gcode
                          (get-in (core/gcode-check ["M112"] "M112") [:error :kind]))]
     ["unsafe-xml" (= :bambu/unsafe-xml
                      (get-in (core/plate-model "<!ENTITY x><model></model>") [:error :kind]))]
     ["plate-object" (= ["4"]
                        (get-in (core/plate-model "<model><object id=\"4\"/></model>")
                                [:ok :object-ids]))]])))

(defn run-gate
  "Print pass count and return a result value rather than exiting a host."
  []
  (let [results (checks)
        failures (vec (remove second results))]
    (println "hive-bambu portable:" (- (count results) (count failures))
             "/" (count results) "passed")
    (if (seq failures)
      {:error {:kind :bambu/portability-failed :failures failures}}
      {:ok {:passes (count results)}})))

#?(:cljs (defn ^:export main [] (let [result (run-gate)]
                                 (when (:error result)
                                   (set! (.-exitCode js/process) 1))))
   :default nil)
