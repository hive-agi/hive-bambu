(ns hive-bambu.printer.cljs-adapter
  "Node printer adapter over npm MQTT, basic-ftp and TLS."
  (:refer-clojure :exclude [list send!])
  (:require [cljs.core.async :refer [promise-chan put!]]
            [hive-bambu.core :as core]
            [hive-bambu.printer.port :as port]
            [hive-bambu.printer.promote :as promote]
            [hive-bambu.printer.schema :as schema]
            [malli.core :as m]
            ["mqtt" :as mqtt]
            ["basic-ftp" :as ftp]
            ["node:tls" :as tls]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]))

(defn- secret [ref]
  (case (:scheme ref)
    :env (aget (.-env js/process) (:path ref))
    :pass (.trim (fs/readFileSync (:path ref) "utf8"))
    nil))

(defn- error [kind hint]
  {:error {:kind kind :hint hint}})

(defn- fingerprint [cert]
  (.digest (.update (crypto/createHash "sha256") (.-raw cert)) "hex"))

(defn- verify-cert [pins serial insecure? cert]
  (let [actual (fingerprint cert)
        pinned (get @pins serial)]
    (if (or insecure? (nil? pinned) (= pinned actual))
      (do (when (and (not insecure?) (nil? pinned)) (swap! pins assoc serial actual)) true)
      false)))

(defn- tls-options [printer pins insecure? port]
  #js {:host (:host printer) :port port :rejectUnauthorized false
       :checkServerIdentity (fn [_ cert]
                              (when-not (verify-cert pins (:serial printer) insecure? cert)
                                (js/Error. "TLS certificate pin mismatch; inspect the printer certificate before reconnecting.")))})

(defn- mqtt-options [printer pins insecure?]
  (let [code (secret (:access-code printer))]
    (when (seq code)
      #js {:username "bblp" :password code :clientId (str "hive-bambu-" (:serial printer))
           :protocol "mqtts" :reconnectPeriod 0 :connectTimeout 10000
           :rejectUnauthorized false
           :checkServerIdentity (fn [_ cert]
                                  (when-not (verify-cert pins (:serial printer) insecure? cert)
                                    (js/Error. "TLS certificate pin mismatch.")))})))

