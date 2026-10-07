(ns hive-bambu.contracts-test
  "Every source defn must have a registered Malli function contract."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [malli.core :as m]
            [hive-bambu.contracts]
            [hive-bambu.addon]
            [hive-bambu.catalog]
            [hive-bambu.ports]
            [hive-bambu.slicer.domain]
            [hive-bambu.slicer.port]
            [hive-bambu.slicer.settings]
            [hive-bambu.slicer.flatpak]))

(defn source-defns [file]
  (let [contents (slurp file)
        forms (with-open [reader (java.io.PushbackReader. (java.io.StringReader. contents))]
                (loop [result []]
                  (let [form (read {:eof ::done :read-cond :allow :features #{:clj}} reader)]
                    (if (= ::done form) result (recur (conj result form))))))
        ns-name (second (first forms))]
    (for [form forms :when (#{'defn} (first form))]
      (symbol (str ns-name) (str (second form))))))

(deftest every-public-source-function-has-contract
  (let [files (file-seq (io/file "src/hive_bambu"))
        defs (set (mapcat source-defns (filter #(and (.isFile %)
                                                     (or (.endsWith (.getName %) ".clj")
                                                         (.endsWith (.getName %) ".cljc"))) files)))
        registered (set (for [[ns-name members] (m/function-schemas)
                              member (keys members)] (symbol (str ns-name) (str member))))]
    (is (<= 12 (count defs)) "The universe must come from real source files, not registry contents")
    (is (empty? (remove registered defs)) (str "Missing contracts: " (remove registered defs)))))
