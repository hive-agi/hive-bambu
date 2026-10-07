(ns hive-bambu.slicer.settings
  "Typed stock-profile overrides written only into fresh run directories."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [hive-bambu.slicer.domain :as domain]))

(def knobs
  {:layer_height {:category :process :min 0.01 :max 1 :source-line 1098}
   :initial_layer_print_height {:category :process :min 0.01 :max 1 :source-line 3459}
   :wall_loops {:category :process :min 0 :max 100 :source-line 4759}
   :top_shell_layers {:category :process :min 0 :max 20 :source-line 6152}
   :bottom_shell_layers {:category :process :min 0 :max 20 :source-line 1432}
   :sparse_infill_density {:category :process :min 0 :max 100 :source-line 3171}
   :sparse_infill_pattern {:category :process :enum #{"concentric" "zig-zag" "grid" "line" "cubic" "triangles" "tri-hexagon" "gyroid" "honeycomb" "adaptivecubic" "alignedrectilinear" "3dhoneycomb" "hilbertcurve" "archimedeanchords" "octagramspiral" "supportcubic" "lightning" "crosshatch" "zigzag" "crosszag" "lockedzag" "2dlattice"} :source-line 3189}
   :enable_support {:category :process :type :boolean :source-line 5597}
   :support_type {:category :process :enum #{"normal(auto)" "tree(auto)" "normal(manual)" "tree(manual)"} :source-line 5604}
   :brim_type {:category :process :enum #{"no_brim" "outer_only" "inner_only" "outer_and_inner" "auto_brim" "painted"} :source-line 1775}
   :brim_width {:category :process :min 0 :max 100 :source-line 1765}
   :outer_wall_speed {:category :process :min 1 :max 1000 :source-line 2245}
   :inner_wall_speed {:category :process :min 1 :max 1000 :source-line 4748}
   :nozzle_temperature {:category :filament :min 0 :max 360 :source-line 6080}
   :bed_temperature {:category :filament :min 0 :max 150 :source-line 2825}
   :filament_type {:category :filament :enum #{"PLA" "PETG" "ABS" "ASA" "TPU" "PA" "PC"} :source-line 2825}})

(def allowed-keys
  (edn/read-string (slurp (io/resource "hive_bambu/slicer/printconfig_keys.edn"))))

(defn settings
  "Describe curated settings, their bounds and PrintConfig.cpp source lines."
  [] knobs)
(m/=> settings [:=> [:cat] map?])

(defn valid-overrides?
  "Check all overrides against curated bounds or the extracted PrintConfig key set."
  [overrides]
  (and (map? overrides)
       (every? (fn [[key value]]
                 (if (= key :raw)
                   (and (map? value) (every? (fn [[k v]]
                                              (and (string? k) (contains? allowed-keys k)
                                                   (not (str/includes? k "gcode"))
                                                   (not (str/includes? k "script"))
                                                   (or (string? v) (number? v) (boolean? v)))) value))
                   (when-let [{:keys [min max enum type]} (get knobs key)]
                     (cond enum (contains? enum value)
                           (= type :boolean) (instance? Boolean value)
                           :else (and (number? value) (<= min value max)))))) overrides)))
(m/=> valid-overrides? [:=> [:cat :any] boolean?])

(defn- encoded [k v]
  (cond
    (#{:outer_wall_speed :inner_wall_speed :nozzle_temperature :bed_temperature :filament_type} k) [(str v)]
    (number? v) (str v (when (= k :sparse_infill_density) "%"))
    (boolean? v) (if v "1" "0")
    :else v))

(defn derive-profiles!
  "Copy stock JSON into a new run directory with validated overrides and return derived paths."
  [process-path filament-path out overrides]
  (if-not (valid-overrides? overrides)
    {:error {:type :slicer/invalid-settings :overrides overrides}}
    (try
      (let [process (json/read-str (slurp process-path))
            filament (json/read-str (slurp filament-path))
            grouped (reduce-kv (fn [acc k v]
                                 (if (= k :raw)
                                   (reduce-kv (fn [a raw-key value]
                                                (update a (if (contains? filament raw-key) :filament :process) assoc raw-key value)) acc v)
                                   (update acc (get-in knobs [k :category]) assoc (name k) (encoded k v))))
                               {:process {} :filament {}} overrides)
            process-out (io/file out "derived-process.json")
            filament-out (io/file out "derived-filament.json")]
        (spit process-out (json/write-str (-> process
                                               (merge (:process grouped))
                                               (assoc "from" "User" "name" (str (get process "name") " hive-variant")))))
        (spit filament-out (json/write-str (-> filament
                                                 (merge (:filament grouped))
                                                 (assoc "from" "User" "name" (str (get filament "name") " hive-variant")))))
        {:ok {:process (.getAbsolutePath process-out) :filament (.getAbsolutePath filament-out)}})
      (catch Exception e {:error {:type :slicer/failed :message (.getMessage e)}}))))
(m/=> derive-profiles! [:=> [:cat string? string? :any map?] map?])
