(ns hive-bambu.printer-node-test
  "Node fake transport conformance over the same printer ports."
  (:refer-clojure :exclude [list send!])
  (:require [hive-bambu.printer.cljs-adapter :as adapter]
            [hive-bambu.printer.port :as port]))

(defn ^:export exercise [printer-json]
  (let [printer (js->clj printer-json :keywordize-keys true)
        pins (atom {})
        link (adapter/link pins true)
        files (adapter/files pins true)]
    (-> (port/connect! link printer)
        (.then (fn [connected]
                 (-> (port/connect! link printer)
                     (.then (fn [duplicate]
                              (-> (port/send! link printer
                                              {:topic (str "device/" (:serial printer) "/request")
                                               :payload {:print {:command "pause" :sequence_id "1"}}})
                                  (.then (fn [published]
                                           (-> (port/report link printer)
                                               (.then (fn [report]
                                                        (-> (port/list files printer "/")
                                                            (.then (fn [listing]
                                                                     (-> (port/close! link printer)
                                                                         (.then (fn [closed]
                                                                                  #js {:connected (boolean (:ok connected))
                                                                                       :duplicate (name (get-in duplicate [:error :kind]))
                                                                                       :published (boolean (get-in published [:ok :published]))
                                                                                       :state (get-in report [:ok :print :gcode_state])
                                                                                       :listing (count (:ok listing))
                                                                                       :closed (boolean (:ok closed))}))))))))))))))))))))
