(ns hive-bambu.printer.promote
  "Pure promotion of printer observations and print authorization."
  (:require [hive-bambu.core :as core]))

(defn merge-report
  "Merge a sparse print or mc_print delta into an observed report."
  [previous decoded observed-at]
  (let [delta (or (:print decoded) (:mc_print decoded))]
    (if (and (map? decoded) (map? delta) (integer? observed-at))
      {:ok {:observed-at observed-at :print (merge (:print previous) delta)}}
      (core/refusal :printer/invalid-report "Provide a decoded print or mc_print map and observation time."))))

(defn authorize-print
  "Authorize a confirmed print only for a fresh idle fault-free report and safe lines."
  [blocked observation now request]
  (let [state (get-in observation [:print :gcode_state])
        print-error (get-in observation [:print :print_error])
        hms (get-in observation [:print :hms])
        lines (:gcode-lines request)]
    (cond
      (not= true (:confirm request))
      (core/refusal :printer/confirmation-required "Set :confirm true to authorize a print.")
      (or (not (integer? now)) (not (integer? (:observed-at observation)))
          (neg? (- now (:observed-at observation))) (> (- now (:observed-at observation)) 15000))
      (core/refusal :printer/stale-report "Request a new printer report within 15 seconds before printing.")
      (not (contains? #{"IDLE" "FINISH"} state))
      (core/refusal :printer/not-idle "Wait until the printer reports an idle state.")
      (or (not (contains? #{nil 0 "0"} print-error)) (seq hms))
      (core/refusal :printer/fault "Clear printer print error and HMS faults before printing.")
      (not (and (vector? lines) (seq lines) (every? string? lines)))
      (core/refusal :printer/missing-gcode "Supply nonempty :gcode-lines for full G-code inspection.")
      :else (if-let [unsafe (first (keep #(when-let [error (:error (core/gcode-check blocked %))] error) lines))]
              {:error unsafe}
              {:ok {:authorized true}}))))
