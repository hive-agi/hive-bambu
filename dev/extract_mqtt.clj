(ns extract-mqtt
  "Extract the sendCommand vocabulary from bambu-mcp's MQTT client."
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn command-on-line [line]
  (when-let [at (str/index-of line "sendCommand(\"")]
    (let [start (+ at (count "sendCommand(\""))
          end (str/index-of line "\"" start)]
      (when end (subs line start end)))))

(let [source (slurp (str (System/getProperty "user.home") "/PP/hive/clones-ref/bambu-mcp/src/mqtt-client.ts"))
      commands (->> (str/split-lines source) (keep command-on-line) distinct sort
                    (mapv (fn [name] {:name name
                                      :family (subs name 0 (str/index-of name "."))
                                      :source "bambu-mcp/src/mqtt-client.ts"})))]
  (spit "resources/hive_bambu/mqtt.edn" (with-out-str (pp/pprint commands)))
  (println "Extracted" (count commands) "MQTT commands"))
