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

(def fixture "test/fixtures/hive-geometry-slab.stl")
(def out-root (str (System/getProperty "user.home") "/.cache/hive-bambu-test"))
(defmacro live-or-skip
  "Run a live flatpak slicer body, or print why it was skipped and assert the absence it relies on."
  [& body]
  `(if (:ok (flatpak/availability))
     (do ~@body)
     (do (println "SKIP: flatpak com.bambulab.BambuStudio absent; live slicer test not run")
         (is (contains? (flatpak/availability) :error)))))
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
  (live-or-skip
    (let [result (port-conformance (flatpak/adapter out-root 120000))]
      (is (or (:ok result) (:error result))))))

(deftest stock-presets
  (live-or-skip
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

(deftest flatpak-export-bundle
  (live-or-skip
    (let [result (facade/export (flatpak/adapter out-root 180000)
                                (request) (str out-root "/orders"))]
      (is (:ok result) (pr-str result))
      (when-let [bundle (:ok result)]
        (is (.isFile (java.io.File. (:model bundle))))
        (is (.isFile (java.io.File. (:slice bundle))))
        (is (.isFile (java.io.File. (:order bundle))))
        (is (clojure.string/includes? (slurp (:order bundle)) "Model bbox (mm):"))
        (is (number? (get-in bundle [:estimate :print-seconds])))))))

(deftest flatpak-override-slice
  (live-or-skip
    (let [result (port/slice! (flatpak/adapter out-root 180000)
                              (assoc (request) :overrides {:layer_height 0.28 :sparse_infill_density 30}))]
      (is (:ok result) (pr-str result))
      (when-let [path (get-in result [:ok :outputs 0 :path])]
        (is (.isFile (java.io.File. path)))
        (is (.isFile (java.io.File. (.getParent (java.io.File. path)) "derived-process.json")))
        (is (.isFile (java.io.File. (.getParent (java.io.File. path)) "derived-filament.json")))))))

(deftest flatpak-compare-variants
  (live-or-skip
    (let [variants [{:layer_height 0.12 :sparse_infill_density 10}
                    {:layer_height 0.20 :sparse_infill_density 15}
                    {:layer_height 0.28 :sparse_infill_density 30}]
          rows (:ok (facade/compare (flatpak/adapter out-root 180000)
                                    (request) variants))]
      (is (= 3 (count rows)))
      (is (= variants (mapv :overrides rows)))
      (is (every? #(get-in % [:result :ok :outputs 0 :path]) rows) (pr-str rows))
      (is (every? #(number? (get-in % [:estimate :print-seconds])) rows))
      (is (every? #(number? (get-in % [:estimate :filament-g])) rows)))))

(deftest slicer-wire-tool
  (let [request (request)
        wire {"model" {"path" (get-in request [:model :path]) "format" "stl"
                       "sha256" (get-in request [:model :sha256]) "bytes" (get-in request [:model :bytes])}
              "preset" {"printer" (get-in request [:preset :printer])
                        "process" (get-in request [:preset :process])
                        "filament" (get-in request [:preset :filament])}
              "overrides" {"layer_height" 0.2}}
        handler (:handler (facade/tool (port/stub)))
        result (handler {"command" "slice" "request" wire})]
    (is (not (:isError result)) (pr-str result))
    (is (clojure.string/includes? (get-in result [:content 0 :text]) "stub.gcode.3mf"))
    (is (= :slicer/invalid-model (get-in (facade/command (port/stub) {:command "slice" :request (assoc-in request [:model :sha256] "bad")}) [:error :type])))))

(deftrifecta override-trifecta
  #'settings/valid-overrides?
  {:golden-path "test/golden/slicer-overrides.edn"
   :cases {:valid {:layer_height 0.2} :invalid {:layer_height 99}}
   :gen (gen/hash-map :layer_height (gen/elements [0.12 0.2 0.28 99]))
   :pred boolean?
   :num-tests 80
   :mutations [["always-valid" (fn [_] true)]]})
