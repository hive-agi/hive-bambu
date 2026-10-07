(ns hive-bambu.printer-node-test
  "Node fake transport conformance over the same printer ports."
  (:refer-clojure :exclude [list send!])
  (:require [hive-bambu.printer.cljs-adapter :as adapter]
            [hive-bambu.printer.port :as port]))

(defn ^:export exercise [printer-json mqtt-port ftp-port]
  (let [printer (update-in (js->clj printer-json :keywordize-keys true) [:access-code :scheme] keyword)
        pins (atom {})
        link (adapter/link pins true mqtt-port)
        files (adapter/files pins true ftp-port)
        topic (str "device/" (:serial printer) "/request")
        results (atom {})
        capture (fn [key value] (swap! results assoc key value))]
    (-> (port/connect! link printer)
        (.then (fn [connected]
                 (capture :connected (boolean (:ok connected)))
                 (port/connect! link printer)))
        (.then (fn [duplicate]
                 (capture :duplicate (str (get-in duplicate [:error :kind])))
                 (port/send! link printer {:topic topic :payload {:print {:command "pause" :sequence_id "1"}}})))
        (.then (fn [published]
                 (capture :published (boolean (get-in published [:ok :published])))
                 (port/report link printer)))
        (.then (fn [report]
                 (capture :state (get-in report [:ok :print :gcode_state]))
                 (port/list files printer "/")))
        (.then (fn [listing]
                 (capture :listing (count (:ok listing)))
                 (capture :listing-error (str (get-in listing [:error :kind])))
                 (port/download files printer "/existing.gcode")))
        (.then (fn [downloaded]
                 (capture :download (when-let [bytes (:ok downloaded)] (.toString bytes)))
                 (capture :download-error (str (get-in downloaded [:error :kind])))
                 (port/upload! files printer (.from (.-Readable (js/require "node:stream")) (clj->js ["new-gcode\n"])) "/new.gcode")))
        (.then (fn [uploaded]
                 (capture :uploaded (boolean (get-in uploaded [:ok :uploaded])))
                 (port/upload! files printer (.from (.-Readable (js/require "node:stream")) (clj->js ["overwritten\n"])) "/existing.gcode")))
        (.then (fn [existing]
                 (capture :upload-existing (str (get-in existing [:error :kind])))
                 (port/close! link printer)))
        (.then (fn [closed]
                 (capture :closed (boolean (:ok closed)))
                 (clj->js @results))))))
