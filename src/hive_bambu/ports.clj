(ns hive-bambu.ports
  "Transport port. Implementations supply their own MQTT signing and serialization."
  (:require [hive-bambu.core :as core]))

(defprotocol PrinterTransport
  (send-request [this publication]
    "Send a validated {:topic :payload} publication; answer a result value."))

(defn dispatch
  "Validate command and hand only approved publications to an injected port."
  [transport commands blocked serial sequence-id command params]
  (let [prepared (core/mqtt-request commands blocked serial sequence-id command params)]
    (cond
      (:error prepared) prepared
      (nil? transport) (core/refusal :bambu/no-transport
                                    "No printer transport configured. Supply a PrinterTransport adapter; wave one ships only a test stub.")
      :else (try (send-request transport (:ok prepared))
                 (catch Exception e
                   (core/refusal :bambu/transport-failed
                                 (str "Transport failed; check printer connectivity and credentials: " (ex-message e))))))))
