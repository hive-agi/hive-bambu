(ns hive-bambu.catalog
  "Resource boundary for reproducibly extracted slicer and printer vocabularies."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hive-bambu.core :as core]
            [malli.core :as m]
            [hive-bambu.schema :as schema]))

(defn read-catalog
  "Load a named bundled EDN catalog; return a value even if packaging is broken."
  [name]
  (if (contains? #{"slicer" "mqtt" "blocked_gcode"} name)
    (if-let [resource (io/resource (str "hive_bambu/" name ".edn"))]
      (try {:ok (edn/read-string (slurp resource))}
           (catch Exception e
             (core/refusal :bambu/invalid-catalog
                           (str "Regenerate the " name " catalog: " (ex-message e)))))
      (core/refusal :bambu/missing-catalog (str "Regenerate and package " name ".edn using dev/extract_*.clj.")))
    (core/refusal :bambu/unknown-catalog "Choose slicer, mqtt or blocked_gcode.")))

(m/=> read-catalog [:=> [:cat :any] schema/response])
