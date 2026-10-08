(ns hive-bambu.slicer.show
  "Own exactly one launched BambuStudio GUI process per slicer tool."
  (:require [clojure.java.io :as io]
            [malli.core :as m])
  (:import [java.util.concurrent TimeUnit]
           [java.util.zip ZipFile]))

(defprotocol GuiProcess
  (launch! [gui path] "Start a GUI process and return an owned process token.")
  (close! [gui token] "Stop only the process identified by an owned token; return whether it exited."))

(defrecord FlatpakGui []
  GuiProcess
  (launch! [_ path]
    (if (nil? (System/getenv "DISPLAY"))
      {:error {:type :slicer/show-unavailable :reason "DISPLAY is unset; open a graphical session."}}
      (try
        (let [process (.start (ProcessBuilder. ^java.util.List ["flatpak" "run" "--die-with-parent" "com.bambulab.BambuStudio" path]))]
          (if (.waitFor process 1500 TimeUnit/MILLISECONDS)
            {:error {:type :slicer/show-failed :reason (str "BambuStudio exited at start: " (.exitValue process))}}
            {:ok process}))
        (catch Exception e {:error {:type :slicer/show-failed :reason (.getMessage e)}}))))
  (close! [_ token]
    (if (and (instance? Process token) (.isAlive ^Process token))
      (do (.destroy ^Process token)
          (when-not (.waitFor ^Process token 3 TimeUnit/SECONDS)
            (.destroyForcibly ^Process token))
          (if (.waitFor ^Process token 3 TimeUnit/SECONDS)
            {:ok true}
            {:error {:type :slicer/show-failed :reason "Owned GUI process did not exit"}}))
      {:ok true})))

(defn flatpak-gui
  "Build the Flatpak GUI process adapter."
  [] (->FlatpakGui))
(m/=> flatpak-gui [:=> [:cat] :any])

(defn sliced-archive?
  "Require an existing sliced 3mf with BambuStudio slice metadata."
  [path]
  (try
    (let [f (io/file path)]
      (and (.isFile f) (.endsWith (.getName f) ".3mf")
           (with-open [zip (ZipFile. f)]
             (boolean (.getEntry zip "Metadata/slice_info.config")))))
    (catch Exception _ false)))
(m/=> sliced-archive? [:=> [:cat :any] boolean?])

(defrecord Viewer [gui owned]
  java.io.Closeable
  (close [_]
    (when-let [token @owned]
      (when (:ok (close! gui token)) (reset! owned nil)))))

(defn viewer
  "Create a GUI viewer with an injectable process port and private ownership state."
  [gui]
  (->Viewer gui (atom nil)))
(m/=> viewer [:=> [:cat :any] :any])

(defn show!
  "Close the previously owned GUI and show a sliced 3mf; never touch unowned processes."
  [viewer path]
  (if-not (sliced-archive? path)
    {:error {:type :slicer/invalid-archive :path (str path)}}
    (locking (:owned viewer)
      (let [old @(:owned viewer)
            closed (if old (close! (:gui viewer) old) {:ok true})]
        (if (:error closed)
          closed
          (do (reset! (:owned viewer) nil)
              (let [started (launch! (:gui viewer) (.getCanonicalPath (io/file path)))]
                (if-let [token (:ok started)]
                  (do (reset! (:owned viewer) token)
                      {:ok {:path (.getCanonicalPath (io/file path)) :pid (if (instance? Process token) (.pid ^Process token) nil)}})
                  started))))))))
(m/=> show! [:=> [:cat :any :any] map?])
