(ns hive-bambu.slicer.facade
  "Local slicing, comparison and third-party order bundles."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [hive-bambu.slicer.domain :as domain]
            [hive-bambu.slicer.flatpak :as flatpak]
            [hive-bambu.slicer.port :as port]
            [hive-bambu.slicer.settings :as settings])
  (:import [java.io RandomAccessFile]
           [java.nio.file Files CopyOption]
           [java.util UUID]))

(defn bbox-mm
  "Measure validated binary STL bounds in millimetres, or nil for another format."
  [model]
  (when (and (= :stl (:format model))
             (= model (:ok (domain/artifact (:path model)))))
    (with-open [r (RandomAccessFile. (:path model) "r")]
      (.seek r 80)
      (let [triangles (Integer/toUnsignedLong (Integer/reverseBytes (.readInt r)))]
        (when (= (:bytes model) (+ 84 (* 50 triangles)))
          (loop [left triangles
                 low [Double/POSITIVE_INFINITY Double/POSITIVE_INFINITY Double/POSITIVE_INFINITY]
                 high [Double/NEGATIVE_INFINITY Double/NEGATIVE_INFINITY Double/NEGATIVE_INFINITY]]
            (if (zero? left)
              (mapv - high low)
              (let [values (mapv (fn [_] (Float/intBitsToFloat (Integer/reverseBytes (.readInt r)))) (range 12))
                    points (partition 3 (drop 3 values))]
                (.skipBytes r 2)
                (recur (dec left)
                       (reduce (fn [bounds xyz] (mapv min bounds xyz)) low points)
                       (reduce (fn [bounds xyz] (mapv max bounds xyz)) high points))))))))))
(m/=> bbox-mm [:=> [:cat map?] :any])

(defn compare
  "Slice each variant of a request through the supplied Slicer, preserving failures."
  [s request variants]
  {:ok (port/compare-variants s request variants)})
(m/=> compare [:=> [:cat :any map? [:sequential map?]] map?])

(defn- order-text [request estimate bbox]
  (let [o (:overrides request)]
    (str "# Print order\n\n"
         "Material: " (or (:filament_type o) (get-in request [:preset :filament])) "\n"
         "Colour: " (or (:colour request) "Specify with print service") "\n"
         "Layer height (mm): " (or (:layer_height o) (get-in request [:preset :process])) "\n"
         "Infill: " (or (:sparse_infill_density o) "stock profile") "\n"
         "Walls: " (or (:wall_loops o) "stock profile") "\n"
         "Supports: " (if (contains? o :enable_support) (:enable_support o) "stock profile") "\n"
         "Estimated seconds: " (:print-seconds estimate) "\n"
         "Estimated grams: " (:filament-g estimate) "\n"
         "Model bbox (mm): " (if bbox (str/join " x " bbox) "not available") "\n")))

(defn export
  "Slice once and copy the model, sliced project and order sheet into a new local folder."
  [s request output-root]
  (if-not (domain/valid-request? request)
    {:error {:type :slicer/invalid-model}}
    (let [result (port/slice! s request)]
      (if-let [failure (:error result)]
        {:error failure}
        (let [folder (io/file output-root (str (UUID/randomUUID)))
              source (io/file folder (str "model." (name (get-in request [:model :format]))))
              archive (io/file folder "slice.gcode.3mf")
              sliced (io/file (get-in result [:ok :outputs 0 :path]))]
          (try
            (Files/createDirectories (.toPath folder) (make-array java.nio.file.attribute.FileAttribute 0))
            (Files/copy (.toPath (io/file (get-in request [:model :path]))) (.toPath source) (make-array CopyOption 0))
            (Files/copy (.toPath sliced) (.toPath archive) (make-array CopyOption 0))
            (let [order (io/file folder "order.md")]
              (spit order (order-text request (get-in result [:ok :estimate]) (bbox-mm (:model request))))
              {:ok {:directory (.getAbsolutePath folder)
                    :model (.getAbsolutePath source) :slice (.getAbsolutePath archive)
                    :order (.getAbsolutePath order) :estimate (get-in result [:ok :estimate])}})
            (catch Exception e {:error {:type :slicer/failed :message (.getMessage e)}})))))))
(m/=> export [:=> [:cat :any map? string?] map?])

(defn command
  "Dispatch the five local slicer tool operations through an injected port."
  [s {:keys [command request variants output-root]}]
  (case command
    "slice" (port/slice! s request)
    "compare" (compare s request variants)
    "presets" (flatpak/presets)
    "settings" {:ok (settings/settings)}
    "export" (export s request output-root)
    {:error {:type :slicer/unknown-command :command command}}))

(defn wire-request
  "Normalize tool JSON request keys and model format without changing raw override keys."
  [request]
  (let [keys->keywords (fn [row] (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v])) row))
        row (keys->keywords request)
        model (keys->keywords (:model row))
        preset (keys->keywords (:preset row))
        overrides (keys->keywords (or (:overrides row) {}))]
    (cond-> (assoc row :model (update model :format #(if (string? %) (keyword %) %)) :preset preset)
      (contains? row :overrides) (assoc :overrides overrides))))
(m/=> wire-request [:=> [:cat map?] map?])

(defn tool
  "Project slicer operations into one tool with a closed command vocabulary."
  [s]
  {:name "bambu-slicer"
   :description "Slice a model, compare settings, inspect presets/settings, or export a local print-order bundle."
   :inputSchema {:type "object" :additionalProperties false
                 :properties {"command" {:type "string" :enum ["slice" "compare" "presets" "settings" "export"]}
                              "request" {:type "object"} "variants" {:type "array" :items {:type "object"}}
                              "output-root" {:type "string"}}
                 :required ["command"]}
   :annotations {:readOnlyHint false :destructiveHint false}
   :handler (fn [args]
              (let [row (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v])) args)
                    row (cond-> row
                          (:request row) (update :request wire-request)
                          (:variants row) (update :variants #(mapv (fn [variant]
                                                                    (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v])) variant)) %)))
                    outcome (command s row)]
                {:isError (boolean (:error outcome))
                 :content [{:type "text" :text (pr-str (or (:ok outcome) (:error outcome)))}]}))})

(defn default-tool
  "Construct a Flatpak-backed tool with fresh output directories."
  []
  (tool (flatpak/adapter (str (System/getProperty "user.home") "/.cache/hive-bambu/out") 180000)))
(m/=> default-tool [:=> [:cat] map?])
(m/=> tool [:=> [:cat :any] map?])
(m/=> command [:=> [:cat :any map?] map?])
