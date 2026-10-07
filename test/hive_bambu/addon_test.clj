(ns hive-bambu.addon-test
  "IAddon lifecycle and injected transport behavior; no printer involved."
  (:require [clojure.test :refer [deftest is]]
            [hive-addon.protocol :as addon]
            [hive-bambu.addon :as bambu]
            [hive-bambu.stub :as stub]))

(deftest mount-and-dispatch
  (let [printer (stub/stub {:accepted true})
        instance (bambu/addon-ctor {:transport printer})]
    (is (= "hive.bambu" (addon/addon-id instance)))
    (is (:success? (addon/initialize! instance {})))
    (is (= 1 (count (addon/tools instance))))
    (is (= 57 (get-in (bambu/doctor) [:catalog :slicer])))
    (is (= 17 (get-in (bambu/doctor) [:catalog :mqtt])))
    (is (= {:ok {:accepted true}}
           (bambu/execute printer {:command "call" :name "print.pause"
                                   :serial "S1" :sequence-id "1"})))
    (is (= "device/S1/request" (:topic (first @(:received printer)))))
    (is (= :bambu/unknown-command
           (get-in (bambu/execute printer {:command "bogus"}) [:error :kind])))
    (addon/shutdown! instance)
    (is (empty? (addon/tools instance)))))

(deftest faults-and-no-transport
  (let [printer (stub/stub :ok)
        faulted (stub/fault printer (constantly true))
        args {:command "call" :name "print.pause" :serial "S1" :sequence-id "1"}]
    (is (= :bambu/no-transport (get-in (bambu/execute nil args) [:error :kind])))
    (is (= :bambu/transport-failed (get-in (bambu/execute faulted args) [:error :kind])))
    (is (empty? @(:received printer)))))
