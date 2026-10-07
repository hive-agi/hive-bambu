(ns hive-bambu.printer.schema
  "Printer-side value objects and their Malli contracts."
  (:require [malli.core :as m]))

(def SecretRef [:map {:closed true} [:scheme [:enum :pass :env]] [:path [:string {:min 1}]]])
(def PrinterRef [:map {:closed true} [:name [:string {:min 1}]] [:host [:string {:min 1}]]
                 [:serial [:string {:min 1}]] [:access-code SecretRef]])
(def Report [:map [:observed-at :int] [:print map?]])
(def Published [:map [:published [:= true]]])
(def ErrorValue [:map [:error [:map [:kind keyword?] [:hint string?]]]])
(def Verdict [:or [:map [:ok :any]] ErrorValue])
(def PrintRequest [:map [:confirm [:= true]] [:gcode-lines [:vector :string]]])
(def TemperatureCaps [:map [:nozzle [:int {:min 0 :max 300}]] [:bed [:int {:min 0 :max 120}]]])
