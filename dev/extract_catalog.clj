(ns extract-catalog
  "Extract active BambuStudio CLI action definitions from PrintConfig.cpp."
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn quoted [s]
  (when-let [a (str/index-of s "\"")]
    (when-let [b (str/index-of s "\"" (inc a))]
      (subs s (inc a) b))))

(defn extract
  "Collect active actions, transforms and miscellaneous CLI options."
  [source]
  (let [segments [["CLIActionsConfigDef::CLIActionsConfigDef()" "CLITransformConfigDef::CLITransformConfigDef()" "action"]
                  ["CLITransformConfigDef::CLITransformConfigDef()" "CLIMiscConfigDef::CLIMiscConfigDef()" "transform"]
                  ["CLIMiscConfigDef::CLIMiscConfigDef()" "const CLIActionsConfigDef" "misc"]]]
    (vec
     (mapcat
      (fn [[begin finish group]]
        (let [start (str/index-of source begin)
              end (str/index-of source finish start)
              lines (str/split-lines (subs source start end))]
          (loop [xs lines active true current nil rows []]
            (if-let [line (first xs)]
              (let [trim (str/trim line)
                    opened? (str/includes? trim "/*")
                    closed? (str/includes? trim "*/")
                    code? (and active (not opened?) (not closed?)
                               (not (str/starts-with? trim "//")))
                    active (cond closed? true opened? false :else active)
                    new? (and code? (str/starts-with? trim "def = this->add("))
                    row (when new? {:name (quoted trim)
                                    :group group
                                    :type (str/trim (subs trim (inc (str/index-of trim ",")) (str/index-of trim ");")))})
                    rows (if (and new? current) (conj rows current) rows)
                    current (cond new? row
                                  (not code?) current
                                  (and current (str/includes? trim "def->tooltip =")) (assoc current :doc (quoted trim))
                                  (and current (str/includes? trim "def->cli =")) (assoc current :cli (quoted trim))
                                  :else current)]
                (recur (rest xs) active current rows))
              (cond-> rows current (conj current)))))) segments))))

(let [source (slurp (str (System/getProperty "user.home") "/PP/hive/clones-ref/BambuStudio/src/libslic3r/PrintConfig.cpp"))
      rows (extract source)]
  (spit "resources/hive_bambu/slicer.edn" (with-out-str (pp/pprint rows)))
  (println "Extracted" (count rows) "active CLI options"))
