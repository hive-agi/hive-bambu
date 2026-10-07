(ns hive-bambu.extract-printconfig
  "Extract PrintConfig option names without a regex from the pinned C++ reference."
  (:require [clojure.java.io :as io]))

(defn keys-from-source
  "Collect option names from this->add calls, excluding commented-out definitions."
  [path]
  (into (sorted-set)
        (keep (fn [line]
                (let [line (.trim ^String line)
                      anchor "this->add(\""
                      start (.indexOf ^String line anchor)]
                  (when (and (not (.startsWith line "//"))
                             (not (.startsWith line "/*")) (>= start 0))
                    (let [begin (+ start (count anchor))
                          end (.indexOf ^String line "\"" begin)]
                      (when (> end begin) (subs line begin end)))))))
        (line-seq (io/reader path))))

(defn write-resource!
  "Write the pinned PrintConfig key inventory as EDN."
  [source destination]
  (spit destination (pr-str (keys-from-source source))))
