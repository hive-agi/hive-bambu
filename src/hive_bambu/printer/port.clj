(ns hive-bambu.printer.port
  "Printer networking ports; all operations return verdict values."
  (:refer-clojure :exclude [list send!])
  (:require [malli.core :as m]))

(defprotocol PrinterLink
  (connect! [link printer] "Claim one printer session; refuse a second owner for its serial.")
  (report [link printer] "Return a fresh observation with sparse print deltas merged.")
  (send! [link printer publication] "Publish a request; success attests only publication.")
  (close! [link printer] "Release the claimed session."))

(defprotocol FileStore
  (list [store printer path] "List an implicit-FTPS directory as values.")
  (upload! [store printer source destination] "Upload to a new path on the printer.")
  (download [store printer source] "Read bounded printer file bytes."))

(defprotocol Camera
  (snapshot [camera printer] "Read a bounded JPEG from the TLS camera."))
