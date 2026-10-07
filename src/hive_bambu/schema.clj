(ns hive-bambu.schema
  "JVM-side value-object schemas for portable command outcomes."
  (:require [malli.core :as m]))

(def error [:map [:error [:map [:kind keyword?] [:hint string?]]]])
(def response [:or [:map [:ok :any]] error])
(def catalog-entry [:map [:name string?] [:group {:optional true} string?]
                    [:type {:optional true} string?] [:doc {:optional true} string?]])
(def request [:map [:command string?] [:serial {:optional true} string?]
              [:sequence-id {:optional true} string?]
              [:params {:optional true} [:map-of keyword? :any]]])
(def publication [:map [:topic string?] [:payload map?]])
