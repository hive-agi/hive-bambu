(ns hive-bambu.printer.stub
  "In-memory printer ports with recording and selective faults."
  (:refer-clojure :exclude [list send!])
  (:require [hive-bambu.core :as core]
            [hive-bambu.printer.port :as port]
            [malli.core :as m]))

(defrecord Stub [owners reports calls files image]
  port/PrinterLink
  (connect! [_ printer]
    (let [serial (:serial printer)]
      (if (contains? @owners serial)
        (core/refusal :printer/owned "Close the existing printer connection before connecting again.")
        (do (swap! owners conj serial) {:ok {:connected true}}))))
  (report [_ printer]
    (if (contains? @owners (:serial printer))
      (if-let [observed (get @reports (:serial printer))]
        {:ok observed}
        (core/refusal :printer/no-report "Request a fresh report from the printer."))
      (core/refusal :printer/not-connected "Connect to this printer first.")))
  (send! [_ printer publication]
    (if (contains? @owners (:serial printer))
      (do (swap! calls conj [:publish (:serial printer) publication]) {:ok {:published true}})
      (core/refusal :printer/not-connected "Connect to this printer first.")))
  (close! [_ printer]
    (swap! owners disj (:serial printer))
    {:ok {:closed true}})
  port/FileStore
  (list [_ _ path] {:ok (->> @files keys (filter #(.startsWith ^String % path)) vec)})
  (upload! [_ _ source destination]
    (if (contains? @files destination)
      (core/refusal :printer/path-exists "Choose a new upload destination.")
      (do (swap! files assoc destination source) {:ok {:uploaded true}})))
  (download [_ _ source]
    (if-let [bytes (get @files source)] {:ok bytes}
        (core/refusal :printer/missing-file "Choose an existing printer path.")))
  port/Camera
  (snapshot [_ _] {:ok @image}))

(defn stub
  "Construct an isolated printer-link, file-store and camera double."
  []
  (->Stub (atom #{}) (atom {}) (atom []) (atom {}) (atom (byte-array [0xff 0xd8 0xff 0xd9]))))
(m/=> stub [:=> [:cat] :any])

(defrecord Recording [wrapped calls]
  port/PrinterLink
  (connect! [_ printer] (swap! calls conj :connect) (port/connect! wrapped printer))
  (report [_ printer] (swap! calls conj :report) (port/report wrapped printer))
  (send! [_ printer publication] (swap! calls conj :send) (port/send! wrapped printer publication))
  (close! [_ printer] (swap! calls conj :close) (port/close! wrapped printer))
  port/FileStore
  (list [_ printer path] (swap! calls conj :list) (port/list wrapped printer path))
  (upload! [_ printer source destination] (swap! calls conj :upload) (port/upload! wrapped printer source destination))
  (download [_ printer source] (swap! calls conj :download) (port/download wrapped printer source))
  port/Camera
  (snapshot [_ printer] (swap! calls conj :snapshot) (port/snapshot wrapped printer)))

(defn recording
  "Decorate all three ports and record method invocations."
  [wrapped]
  (->Recording wrapped (atom [])))
(m/=> recording [:=> [:cat :any] :any])

(defrecord Fault [wrapped fail?]
  port/PrinterLink
  (connect! [_ p] (if (fail? :connect) (core/refusal :printer/fault "Injected connection fault.") (port/connect! wrapped p)))
  (report [_ p] (if (fail? :report) (core/refusal :printer/fault "Injected report fault.") (port/report wrapped p)))
  (send! [_ p x] (if (fail? :send) (core/refusal :printer/fault "Injected publish fault.") (port/send! wrapped p x)))
  (close! [_ p] (port/close! wrapped p))
  port/FileStore
  (list [_ p path] (if (fail? :list) (core/refusal :printer/fault "Injected FTPS fault.") (port/list wrapped p path)))
  (upload! [_ p source dest] (if (fail? :upload) (core/refusal :printer/fault "Injected FTPS fault.") (port/upload! wrapped p source dest)))
  (download [_ p source] (if (fail? :download) (core/refusal :printer/fault "Injected FTPS fault.") (port/download wrapped p source)))
  port/Camera
  (snapshot [_ p] (if (fail? :snapshot) (core/refusal :printer/fault "Injected camera fault.") (port/snapshot wrapped p))))

(defn fault
  "Decorate all ports with a predicate selecting operations to fail."
  [wrapped fail?]
  (->Fault wrapped fail?))
(m/=> fault [:=> [:cat :any ifn?] :any])
