(ns extract-safety
  "Extract blocked G-code tokens from the reference TypeScript constant."
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]))
(let [source (slurp "/home/klein/PP/hive/clones-ref/bambu-mcp/src/safety.ts")
      begin (str/index-of source "BLOCKED_GCODE_PREFIXES = [")
      end (str/index-of source "];" begin)
      lines (str/split-lines (subs source begin end))
      codes (mapv (fn [line] (second (str/split line #"\"")))
                  (filter #(str/starts-with? (str/trim %) "\"") lines))]
  (spit "resources/hive_bambu/blocked_gcode.edn" (with-out-str (pp/pprint codes)))
  (println "Extracted" (count codes) "blocked codes"))
