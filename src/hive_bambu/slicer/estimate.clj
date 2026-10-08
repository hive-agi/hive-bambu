(ns hive-bambu.slicer.estimate
  "Parse archived slice metadata and estimate filament mass when CLI omits density."
  (:require [malli.core :as m])
  (:import [java.io File]
           [java.util.zip ZipFile]
           [javax.xml.parsers DocumentBuilderFactory]))

(def filament-density {"PLA" 1.24 "PETG" 1.27 "ABS" 1.04 "ASA" 1.07 "TPU" 1.21 "PA" 1.14 "PC" 1.2})

(defn- attributes [node]
  (let [attrs (.getAttributes node)]
    (into {} (for [i (range (.getLength attrs))
                   :let [attr (.item attrs i)]]
               [(.getNodeName attr) (.getNodeValue attr)]))))

(defn- elements [doc tag]
  (let [nodes (.getElementsByTagName doc tag)]
    (mapv (comp attributes #(.item nodes %)) (range (.getLength nodes)))))

(defn- xml [stream]
  (let [factory (DocumentBuilderFactory/newInstance)]
    (.setFeature factory "http://apache.org/xml/features/disallow-doctype-decl" true)
    (.setFeature factory "http://xml.org/sax/features/external-general-entities" false)
    (.setFeature factory "http://xml.org/sax/features/external-parameter-entities" false)
    (.setFeature factory "http://apache.org/xml/features/nonvalidating/load-external-dtd" false)
    (.setXIncludeAware factory false)
    (.setExpandEntityReferences factory false)
    (.parse (.newDocumentBuilder factory) stream)))

(defn- number [v]
  (when (and (string? v) (not (empty? v)))
    (let [n (Double/parseDouble v)]
      (when (Double/isFinite n) n))))

(defn from-document
  "Project XML slice metadata into time, metres and grams; derive mass from length for zero-density CLI profiles."
  [doc]
  (let [metadata (into {} (map (juxt #(get % "key") #(get % "value")) (elements doc "metadata")))
        filaments (elements doc "filament")
        meters (keep #(number (get % "used_m")) filaments)
        grams (map (fn [row]
                     (let [g (number (get row "used_g"))
                           length (number (get row "used_m"))
                           density (get filament-density (get row "type"))]
                       (if (and (or (nil? g) (zero? g)) (some? length) density)
                         (* length 1000 Math/PI 0.25 1.75 1.75 0.001 density)
                         g))) filaments)]
    {:print-seconds (number (get metadata "prediction"))
     :filament-m (when (seq meters) (reduce + 0.0 meters))
     :filament-g (when (and (seq filaments) (every? some? grams)) (reduce + 0.0 grams))}))
(m/=> from-document [:=> [:cat :any] map?])

(defn from-archive
  "Read Metadata/slice_info.config from a 3mf ZIP with external XML entities disabled."
  [archive]
  (try
    (with-open [zip (ZipFile. ^File archive)]
      (when-let [entry (.getEntry zip "Metadata/slice_info.config")]
        (with-open [stream (.getInputStream zip entry)]
          (from-document (xml stream)))))
    (catch Exception _ {:print-seconds nil :filament-g nil :filament-m nil})))
(m/=> from-archive [:=> [:cat :any] [:maybe map?]])
