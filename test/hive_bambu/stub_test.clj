(ns hive-bambu.stub-test
  "Trifectas for test-only transport factories and fault decorators."
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-bambu.stub :as stub]
            [hive-bambu.ports :as ports]))

(deftrifecta stub-trifecta #'stub/stub
  {:golden-path "test/golden/stub.edn" :apply? true
   :cases {:accepted [:accepted]}
   :xf #(select-keys % [:response])
   :gen (gen/tuple (gen/return :accepted))
   :pred #(= :accepted (:response %)) :num-tests 80
   :mutations [["discard-response" (fn [_] (stub/->Stub (atom []) nil))]]})

(deftrifecta fault-trifecta #'stub/fault
  {:golden-path "test/golden/fault.edn" :apply? true
   :cases {:fault [(stub/stub :ok) (constantly true)]}
   :xf #(try (ports/send-request % {:topic "device/S1/request"})
             (catch Exception _ :injected-fault))
   :gen (gen/tuple (gen/return (stub/stub :ok)) (gen/return (constantly true)))
   :pred #(try (ports/send-request % {:topic "device/S1/request"}) false
               (catch Exception _ true)) :num-tests 80
   :mutations [["ignore-fault" (fn [wrapped _] (stub/->Fault wrapped (constantly false)))]]})

(deftrifecta recording-trifecta #'stub/recording
  {:golden-path "test/golden/recording.edn" :apply? true
   :cases {:wrapped [(stub/stub :ok)]}
   :xf #(do (ports/send-request % {:topic "device/S1/request"})
            (count @(:received %)))
   :gen (gen/tuple (gen/return (stub/stub :ok)))
   :pred #(= 1 (do (ports/send-request % {:topic "device/S1/request"})
                   (count @(:received %)))) :num-tests 80
   :mutations [["drop-recording" (fn [wrapped] (stub/->Recording wrapped nil))]]})
