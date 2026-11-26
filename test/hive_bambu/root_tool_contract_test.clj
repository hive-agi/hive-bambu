(ns hive-bambu.root-tool-contract-test
  (:require [hive-addon.tool-contract.test :refer [deftest-root-tools]]
            [hive-addon.protocol :as proto]
            [hive-bambu.addon :as a]
            [hive-bambu.printer.gate :as gate]))

(deftest-root-tools root-tools
  (let [instance (a/addon-ctor {:print-gate (gate/guard ["M112"])})]
    (proto/initialize! instance {})
    (proto/tools instance)))
