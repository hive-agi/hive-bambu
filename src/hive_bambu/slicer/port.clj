(ns hive-bambu.slicer.port
  "Slicer transport protocol and deterministic conformance stub."
  (:require [malli.core :as m] [hive-bambu.slicer.domain :as domain]))

(defprotocol Slicer
  (slice! [s request] "Slice a validated model into an isolated output directory."))

(defrecord StubSlicer []
  Slicer
  (slice! [_ request]
    (if (domain/valid-request? request)
      {:ok {:outputs [{:path "stub.gcode.3mf" :format :gcode-3mf}]
            :estimate {:print-seconds 3600 :filament-g 10.0 :filament-m 3.0} :warnings []}}
      {:error {:type :slicer/invalid-model}})))

(defn stub
  "Return a deterministic slicer for conformance and offline use."
  [] (->StubSlicer))
(m/=> stub [:=> [:cat] :any])

(defn compare-variants
  "Slice each variant through an injected Slicer and return one measured row per variant."
  [s request variants]
  (mapv (fn [override]
          (let [result (slice! s (update request :overrides merge override))]
            {:overrides override :result result :estimate (get-in result [:ok :estimate])})) variants))
(m/=> compare-variants [:=> [:cat :any map? [:sequential map?]] [:vector map?]])
