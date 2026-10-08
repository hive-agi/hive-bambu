(ns hive-bambu.print-gate-config-test
  "Data print safety policy and mount regressions."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [hive-addon.protocol :as addon]
            [hive-bambu.addon :as bambu]
            [hive-bambu.printer.gate :as gate]))

(def policy {:require-confirm true :max-report-age-ms 15000 :nozzle-max-c 300 :bed-max-c 120})
(defn policy-valid? [p] (m/validate gate/GateConfig p))
(m/=> policy-valid? [:=> [:cat :any] boolean?])
(deftrifecta gate-policy-trifecta #'policy-valid?
  {:golden-path "test/golden/print-gate-policy.edn"
   :cases {:valid policy :missing (dissoc policy :require-confirm)
           :too-hot (assoc policy :nozzle-max-c 301) :extra (assoc policy :unknown 1)}
   :gen (gen/elements [policy (dissoc policy :bed-max-c) (assoc policy :bed-max-c 121)])
   :pred boolean? :num-tests 80
   :mutations [["accept-any" (fn [_] true)]]})

(deftest config-mount-and-injection
  (let [instance (bambu/addon-ctor {:print-gate policy})]
    (is (:success? (addon/initialize! instance {})))
    (is (= 2 (count (addon/tools instance))))
    (is (satisfies? gate/PrintGate (:gate @(:state instance)))))
  (let [instance (bambu/addon-ctor {:print-gate (assoc policy :nozzle-max-c 301)})]
    (is (= :printer/missing-gate (get-in (addon/initialize! instance {}) [:errors 0 :kind])))
    (is (empty? (addon/tools instance))))
  (is (:success? (addon/initialize! (bambu/addon-ctor {:print-gate (gate/guard ["M112"])}) {}))))

(deftest policy-enforced
  (let [guard (gate/config-gate (assoc policy :max-report-age-ms 2000 :nozzle-max-c 210 :bed-max-c 60))
        observation {:observed-at 1000 :print {:gcode_state "IDLE"}}
        request {:confirm true :gcode-lines ["M104 S211"]}]
    (is (= :printer/stale-report (get-in (gate/authorize! guard observation 3001 request) [:error :kind])))
    (is (= :bambu/unsafe-temperature (get-in (gate/authorize! guard observation 1001 request) [:error :kind])))
    (is (:ok (gate/authorize! guard observation 1001 (assoc request :gcode-lines ["M104 S210" "M140 S60"]))))))
