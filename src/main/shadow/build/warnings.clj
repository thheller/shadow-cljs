(ns shadow.build.warnings
  (:require [clojure.string :as str]
            [shadow.build.data :as data]
            [clojure.tools.logging :as log])
  (:import (java.io BufferedReader StringReader)))

(def ^:dynamic *color* true)

(defn get-source-excerpts [source locations]
  (let [source-lines
        (into [] (-> source
                     (StringReader.)
                     (BufferedReader.)
                     (line-seq)))

        excerpt-offset
        4 ;; +/- lines to show

        max-lines
        (count source-lines)

        make-source-excerpt
        (fn [line col]
          (let [line
                (Math/max 0 (Math/min line (dec max-lines))) ;; too paranoid?

                before
                (Math/min (Math/max 0 (- line excerpt-offset)) (dec max-lines))

                idx
                (Math/min (Math/max 0 line) (dec max-lines))

                after
                (Math/max 0 (Math/min (+ line excerpt-offset) (dec max-lines)))]

            {:start-idx before
             :before (subvec source-lines before idx)
             :line (nth source-lines idx)
             :after (subvec source-lines line after)}))]

    (->> (for [{:keys [line column] :as location} locations]
           (make-source-excerpt line column))
         (into []))))

(defn get-source-excerpts-for-rc
  "adds source excerpts to warnings if line information is available"
  [state rc locations]
  (let [{:keys [source]} (data/get-output! state rc)]
    (get-source-excerpts source locations)
    ))

(defn get-source-excerpt-for-rc [state rc location]
  (-> (get-source-excerpts-for-rc state rc [location])
      (first)))

;; https://en.wikipedia.org/wiki/ANSI_escape_code
;; https://github.com/chalk/ansi-styles/blob/master/index.js

(def sgr-pairs
  {:reset [0, 0]
   :bold [1 22]
   :dim [2 22] ;; cursive doesn't support this
   :yellow [33 39] ;; cursive doesn't seem to support 39
   :red [31 39]
   })

(defn ansi-seq [codes]
  (str \u001b \[ (str/join ";" codes) \m))

(defn coded-str [codes s]
  (let [open
        (->> (map sgr-pairs codes)
             (map first))
        close
        (->> (map sgr-pairs codes)
             (map second))]

    ;; FIXME: cursive doesn't support some ANSI codes
    ;; always reset to 0 sucks if there are nested styles
    (if-not *color*
      s
      (str (ansi-seq open) s (ansi-seq [0])))))

;; all this was pretty rushed and should be rewritten propely
;; long lines are really ugly and should maybe do some kind of word wrap
(def sep-length 80)

(defn sep-line
  ([]
   (sep-line "" 0))
  ([label offset]
   (let [sep-len (Math/max sep-length offset)
         len (count label)

         sep
         (fn [c]
           (->> (repeat c "-")
                (str/join "")))]
     (str (sep offset) label (sep (- sep-len (+ offset len)))))))

(defn print-source-lines
  [start-idx lines transform]
  (->> (for [[idx text] (map-indexed vector lines)]
         (format "%4d | %s" (+ 1 idx start-idx) text))
       (map transform)
       (str/join "\n")
       (println)))

(defn dim [s]
  (coded-str [:dim] s))


(defn print-source-excerpt-header
  [{:keys [source-excerpt column] :as warning}]
  (let [{:keys [start-idx before line after]} source-excerpt]
    (println (sep-line))
    (print-source-lines start-idx before dim)
    (print-source-lines (+ start-idx (count before)) [line] #(coded-str [:bold] %))
    ;; all CLJS warnings start at column 1
    ;; closure source mapped errors always seem to have column 0
    ;; doesn't make sense to have an arrow then
    (if (pos-int? column)
      (let [arrow-idx (+ 6 (or column 1))]
        (println (sep-line "^" arrow-idx)))
      (println (sep-line)))))

(defn print-source-excerpt-footer
  [{:keys [source-excerpt] :as warning}]
  (let [{:keys [start-idx before line after]} source-excerpt]

    (when (seq after)
      (print-source-lines (+ start-idx (count before) 1) after dim)
      (println (sep-line)))
    ))

(defn name-with-loc [name line column]
  (str name
       (when (pos-int? line)
         (str ":" line
              (when (pos-int? column)
                (str ":" column))))))

(defn print-warning
  [{::keys [idx] :keys [resource-name file line column source-excerpt msg] :as warning}]
  (println (coded-str [:bold] (sep-line (str " WARNING #" idx " ") 6)))
  (println " File:" (name-with-loc (or file resource-name) line column))

  (if (> idx 3)
    (do (println (str " " (coded-str [:yellow :bold] msg)))
        (println (sep-line)))
    (if-not source-excerpt
      (do (println)
          (println (str " " (coded-str [:yellow :bold] msg)))
          (println (sep-line)))

      (do (print-source-excerpt-header warning)
          (println (str " " (coded-str [:yellow :bold] msg)))
          (println (sep-line))
          (print-source-excerpt-footer warning))))
  (println))

(defn print-warnings
  [warnings]
  (doseq [[idx w] (map-indexed vector warnings)]
    (print-warning (assoc w ::idx (inc idx)))))

(defn get-warnings-for-build [{:keys [build-sources sources output] :as state}]
  (for [id build-sources
        :let [{:keys [resource-name file url] :as rc} (data/get-source-by-id state id)
              {:keys [warnings]} (data/get-output! state rc)]
        warning warnings]
    (assoc warning :source-id id :resource-name resource-name :file file :url url)))

(defn print-warnings-for-build [state]
  (->> (get-warnings-for-build state)
       (print-warnings)))