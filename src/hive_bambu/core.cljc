(ns hive-bambu.core
  "Portable, effect-free Bambu command vocabulary and wire values.

   Catalog rows are supplied as values by the host, never loaded in this core.
   Every public operation returns {:ok value} or {:error {:kind ... :hint ...}}."
  (:require [clojure.string :as str]))

(defn refusal
  "Make an actionable refusal value."
  [kind hint]
  {:error {:kind kind :hint hint}})

(defn lookup
  "Look up an exact command name in extracted descriptor values."
  [rows name]
  (if-let [row (first (filter #(= name (:name %)) rows))]
    {:ok row}
    (refusal :bambu/unknown-command
             (str "Unknown command " (pr-str name) ". Run bambu catalog to see supported names."))))

(defn slice-argv
  "Build slicer argv; arguments remain distinct to avoid shell interpretation.
   The plate number is zero (all plates) or a positive integer. No binary runs."
  [binary input output-dir plate]
  (cond
    (or (not (string? binary)) (str/blank? binary))
    (refusal :bambu/missing-binary "Provide the BambuStudio CLI binary path; build BambuStudio first.")
    (or (not (string? input)) (str/blank? input))
    (refusal :bambu/missing-input "Provide a nonempty input .3mf path.")
    (not (str/ends-with? (str/lower-case input) ".3mf"))
    (refusal :bambu/invalid-input "Slice an existing .3mf project, not another file type.")
    (or (not (string? output-dir)) (str/blank? output-dir))
    (refusal :bambu/missing-output "Provide an output directory for the sliced result.")
    (or (not (integer? plate)) (neg? plate))
    (refusal :bambu/invalid-plate "Plate must be 0 (all) or a positive integer.")
    :else {:ok [binary "--slice" (str plate) "--outputdir" output-dir input]}))

(defn topic
  "Build a printer request or report topic; no MQTT wildcard or separator in a serial."
  [serial direction]
  (cond
    (or (not (string? serial)) (str/blank? serial)
        (not (every? #(or (<= (int \0) (int %) (int \9))
                          (<= (int \A) (int %) (int \Z))
                          (<= (int \a) (int %) (int \z))
                          (= % \-)) serial)))
    (refusal :bambu/invalid-serial "Use a nonempty alphanumeric serial (hyphens allowed); MQTT wildcards and separators are forbidden.")
    (not (contains? #{"request" "report"} direction))
    (refusal :bambu/invalid-direction "Direction must be request or report.")
    :else {:ok (str "device/" serial "/" direction)}))

(defn gcode-check
  "Refuse blocked commands and excessive temperature settings.
   Safety tokens are supplied from extracted data."
  [blocked line]
  (let [parts (when (string? line) (str/split (str/upper-case (str/trim line)) #"\s+"))
        opcode (first parts)
        param (second parts)
        temp? (contains? #{"M104" "M109" "M140"} opcode)
        digits (when (and (string? param) (str/starts-with? param "S")) (subs param 1))
        number (when (and (seq digits) (< (count digits) 8)
                          (every? #(<= (int \0) (int %) (int \9)) digits))
                 (reduce (fn [n ch] (+ (* n 10) (- (int ch) (int \0)))) 0 digits))]
    (cond
      (or (not (string? line)) (str/blank? line) (str/includes? line "\n")
          (str/includes? line "\r") (str/includes? line ";"))
      (refusal :bambu/invalid-gcode "Provide one nonempty G-code line, without comments or line breaks.")
      (some #{opcode} blocked)
      (refusal :bambu/blocked-gcode (str opcode " is blocked for safety; use the dedicated printer control instead."))
      (and temp? (nil? number))
      (refusal :bambu/invalid-temperature "Temperature commands require an integer S parameter (at most 7 digits).")
      (and temp? (> number (if (= opcode "M140") 120 300)))
      (refusal :bambu/unsafe-temperature "Temperature exceeds the nozzle (300 C) or bed (120 C) limit.")
      :else {:ok (str/trim line)})))

(defn mqtt-request
  "Build a data-only MQTT publication. Transport handles JSON and signing.
   Extra parameters are limited to scalar, keyword-keyed fields."
  [commands blocked serial sequence-id command params]
  (let [route (topic serial "request")
        known (lookup commands command)
        [family operation] (when (string? command) (str/split command #"\."))
        param (or params {})]
    (cond
      (:error route) route
      (:error known) known
      (or (not (string? sequence-id)) (str/blank? sequence-id))
      (refusal :bambu/invalid-sequence "Supply a nonempty sequence ID for report matching.")
      (or (not (map? param))
          (not (every? keyword? (keys param)))
          (not (every? #(or (string? %) (number? %) (boolean? %)) (vals param)))
          (some #{:command :sequence_id} (keys param)))
      (refusal :bambu/invalid-params "Supply scalar keyword-keyed params, without command or sequence_id overrides.")
      (and (= command "print.gcode_line") (:error (gcode-check blocked (:param param))))
      (gcode-check blocked (:param param))
      (and (= command "print.print_speed")
           (or (not (integer? (:param param))) (not (<= 1 (:param param) 166))))
      (refusal :bambu/invalid-speed "Set speed to an integer percentage from 1 to 166.")
      :else {:ok {:topic (:ok route)
                  :payload {(keyword family) (merge {:sequence_id sequence-id :command operation} param)}}})))

(defn report
  "Project a decoded report map to the matching printer's report value."
  [serial received-topic decoded]
  (let [route (topic serial "report")]
    (cond
      (:error route) route
      (not= (:ok route) received-topic)
      (refusal :bambu/unexpected-topic "Subscribe to device/<serial>/report for the intended serial.")
      (not (map? decoded))
      (refusal :bambu/invalid-report "Decode the JSON report into a map before parsing.")
      :else {:ok {:print (or (:print decoded) (:mc_print decoded))
                  :sequence-id (or (get-in decoded [:print :sequence_id])
                                   (get-in decoded [:mc_print :sequence_id]))}})))

(defn plate-model
  "Read a narrow 3MF model/plate view from already-unzipped XML text.
   Refuses DTD/entities and unsupported markup instead of accepting unsafe XML."
  [xml]
  (cond
    (not (string? xml))
    (refusal :bambu/invalid-xml "Supply already-unzipped 3MF XML text.")
    (or (str/includes? (str/upper-case xml) "<!DOCTYPE")
        (str/includes? (str/upper-case xml) "<!ENTITY")
        (str/includes? xml "&") )
    (refusal :bambu/unsafe-xml "3MF XML must not contain entities or a DTD; unzip and sanitize first.")
    (or (not (str/includes? xml "<model"))
        (not (str/includes? xml "</model>")))
    (refusal :bambu/invalid-xml "Expected a complete <model>...</model> document.")
    :else (let [tags (loop [remaining xml acc []]
                       (if-let [i (str/index-of remaining "<object ")]
                         (if-let [j (str/index-of remaining ">" i)]
                           (recur (subs remaining (inc j)) (conj acc (subs remaining i (inc j))))
                           acc)
                         acc))
                ids (mapv (fn [tag]
                            (let [i (str/index-of tag "id=\"")]
                              (when i (let [start (+ i 4) end (str/index-of tag "\"" start)]
                                        (when end (subs tag start end)))))) tags)]
            (if (some nil? ids)
              (refusal :bambu/invalid-xml "Every 3MF object must have a quoted id attribute.")
              {:ok {:object-ids ids :object-count (count ids)}}))))
