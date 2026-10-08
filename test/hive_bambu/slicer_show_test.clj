(ns hive-bambu.slicer-show-test
  "One owned GUI lifecycle with a recording process adapter."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-bambu.slicer.show :as show]
            [hive-bambu.slicer.facade :as facade]
            [malli.core :as m]))

(def fixture "test/fixtures/slice-info.gcode.3mf")
(defrecord RecordingGui [events]
  show/GuiProcess
  (launch! [_ path]
    (let [token (str "owned-" (count @events))]
      (swap! events conj [:launch token path])
      {:ok token}))
  (close! [_ token]
    (swap! events conj [:close token])
    {:ok true}))

(defn valid-slice? [path] (show/sliced-archive? path))
(m/=> valid-slice? [:=> [:cat :any] boolean?])
(deftrifecta archive-trifecta #'valid-slice?
  {:golden-path "test/golden/show-archive.edn"
   :cases {:real fixture :missing "test/fixtures/no-such-file.3mf"}
   :gen (gen/elements [fixture "test/fixtures/no-such-file.3mf"])
   :pred boolean? :num-tests 40
   :mutations [["allow-all" (fn [_] true)]]})

(deftest one-owned-window
  (let [events (atom [])
        viewer (show/viewer (->RecordingGui events))]
    (is (:ok (show/show! viewer fixture)))
    (is (:ok (show/show! viewer fixture)))
    (is (= [:launch :close :launch] (mapv first @events)))
    (is (= (second (first @events)) (second (second @events))))
    (is (= :slicer/invalid-archive (get-in (show/show! viewer "missing.3mf") [:error :type])))
    (is (= 3 (count @events)))
    (.close ^java.io.Closeable viewer)
    (is (= [:launch :close :launch :close] (mapv first @events)))))

(deftest tool-route-show
  (let [events (atom [])
        viewer (show/viewer (->RecordingGui events))
        handler (:handler (facade/tool nil viewer))]
    (is (not (:isError (handler {"command" "show" "path" fixture}))))
    (is (= :launch (ffirst @events)))
    (.close ^java.io.Closeable viewer)))
