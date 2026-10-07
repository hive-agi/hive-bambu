(ns hive-bambu.stub
  "Test-only injected in-memory printer transport with recording and faults."
  (:require [hive-bambu.ports :as ports]))

(defrecord Stub [received response]
  ports/PrinterTransport
  (send-request [_ publication]
    (swap! received conj publication)
    {:ok response}))

(defn stub
  "Create an isolated recording stub."
  [response]
  (->Stub (atom []) response))

(defrecord Fault [wrapped fail?]
  ports/PrinterTransport
  (send-request [_ publication]
    (if (fail? publication)
      (throw (ex-info "injected printer fault" {:topic (:topic publication)}))
      (ports/send-request wrapped publication))))

(defn fault
  "Decorate an injected transport with a selective fault predicate."
  [wrapped fail?]
  (->Fault wrapped fail?))

(defrecord Recording [wrapped received]
  ports/PrinterTransport
  (send-request [_ publication]
    (swap! received conj publication)
    (ports/send-request wrapped publication)))

(defn recording
  "Decorate a printer port and record every publication before delegation."
  [wrapped]
  (->Recording wrapped (atom [])))
