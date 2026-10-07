(ns hive-bambu.addon-test
  "IAddon lifecycle and injected transport behavior; no printer involved."
  (:require [clojure.test :refer [deftest is]]
            [hive-addon.protocol :as addon]
            [hive-bambu.addon :as bambu]
            [hive-bambu.stub :as stub]
            [hive-test.trifecta :refer [deftrifecta]]
            [clojure.test.check.generators :as gen]
            [hive-bambu.printer.gate :as gate]))

(deftest mount-and-dispatch
  (let [printer (stub/stub {:accepted true})
        instance (bambu/addon-ctor {:transport printer :print-gate (gate/guard ["M112"])})]
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

(deftest refuses-mount-without-print-gate
  (let [instance (bambu/addon-ctor {})]
    (is (= :printer/missing-gate
           (get-in (addon/initialize! instance {}) [:errors 0 :kind])))
    (is (empty? (addon/tools instance)))))

(deftest direct-project-file-is-gated
  (let [printer (stub/stub :ok)]
    (is (= :printer/missing-gate
           (get-in (bambu/execute printer {:command "call" :name "print.project_file"
                                           :serial "S1" :sequence-id "1"}) [:error :kind])))
    (is (empty? @(:received printer)))))

(deftest faults-and-no-transport
  (let [printer (stub/stub :ok)
        faulted (stub/fault printer (constantly true))
        args {:command "call" :name "print.pause" :serial "S1" :sequence-id "1"}]
    (is (= :bambu/no-transport (get-in (bambu/execute nil args) [:error :kind])))
    (is (= :bambu/transport-failed (get-in (bambu/execute faulted args) [:error :kind])))
    (is (empty? @(:received printer)))))

(def recording-printer (stub/recording (stub/stub {:accepted true})))
(def handler (:handler (bambu/tool recording-printer)))

(deftrifecta handler-trifecta #'handler
  {:golden-path "test/golden/handler.edn" :apply? true
   :cases {:unknown [{"command" "bogus"}]
           :call [{"command" "call" "name" "print.pause" "serial" "S1" "sequence-id" "7"}]}
   :gen (gen/tuple (gen/return {"command" "bogus"}))
   :pred #(and (:isError %) (= "text" (get-in % [:content 0 :type])))
   :num-tests 80
   :mutations [["accept-any-command" (fn [_] {:isError false :content [{:type "text" :text "ok"}]})]]})

(deftest handler-records-publication
  (reset! (:received recording-printer) [])
  (handler {"command" "call" "name" "print.pause" "serial" "S1" "sequence-id" "7"})
  (is (= "device/S1/request" (:topic (first @(:received recording-printer))))))
