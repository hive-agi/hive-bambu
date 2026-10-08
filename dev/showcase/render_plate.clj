(ns render-plate
  "Draw a sliced BambuStudio plate from its G-code, top down, as SVG.

   Usage: bb dev/showcase/render_plate.clj <plate_1.gcode> <out-dir>
   Writes plate-first-layer.svg (first layer) and plate-all-layers.svg (every
   layer, colored from low Z to high Z). Extrusion is a G1 with a positive E
   (BambuStudio emits M83, relative E); any other move breaks the path.
   Convert with: rsvg-convert -w 1200 in.svg -o out.png"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- word [line letter]
  (some (fn [w] (when (and (seq w) (= letter (first w)))
                  (parse-double (subs w 1))))
        (str/split line #"\s+")))

(defn layers
  "Vector of layers; each layer is a vector of paths [[x y] ...] of extrusion."
  [gcode-file]
  (with-open [r (io/reader gcode-file)]
    (loop [lines (line-seq r) x 0.0 y 0.0 path [] layer [] out []]
      (if-let [line (first lines)]
        (cond
          (str/starts-with? line "; CHANGE_LAYER")
          (let [layer (cond-> layer (> (count path) 1) (conj path))]
            (recur (rest lines) x y [] [] (cond-> out (seq layer) (conj layer))))

          (re-find #"^G[01] " line)
          (let [nx (or (word line \X) x) ny (or (word line \Y) y) e (word line \E)
                moved? (or (not= nx x) (not= ny y))]
            (cond
              (and moved? e (pos? e))
              (recur (rest lines) nx ny (if (seq path) (conj path [nx ny]) [[x y] [nx ny]]) layer out)
              moved?
              (recur (rest lines) nx ny [] (cond-> layer (> (count path) 1) (conj path)) out)
              :else (recur (rest lines) x y path layer out)))

          :else (recur (rest lines) x y path layer out))
        (let [layer (cond-> layer (> (count path) 1) (conj path))]
          (cond-> out (seq layer) (conj layer)))))))

(defn- bounds [paths]
  (let [pts (mapcat identity paths) xs (map first pts) ys (map second pts)]
    [(apply min xs) (apply min ys) (apply max xs) (apply max ys)]))

(defn- color [t]
  (let [lerp (fn [a b] (int (+ a (* t (- b a)))))]
    (format "rgb(%d,%d,%d)" (lerp 120 255) (lerp 60 196) (lerp 10 70))))

(defn svg
  "SVG text for LAYERS (each a vector of paths), framed to their bounds."
  [layers title]
  (let [[x0 y0 x1 y1] (bounds (mapcat identity layers))
        pad 6 w (+ (- x1 x0) (* 2 pad)) h (+ (- y1 y0) (* 2 pad))
        n (count layers)
        pt (fn [[x y]] (format "%.2f,%.2f" (+ pad (- x x0)) (+ pad (- y1 y))))]
    (str "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 " w " " (+ h 14) "'>"
         "<rect width='100%' height='100%' fill='#161e26'/>"
         "<text x='" pad "' y='" (+ h 10) "' font-family='sans-serif' font-size='5' fill='#c9d4dd'>" title "</text>"
         (str/join
          (map-indexed
           (fn [i layer]
             (str "<g fill='none' stroke='" (color (if (> n 1) (/ i (dec n)) 1.0))
                  "' stroke-width='0.42' stroke-linejoin='round'>"
                  (str/join (map #(str "<polyline points='" (str/join " " (map pt %)) "'/>") layer))
                  "</g>"))
           layers))
         "</svg>")))

(defn -main [gcode out-dir]
  (let [ls (layers gcode)]
    (spit (io/file out-dir "plate-first-layer.svg")
          (svg [(first ls)] "First layer, from the BambuStudio G-code"))
    (spit (io/file out-dir "plate-all-layers.svg")
          (svg ls (str "All " (count ls) " layers, low Z dark to high Z bright")))
    (println "layers" (count ls))))

(apply -main *command-line-args*)
