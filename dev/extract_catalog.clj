(ns extract-catalog
  "Extract active BambuStudio CLI action definitions from PrintConfig.cpp."
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn quoted [s]
  (when-let [a (str/index-of s "\"")]
    (when-let [b (str/index-of s "\"" (inc a))]
      (subs s (inc a) b))))

(defn extract [source]
  (let [start (str/index-of source "CLIActionsConfigDef::CLIActionsConfigDef()")
        end (str/index-of source "CLITransformConfigDef::CLITransformConfigDef()")
        lines (str/split-lines (subs source start end))]
    (loop [xs lines active true current nil rows []]
      (if-let [line (first xs)]
        (let [trim (str/trim line)
              active (cond (str/includes? trim "/*") false
                           (str/includes? trim "*/") true
                           :else active)
              new? (and active (str/starts-with? trim "def = this->add("))
              row (when new? {:name (quoted trim)
                              :type (str/trim (subs trim (inc (str/index-of trim ",")) (str/index-of trim ");")))})
              rows (if (and new? current) (conj rows current) rows)
              current (cond new? row
                            (not active) current
                            (and current (str/includes? trim "def->tooltip =")) (assoc current :doc (quoted trim))
                            (and current (str/includes? trim "def->cli =")) (assoc current :cli (quoted trim))
                            :else current)]
          (recur (rest xs) active current rows))
        (cond-> rows current (conj current))))))

(let [source (slurp "/home/klein/PP/hive/clones-ref/BambuStudio/src/libslic3r/PrintConfig.cpp")
      rows (extract source)]
  (spit "resources/hive_bambu/slicer.edn" (with-out-str (pp/pprint rows)))
  (println "Extracted" (count rows) "active CLI actions"))
