(ns hive-bambu.schema-probe
  (:require [hive-bambu.printer.schema :as schema]
            [malli.core :as m]))

(defn ^:export probe []
  (clj->js {:report (try (m/form (m/schema schema/Report)) (catch :default e (str e)))
            :verdict (try (m/form (m/schema schema/Verdict)) (catch :default e (str e)))
            :fn-schema (try (m/form (m/schema [:=> [:cat [:maybe schema/Report] [:map-of :keyword :any] :int] schema/Verdict])) (catch :default e (str e)))
            :function (try (m/=> probe [:=> [:cat] :any]) :ok (catch :default e (str e)))
            :merged (try (m/=> probe [:=> [:cat [:maybe schema/Report] [:map-of :keyword :any] :int] schema/Verdict]) :ok (catch :default e (str e)))}))
