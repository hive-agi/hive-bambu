(ns hive-bambu.printer.promote
  "Pure promotion of printer observations."
  (:require [hive-bambu.core :as core]
            [hive-bambu.printer.schema :as schema]
            [malli.core :as m]))

(defn merge-report
  "Merge a sparse print or mc_print delta into an observed report."
  [previous decoded observed-at]
  (let [delta (or (:print decoded) (:mc_print decoded))]
    (if (and (map? decoded) (map? delta) (integer? observed-at))
      {:ok {:observed-at observed-at :print (merge (:print previous) delta)}}
      (core/refusal :printer/invalid-report "Provide a decoded print or mc_print map and observation time."))))

(m/=> merge-report [:=> [:cat [:maybe schema/Report] [:map-of :keyword :any] :int] schema/Verdict])

