(ns hive-bambu.slicer-followups-test
  "Slice request, estimate and output-root regressions."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-bambu.slicer.domain :as domain]
            [hive-bambu.slicer.flatpak :as flatpak]
            [hive-bambu.slicer.estimate :as estimate]
            [hive-bambu.slicer.port :as port]
            [hive-bambu.slicer-test :as slicer-test]
            [malli.core :as m]))

(defn scale-valid?
  "Validate scale and arrange on a slice request using its domain schema."
  [[scale arrange]]
  (m/validate domain/SliceRequest (assoc (slicer-test/request) :scale scale :arrange arrange)))
(m/=> scale-valid? [:=> [:cat [:tuple :any :any]] boolean?])

(deftrifecta scale-trifecta #'scale-valid?
  {:golden-path "test/golden/slicer-scale.edn"
   :cases {:min [0.01 true] :max [50 false] :zero [0 true] :too-big [50.01 false] :wrong-arrange [10 1]}
   :gen (gen/tuple (gen/elements [0 0.1 1 10 50 51]) (gen/elements [true false 1]))
   :pred boolean? :num-tests 80
   :mutations [["always-accept" (fn [_] true)]]})

(deftest rejects-invalid-scale-at-port
  (is (get-in (port/slice! (port/stub) (assoc (slicer-test/request) :scale 0)) [:error :type]))
  (is (:ok (port/slice! (port/stub) (assoc (slicer-test/request) :scale 10 :arrange true)))))

(defn root-safe? [root] (boolean (:ok (flatpak/valid-output-root root))))
(m/=> root-safe? [:=> [:cat string?] boolean?])
(deftrifecta root-trifecta #'root-safe?
  {:golden-path "test/golden/slicer-output-root.edn"
   :cases {:tmp "/tmp/bambu/out" :safe (str (System/getProperty "user.home") "/.cache/hive-bambu/out")}
   :gen (gen/elements ["/tmp/bambu" "/tmp-other" (str (System/getProperty "java.io.tmpdir") "/nested")
                       (str (System/getProperty "user.home") "/.cache/hive-bambu/out")])
   :pred boolean? :num-tests 80
   :mutations [["allow-all" (fn [_] true)]]})

(deftest rejects-private-temp-before-flatpak
  (let [result (port/slice! (flatpak/adapter "/tmp/private-bambu" 1000) (slicer-test/request))]
    (is (= :slicer/invalid-output-root (get-in result [:error :type])))
    (is (string? (get-in result [:error :reason])))))

(defn estimate-archive [path] (estimate/from-archive (java.io.File. path)))
(m/=> estimate-archive [:=> [:cat string?] map?])
(deftrifecta estimate-trifecta #'estimate-archive
  {:golden-path "test/golden/slicer-estimate.edn"
   :cases {:real "test/fixtures/slice-info.gcode.3mf"}
   :gen (gen/return "test/fixtures/slice-info.gcode.3mf")
   :pred #(and (= 9822.0 (:print-seconds %)) (= 19.4 (:filament-m %)) (> (:filament-g %) 50))
   :num-tests 10
   :mutations [["zero-grams" (fn [_] {:print-seconds 9822.0 :filament-m 19.4 :filament-g 0.0})]]})
