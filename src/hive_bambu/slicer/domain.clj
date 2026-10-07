(ns hive-bambu.slicer.domain
  "Slicer values and validation independent of the CLI boundary."
  (:require [malli.core :as m]
            [clojure.string :as str])
  (:import [java.io File RandomAccessFile]
           [java.security MessageDigest]))

(def ModelArtifact [:map [:path string?] [:format [:enum :stl :3mf :obj :step]] [:sha256 string?] [:bytes pos-int?]])
(def SlicePreset [:map [:printer string?] [:process string?] [:filament string?] [:plate {:optional true} nat-int?] [:bed-type {:optional true} string?]])
(def SettingOverrides [:map [:layer_height {:optional true} [:and number? [:fn #(<= 0.05 % 0.5)]]] [:initial_layer_print_height {:optional true} [:and number? [:fn #(<= 0.05 % 0.5)]]] [:wall_loops {:optional true} [:int {:min 0 :max 20}]] [:top_shell_layers {:optional true} [:int {:min 0 :max 20}]] [:bottom_shell_layers {:optional true} [:int {:min 0 :max 20}]] [:sparse_infill_density {:optional true} [:and number? [:fn #(<= 0 % 100)]]] [:enable_support {:optional true} boolean?] [:outer_wall_speed {:optional true} [:and number? pos?]] [:inner_wall_speed {:optional true} [:and number? pos?]] [:nozzle_temperature {:optional true} [:int {:min 0 :max 400}]] [:bed_temperature {:optional true} [:int {:min 0 :max 150}]] [:filament_type {:optional true} string?] [:raw {:optional true} [:map-of string? :any]]])
(def SliceRequest [:map [:model ModelArtifact] [:preset SlicePreset] [:overrides {:optional true} SettingOverrides]])
(def SliceResult [:map [:outputs [:vector [:map [:path string?] [:format [:enum :gcode-3mf :gcode]]]]] [:estimate [:map [:print-seconds [:maybe number?]] [:filament-g [:maybe number?]] [:filament-m [:maybe number?]]]] [:warnings [:vector string?]]])

(defn artifact
  "Validate a local model and compute its immutable fingerprint; return a typed refusal on invalid input."
  [path]
  (let [f (File. (str path))
        ext (some-> (.getName f) (str/split #"\\.") last str/lower-case)
        fmt ({"stl" :stl "3mf" :3mf "obj" :obj "step" :step} ext)
        n (.length f)]
    (if (or (not (.isFile f)) (not fmt) (zero? n) (> n (* 256 1024 1024)))
      {:error {:type :slicer/invalid-model :path (str path)}}
      (try
        (if (and (= fmt :stl)
                 (with-open [r (RandomAccessFile. f "r")]
                   (and (>= n 84)
                        (do (.seek r 80)
                            (not= n (+ 84 (* 50 (Integer/toUnsignedLong (Integer/reverseBytes (.readInt r)))))))))
                 (not (str/starts-with? (slurp f :encoding "US-ASCII") "solid")))
          {:error {:type :slicer/invalid-model :path (str path)}}
          (let [digest (MessageDigest/getInstance "SHA-256")]
            (with-open [input (java.io.FileInputStream. f)]
              (let [buf (byte-array 65536)]
                (loop [] (let [read (.read input buf)]
                           (when (pos? read) (.update digest buf 0 read) (recur))))))
            {:ok {:path (.getAbsolutePath f) :format fmt :sha256 (format "%064x" (BigInteger. 1 (.digest digest))) :bytes n}}))
        (catch Exception e {:error {:type :slicer/invalid-model :message (.getMessage e)}})))))

(m/=> artifact [:=> [:cat :any] map?])

(defn valid-request?
  "Check the slice request schema and model fingerprint."
  [request]
  (and (m/validate SliceRequest request)
       (= (:model request) (:ok (artifact (get-in request [:model :path]))))))

(m/=> valid-request? [:=> [:cat :any] boolean?])
