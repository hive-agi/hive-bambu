(ns hive-bambu.contracts
  "JVM-only Malli contracts for the portable core."
  (:require [malli.core :as m]
            [hive-bambu.core]
            [hive-bambu.schema :as schema]))

(m/=> hive-bambu.core/refusal [:=> [:cat keyword? string?] schema/error])
(m/=> hive-bambu.core/lookup [:=> [:cat sequential? :any] schema/response])
(m/=> hive-bambu.core/slice-argv [:=> [:cat :any :any :any :any] schema/response])
(m/=> hive-bambu.core/topic [:=> [:cat :any :any] schema/response])
(m/=> hive-bambu.core/gcode-check [:=> [:cat sequential? :any] schema/response])
(m/=> hive-bambu.core/mqtt-request [:=> [:cat sequential? sequential? :any :any :any :any] schema/response])
(m/=> hive-bambu.core/report [:=> [:cat :any :any :any] schema/response])
(m/=> hive-bambu.core/plate-model [:=> [:cat :any] schema/response])
