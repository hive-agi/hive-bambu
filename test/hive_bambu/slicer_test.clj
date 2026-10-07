(ns hive-bambu.slicer-test
  "Port conformance and settings isolation against a deterministic fixture."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-bambu.slicer.domain :as domain]
            [hive-bambu.slicer.port :as port]
            [hive-bambu.slicer.settings :as settings]
            [hive-bambu.slicer.flatpak :as flatpak]
            [hive-bambu.slicer.facade :as facade]))

(def fixture "/home/klein/.cache/hive-craft-blender/out/hive-geometry-slab.stl")
(defn request []
  {:model (:ok (domain/artifact fixture))
   :preset {:printer "Bambu Lab X1 Carbon 0.4 nozzle.json"
            :process "0.20mm Standard @BBL X1C.json"
            :filament "Bambu PLA Basic @BBL X1C.json"}})

(defn port-conformance [s]
  (let [valid (port/slice! s (request))
        invalid (port/slice! s (assoc-in (request) [:model :sha256] "bad"))]
    (is (domain/valid-request? (request)))
    (is (= :slicer/invalid-model (get-in invalid [:error :type])))
    (is (or (and (:ok valid) (m/validate domain/SliceResult (:ok valid)))
            (and (:error valid) (contains? #{:slicer/failed :slicer/timeout :slicer/unavailable} (get-in valid [:error :type])))))
    valid))

(deftest stub-conformance
  (port-conformance (port/stub)))

(deftest flatpak-conformance
  (when (:ok (flatpak/availability))
    (let [result (port-conformance (flatpak/adapter "/home/klein/.cache/hive-craft-bambu/out" 120000))]
      (is (or (:ok result) (:error result))))))

(deftest stock-presets
  (when (:ok (flatpak/availability))
    (is (some #{"Bambu Lab X1 Carbon 0.4 nozzle.json"} (get-in (flatpak/presets) [:ok :machine])))))

(deftest facade-compare-and-export
  (let [request (request)
        variants [{:layer_height 0.12 :sparse_infill_density 10}
                  {:layer_height 0.20 :sparse_infill_density 15}
                  {:layer_height 0.28 :sparse_infill_density 30}]
        rows (:ok (facade/compare (port/stub) request variants))]
    (is (= 3 (count rows)))
    (is (= variants (mapv :overrides rows)))
    (is (every? #(= 3600 (get-in % [:estimate :print-seconds])) rows))
    (is (every? #(= 10.0 (get-in % [:estimate :filament-g])) rows))
    (is (every? #(= :gcode-3mf (get-in % [:result :ok :outputs 0 :format])) rows))
    (is (= 3 (count (facade/bbox-mm (:model request)))))
    (is (= :slicer/invalid-model
           (get-in (facade/export (port/stub) (assoc-in request [:model :sha256] "invalid")
                                  (System/getProperty "java.io.tmpdir")) [:error :type])))))

(deftrifecta override-trifecta
  #'settings/valid-overrides?
  {:golden-path "test/golden/slicer-overrides.edn"
   :cases {:valid {:layer_height 0.2} :invalid {:layer_height 99}}
   :gen (gen/hash-map :layer_height (gen/elements [0.12 0.2 0.28 99]))
   :pred boolean?
   :num-tests 80
   :mutations [["always-valid" (fn [_] true)]]})