(defrecord CljsLink [sessions pins insecure? port]
  port/PrinterLink
  (connect! [_ printer]
    (if-not (m/validate schema/PrinterRef printer)
      (js/Promise.resolve (error :printer/invalid-ref "Supply a PrinterRef carrying a secret reference."))
      (if (contains? @sessions (:serial printer))
        (js/Promise.resolve (error :printer/owned "Close the existing printer connection first."))
        (if-let [opts (mqtt-options printer pins insecure?)]
          (js/Promise.
           (fn [resolve _]
             (let [serial (:serial printer)
                   client (.connect mqtt (str "mqtts://" (:host printer) ":" port) opts)
                   done (atom false)
                   timer (js/setTimeout (fn [] (.end client true) (swap! sessions dissoc serial)
                                             (when (compare-and-set! done false true)
                                               (resolve (error :printer/timeout "MQTT connect timed out.")))) 12000)
                   finish (fn [result] (when (compare-and-set! done false true)
                                         (js/clearTimeout timer) (resolve result)))]
               (swap! sessions assoc serial {:client client :report nil})
               (.on client "message"
                    (fn [topic bytes]
                      (when (= topic (str "device/" serial "/report"))
                        (try
                          (let [delta (js->clj (js/JSON.parse (.toString bytes)) :keywordize-keys true)
                                old (get-in @sessions [serial :report])
                                merged (promote/merge-report old delta (.now js/Date))]
                            (when (:ok merged) (swap! sessions assoc-in [serial :report] (:ok merged))))
                          (catch :default _ nil)))))
               (.once client "connect"
                      (fn [] (.subscribe client (str "device/" serial "/report")
                                        (fn [err] (if err (do (.end client true) (swap! sessions dissoc serial)
                                                             (finish (error :printer/subscribe-failed "MQTT report subscription failed.")))
                                                      (finish {:ok {:connected true}}))))))
               (.once client "error"
                      (fn [_] (.end client true) (swap! sessions dissoc serial)
                        (finish (error :printer/connect-failed "Check MQTT TLS pin, host and credentials.")))))))
          (js/Promise.resolve (error :printer/missing-secret "Provide a readable access-code secret reference."))))))
  (report [_ printer]
    (let [session (get @sessions (:serial printer))]
      (if-not session
        (js/Promise.resolve (error :printer/not-connected "Connect first."))
        (do (.publish (:client session) (str "device/" (:serial printer) "/request")
                      (js/JSON.stringify (clj->js {:pushing {:sequence_id (str (.now js/Date)) :command "pushall"}})))
            (js/Promise. (fn [resolve _]
                           (let [start (.now js/Date)]
                             (letfn [(poll [] (let [observation (get-in @sessions [(:serial printer) :report])]
                                                (cond
                                                  (and observation (>= (:observed-at observation) start)) (resolve {:ok observation})
                                                  (> (- (.now js/Date) start) 10000) (resolve (error :printer/timeout "Fresh MQTT report timed out."))
                                                  :else (js/setTimeout poll 25))))]
                               (poll)))))))))
  (send! [_ printer publication]
    (if-let [client (get-in @sessions [(:serial printer) :client])]
      (js/Promise. (fn [resolve _]
                     (.publish client (:topic publication) (js/JSON.stringify (clj->js (:payload publication)))
                               #js {:qos 0} (fn [err] (resolve (if err (error :printer/publish-failed "MQTT publication failed.")
                                                                   {:ok {:published true}}))))))
      (js/Promise.resolve (error :printer/not-connected "Connect first."))))
  (close! [_ printer]
    (when-let [client (get-in @sessions [(:serial printer) :client])]
      (.end client true) (swap! sessions dissoc (:serial printer)))
    (js/Promise.resolve {:ok {:closed true}})))

(defn link
  "Construct a single-owner MQTT adapter with per-serial TLS certificate pins. Optional port is for fake-server conformance."
  ([pins insecure?] (link pins insecure? 8883))
  ([pins insecure? port] (->CljsLink (atom {}) pins insecure? port)))
(m/=> link [:=> [:cat :any boolean?] :any])

(defn- with-ftp [printer pins insecure? port action]
  (if-let [code (secret (:access-code printer))]
    (let [client (ftp/Client. 10000)]
      (-> (.access client #js {:host (:host printer) :port port :user "bblp" :password code
                               :secure "implicit" :secureOptions (tls-options printer pins insecure? port)})
          (.then (fn [] (action client)))
          (.then (fn [result] (if (:error result) result {:ok result})))
          (.catch (fn [_] (error :printer/ftps-failed "Check implicit FTPS 990, TLS pin and basic-ftp compatibility; no curl fallback.")))
          (.finally (fn [] (.close client)))))
    (js/Promise.resolve (error :printer/missing-secret "Provide a readable access-code secret reference."))))

(defrecord CljsFiles [pins insecure? port]
  port/FileStore
  (list [_ printer path]
    (with-ftp printer pins insecure? port (fn [client] (.list client path))))
  (upload! [_ printer source destination]
    (with-ftp printer pins insecure? port
      (fn [client]
        (-> (.size client destination)
            (.then (fn [_] {:error {:kind :printer/path-exists :hint "Choose a new upload destination."}}))
            (.catch (fn [err]
                      (if (= 550 (.-code err))
                        (-> (.uploadFrom client source destination) (.then (fn [_] {:uploaded true})))
                        (js/Promise.reject err))))))))
  (download [_ printer source]
    (with-ftp printer pins insecure? port
      (fn [client]
        (let [chunks (atom [])
              total (atom 0)
              sink ((.-Writable (js/require "stream")) #js {:write (fn [chunk _ callback]
                                                               (swap! total + (.-length chunk))
                                                               (if (> @total 10485760)
                                                                 (callback (js/Error. "Download exceeds 10 MiB cap"))
                                                                 (do (swap! chunks conj chunk) (callback))))})]
          (-> (.downloadTo client sink source)
              (.then (fn [_] (js/Buffer.concat (clj->js @chunks) @total)))))))))

