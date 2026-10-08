(ns hive-bambu.printer.port
  "Async printer networking ports for Node adapters."
  (:refer-clojure :exclude [list send!]))

(defprotocol PrinterLink
  (connect! [link printer] "Claim one printer session; resolve a verdict promise.")
  (report [link printer] "Resolve a fresh merged report promise.")
  (send! [link printer publication] "Resolve a publish-only verdict promise.")
  (close! [link printer] "Release one printer session."))

(defprotocol FileStore
  (list [store printer path] "List implicit-FTPS paths.")
  (upload! [store printer source destination] "Upload a file through basic-ftp.")
  (download [store printer source] "Read bounded printer file bytes."))

(defprotocol Camera
  (snapshot [camera printer] "Read a bounded TLS JPEG frame."))
