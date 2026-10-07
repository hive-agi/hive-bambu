(ns hive-bambu.slicer.flatpak
  "Bounded Flatpak CLI transport with isolated per-slice outputs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [hive-bambu.slicer.domain :as domain]
            [hive-bambu.slicer.settings :as settings]
            [hive-bambu.slicer.port :as port])
  (:import [java.io File]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]
           [java.util.zip ZipFile]))

(def app-id "com.bambulab.BambuStudio")
(def install-command "flatpak install flathub com.bambulab.BambuStudio")

(defn- run-process [args timeout-ms]
  (try
    (let [p (.start (doto (ProcessBuilder. ^java.util.List args) (.redirectErrorStream true)))
          _ (.close (.getOutputStream p))
          output (future (with-open [r (.getInputStream p)]
                           (let [buf (byte-array 4096) out (java.io.ByteArrayOutputStream.)]
                             (loop [] (let [n (.read r buf)]
                                        (when (pos? n)
                                          (when (< (.size out) 65536)
                                            (.write out buf 0 (min n (- 65536 (.size out)))))
                                          (recur))))
                             (.toString out "UTF-8"))))
          done (.waitFor p timeout-ms TimeUnit/MILLISECONDS)]
      (when-not done (.destroyForcibly p) (.waitFor p 2 TimeUnit/SECONDS))
      {:exit (when done (.exitValue p)) :timeout? (not done) :stderr (subs (deref output 2000 "") 0 (min 4096 (count (deref output 2000 ""))))})
    (catch Exception e {:exit -1 :stderr (.getMessage e)})))

(defn availability
  "Probe the installed Flatpak by exit status without running its GUI."
  []
  (let [result (run-process ["flatpak" "info" app-id] 10000)]
    (if (zero? (:exit result)) {:ok true}
        {:error {:type :slicer/unavailable :install install-command :detail (:stderr result)}})))
(m/=> availability [:=> [:cat] map?])

(defn- profile-root []
  (let [location (run-process ["flatpak" "info" "--show-location" app-id] 10000)]
    (when (zero? (:exit location))
      (io/file (str/trim (:stderr location)) "files" "share" "BambuStudio" "profiles" "BBL"))))

(defn presets
  "List the installed stock BBL printer, process and filament JSON profiles."
  []
  (if-let [root (profile-root)]
    {:ok (into {} (for [kind ["machine" "process" "filament"]]
                    [(keyword kind) (->> (file-seq (io/file root kind))
                                         (filter #(.isFile ^File %))
                                         (map #(.getName ^File %))
                                         (filter #(str/ends-with? % ".json"))
                                         sort vec)]))}
    (availability)))
(m/=> presets [:=> [:cat] map?])

(defn- estimate [archive]
  (try
    (with-open [zip (ZipFile. ^File archive)]
      (let [entry (.getEntry zip "Metadata/slice_info.config")
            text (when entry (with-open [stream (.getInputStream zip entry)] (slurp stream)))
            metadata (when text (re-seq #"<metadata key=\"([^\"]+)\" value=\"([^\"]*)\"" text))
            filament (when text (re-find #"<filament [^>]*used_m=\"([^\"]*)\" used_g=\"([^\"]*)\"" text))
            attrs (into {} (map (fn [[_ k v]] [k v]) metadata))
            number (fn [v] (when (and v (not (empty? v))) (Double/parseDouble v)))]
        {:print-seconds (number (get attrs "prediction"))
         :filament-g (number (nth filament 2 nil))
         :filament-m (number (nth filament 1 nil))}))
    (catch Exception _ {:print-seconds nil :filament-g nil :filament-m nil})))

(defn- profile-path [root kind name]
  (let [file (io/file root kind name)]
    (when (and (.isFile file) (= (.getCanonicalFile (.getParentFile file)) (.getCanonicalFile (io/file root kind))))
      (.getAbsolutePath file))))

(defrecord FlatpakCliSlicer [output-root timeout-ms]
  port/Slicer
  (slice! [_ request]
    (cond
      (not (domain/valid-request? request)) {:error {:type :slicer/invalid-model}}
      (:error (availability)) (availability)
      (not (settings/valid-overrides? (or (:overrides request) {}))) {:error {:type :slicer/invalid-settings}}
      :else
      (let [root (profile-root)
            {:keys [printer process filament plate]} (:preset request)
            names [printer process filament]
            host-paths (mapv #(profile-path root %1 %2) ["machine" "process" "filament"] names)
            container-paths (mapv #(str "/app/share/BambuStudio/profiles/BBL/" %1 "/" %2)
                                  ["machine" "process" "filament"] names)]
        (if (some nil? host-paths)
          {:error {:type :slicer/failed :message "Stock profile not found" :preset (:preset request)}}
          (let [out (io/file output-root (str (java.util.UUID/randomUUID)))
                _ (Files/createDirectories (.toPath out) (make-array java.nio.file.attribute.FileAttribute 0))
                archive (io/file out "output.gcode.3mf")
                derived (when (seq (:overrides request))
                          (settings/derive-profiles! (second host-paths) (last host-paths) out (:overrides request)))
                setting-paths (if derived
                                [(first container-paths) (get-in derived [:ok :process]) (get-in derived [:ok :filament])]
                                container-paths)
                args ["flatpak" "run" "--command=bambu-studio" app-id
                      (str "--load-settings=" (str/join ";" (take 2 setting-paths)))
                      (str "--load-filaments=" (last setting-paths))
                      (str "--outputdir=" (.getAbsolutePath out))
                      (str "--slice=" (or plate 0))
                      "--export-3mf=output.gcode.3mf"
                      (get-in request [:model :path])]
                start (System/nanoTime)
                result (when-not (:error derived) (run-process args timeout-ms))]
            (cond
              (:error derived) derived
              (:timeout? result) {:error {:type :slicer/timeout :output-dir (.getAbsolutePath out)}}
              (not (zero? (:exit result))) {:error {:type :slicer/failed :exit (:exit result) :stderr (:stderr result) :output-dir (.getAbsolutePath out)}}
              (not (.isFile archive)) {:error {:type :slicer/failed :message "CLI exited successfully but archive is absent" :output-dir (.getAbsolutePath out)}}
              :else {:ok {:outputs [{:path (.getAbsolutePath archive) :format :gcode-3mf :bytes (.length archive)}]
                         :estimate (estimate archive) :warnings [] :wall-ms (long (/ (- (System/nanoTime) start) 1000000))}})))))))

(defn adapter
  "Construct the Flatpak adapter with an output root and finite timeout."
  [output-root timeout-ms]
  (->FlatpakCliSlicer output-root timeout-ms))
(m/=> adapter [:=> [:cat string? pos-int?] :any])