(defn files
  "Construct a basic-ftp implicit-FTPS adapter; failures never invoke curl. Optional port is for fake-server conformance."
  ([pins insecure?] (files pins insecure? 990))
  ([pins insecure? port] (->CljsFiles pins insecure? port)))
(m/=> files [:=> [:cat :any boolean?] :any])

(defrecord CljsCamera [pins insecure? max-bytes port]
  port/Camera
  (snapshot [_ printer]
    (if-let [code (secret (:access-code printer))]
      (js/Promise.
       (fn [resolve _]
         (let [socket (tls/connect (tls-options printer pins insecure? port))
               done (atom false)
               finish (fn [value] (when (compare-and-set! done false true)
                                    (.destroy socket) (resolve value)))
               header (js/Buffer.alloc 16)
               header-read (atom 0)
               frame (atom nil)
               frame-read (atom 0)]
           (.setTimeout socket 10000)
           (.on socket "timeout" (fn [] (finish (error :printer/timeout "Camera TLS frame timed out."))))
           (.on socket "error" (fn [_] (finish (error :printer/camera-failed "Check camera TLS pin and port 6000."))))
           (.on socket "secureConnect"
                (fn [] (let [auth (js/Buffer.alloc 80)]
                         (.writeUInt32LE auth 0x40 0) (.writeUInt32LE auth 0x3000 4)
                         (.write auth "bblp" 16 "ascii") (.write auth code 48 "ascii")
                         (.write socket auth))))
           (.on socket "data"
                (fn [part]
                  (loop [offset 0]
                    (when (< offset (.-length part))
                      (if-not @frame
                        (let [n (min (- 16 @header-read) (- (.-length part) offset))]
                          (.copy part header @header-read offset (+ offset n))
                          (swap! header-read + n)
                          (when (= 16 @header-read)
                            (let [length (+ (.readUInt8 header 12) (bit-shift-left (.readUInt8 header 13) 8)
                                            (bit-shift-left (.readUInt8 header 14) 16))]
                              (if (or (< length 4) (> length max-bytes))
                                (finish (error :printer/camera-size "Camera JPEG advertised length exceeds the configured cap."))
                                (reset! frame (js/Buffer.alloc length)))))
                          (when-not @done (recur (+ offset n))))
                        (let [n (min (- (.-length @frame) @frame-read) (- (.-length part) offset))]
                          (.copy part @frame @frame-read offset (+ offset n))
                          (swap! frame-read + n)
                          (if (= @frame-read (.-length @frame))
                            (finish (if (and (= 255 (.readUInt8 @frame 0)) (= 216 (.readUInt8 @frame 1))
                                             (= 255 (.readUInt8 @frame (- @frame-read 2))) (= 217 (.readUInt8 @frame (dec @frame-read))))
                                      {:ok @frame}
                                      (error :printer/invalid-jpeg "Camera frame is not a JPEG.")))
                            (recur (+ offset n)))))))))))
      (js/Promise.resolve (error :printer/missing-secret "Provide a readable access-code secret reference."))))))

(defn camera
  "Construct a camera adapter with an explicit maximum JPEG byte length."
  ([pins insecure? max-bytes] (camera pins insecure? max-bytes 6000))
  ([pins insecure? max-bytes port]
   (when (and (integer? max-bytes) (<= 4 max-bytes 10485760))
     (->CljsCamera pins insecure? max-bytes port))))
(m/=> camera [:=> [:cat :any boolean? :int] [:maybe :any]])
