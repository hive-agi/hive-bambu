(ns hive-bambu.slicer.domain
  "Slicer values and validation independent of the CLI boundary."
  (:require [malli.core :as m]
            [clojure.string :as str])
  (:import [java.io File RandomAccessFile]
           [java.security MessageDigest]))

(def ModelArtifact [:map [:path string?] [:format [:enum :stl :3mf :obj :step]] [:sha256 string?] [:bytes pos-int?]])
(def SlicePreset [:map [:printer string?] [:process string?] [:filament string?] [:plate {:optional true} nat-int?] [:bed-type {:optional true} string?]])
(def SettingOverrides [:map {:closed false} [:raw {:optional true} [:map-of string? :any]]])
(def SliceRequest [:map [:model ModelArtifact] [:preset SlicePreset] [:overrides {:optional true} SettingOverrides]])
(def SliceResult [:map [:outputs [:vector [:map [:path string?] [:format [:enum :gcode-3mf :gcode]]]]] [:estimate [:map [:print-seconds [:maybe number?]] [:filament-g [:maybe number?]] [:filament-m [:maybe number?]]]] [:warnings [:vector string?]]])

(defn artifact
  "Validate a local model and compute its immutable fingerprint; return a typed refusal on invalid input."
  [path]
  (let [f (File. (str path))
        name (.getName f)
        dot (.lastIndexOf name ".")
        ext (when (pos? dot) (str/lower-case (subs name (inc dot))))
        fmt ({"stl" :stl "3mf" :3mf "obj" :obj "step" :step} ext)
        n (.length f)]
    (if (or (not (.isFile f)) (not fmt) (zero? n) (> n (* 256 1024 1024)))
      {:error {:type :slicer/invalid-model :path (str path)}}
      (try
        (let [valid-stl?
              (or (not= fmt :stl)
                  (with-open [r (RandomAccessFile. f "r")]
                    (if (< n 84) false
                        (do (.seek r 80)
                            (let [triangles (Integer/toUnsignedLong (Integer/reverseBytes (.readInt r)))]
                              (if (= n (+ 84 (* 50 triangles)))
                                (loop [remaining triangles]
                                  (if (zero? remaining) true
                                      (let [finite? (loop [i 0]
                                                      (if (= i 12) true
                                                          (let [v (Float/intBitsToFloat (Integer/reverseBytes (.readInt r)))]
                                                            (if (Float/isFinite v) (recur (inc i)) false))))]
                                        (if finite? (do (.skipBytes r 2) (recur (dec remaining))) false))))
                                (do (.seek r 0)
                                    (let [header (byte-array 5)]
                                      (.readFully r header)
                                      (= "solid" (String. header "US-ASCII"))))))))))]
          (if-not valid-stl?
            {:error {:type :slicer/invalid-model :path (str path)}}
            (let [digest (MessageDigest/getInstance "SHA-256")]
              (with-open [input (java.io.FileInputStream. f)]
                (let [buf (byte-array 65536)]
                  (loop [] (let [read (.read input buf)]
                             (when (pos? read) (.update digest buf 0 read) (recur))))))
              {:ok {:path (.getAbsolutePath f) :format fmt :sha256 (format "%064x" (BigInteger. 1 (.digest digest))) :bytes n}})))
        (catch Exception e {:error {:type :slicer/invalid-model :message (.getMessage e)}})))))

(m/=> artifact [:=> [:cat :any] map?])

(defn valid-request?
  "Check the slice request schema and model fingerprint."
  [request]
  (and (m/validate SliceRequest request)
       (= (:model request) (:ok (artifact (get-in request [:model :path]))))))

(m/=> valid-request? [:=> [:cat :any] boolean?])
