(ns hive-bambu.printer.conformance-test
  "Port conformance over injected printer implementations."
  (:refer-clojure :exclude [list send!])
  (:require [clojure.test :refer [deftest is testing]]
            [hive-bambu.printer.port :as port]
            [hive-bambu.printer.stub :as stub]
            [hive-bambu.printer.gate :as gate]
            [hive-bambu.printer.promote :as promote]
            [hive-bambu.printer.schema :as schema]
            [malli.core :as m]))

(def printer {:name "desk" :host "127.0.0.1" :serial "ABC1"
              :access-code {:scheme :env :path "BAMBU_ACCESS_CODE"}})
(def idle {:observed-at 1000 :print {:gcode_state "IDLE" :print_error 0 :hms []}})
(def blocked ["M112" "M502" "M500" "M501" "M997" "M999"])

(defn exercise
  "Run the same port assertions against a factory of isolated adapters."
  [factory]
  (let [raw (factory)
        link (stub/recording raw)]
    (is (m/validate schema/PrinterRef printer))
    (is (= {:ok {:connected true}} (port/connect! link printer)))
    (is (= :printer/owned (get-in (port/connect! link printer) [:error :kind])))
    (is (= {:ok {:published true}} (port/send! link printer {:topic "device/ABC1/request"})))
    (is (= {:ok {:closed true}} (port/close! link printer)))
    (is (= :printer/not-connected (get-in (port/send! link printer {}) [:error :kind])))
    (is (= [:connect :connect :send :close :send] @(:calls link)))
    (is (= {:ok {:uploaded true}} (port/upload! link printer [1 2 3] "new.gcode")))
    (is (= :printer/path-exists (get-in (port/upload! link printer [] "new.gcode") [:error :kind])))
    (is (= {:ok [1 2 3]} (port/download link printer "new.gcode")))
    (is (= {:ok ["new.gcode"]} (port/list link printer "new")))
    (is (= 4 (alength ^bytes (:ok (port/snapshot link printer)))))))

(deftest stub-port-conformance
  (exercise stub/stub))

(deftest print-gate-conformance
  (let [s (stub/stub)
        recording (stub/recording s)
        guard (gate/guard blocked)
        req {:confirm true :gcode-lines ["G28" "M104 S220"]}]
    (is (= {:ok {:connected true}} (port/connect! recording printer)))
    (swap! (:reports s) assoc "ABC1" idle)
    (is (= {:ok {:published true}} (gate/print! guard recording printer 1001 req {:print :job})))
    (is (= 1 (count @(:calls s))))
    (doseq [[request expected] [[(assoc req :confirm false) :printer/confirmation-required]
                                [(assoc req :gcode-lines ["M112"]) :bambu/blocked-gcode]
                                [(assoc req :gcode-lines ["M140 S121"]) :bambu/unsafe-temperature]
                                [(assoc req :gcode-lines ["G28" "M5020"]) :bambu/blocked-gcode]
                                [(assoc req :gcode-lines []) :printer/missing-gcode]]]
      (is (= expected (get-in (gate/print! guard recording printer 1001 request {}) [:error :kind]))))
    (is (= :printer/stale-report (get-in (gate/print! guard recording printer 16001 req {}) [:error :kind])))
    (swap! (:reports s) assoc "ABC1" (assoc-in idle [:print :hms] [{:code "fault"}]))
    (is (= :printer/fault (get-in (gate/print! guard recording printer 1001 req {}) [:error :kind])))
    (is (= 1 (count @(:calls s))))
    (is (= :printer/missing-gate (get-in (gate/print! nil recording printer 1001 req {}) [:error :kind])))))

(deftest sparse-report-conformance
  (let [old {:observed-at 1 :print {:gcode_state "IDLE" :mc_percent 4}}
        result (promote/merge-report old {:mc_print {:mc_percent 5}} 2)]
    (is (= {:ok {:observed-at 2 :print {:gcode_state "IDLE" :mc_percent 5}}} result))
    (is (= :printer/invalid-report (get-in (promote/merge-report old {} 2) [:error :kind])))))

(deftest fault-decorator-conformance
  (let [s (stub/fault (stub/stub) #{:connect :snapshot})]
    (is (= :printer/fault (get-in (port/connect! s printer) [:error :kind])))
    (is (= :printer/fault (get-in (port/snapshot s printer) [:error :kind])))))
