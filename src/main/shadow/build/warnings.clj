(ns shadow.build.warnings
  (:require [clojure.string :as str]
            [shadow.build.data :as data]
            [shadow.jvm-log :as log])
  (:import (java.io BufferedReader StringReader)))

(def ^:dynamic *color* true)

(defn get-source-excerpts [source locations]
  (let [source-lines
        (-> []
            (into (-> source (StringReader.) (BufferedReader.) (line-seq)))
            (cond->
              ;; add additional line since EOF errors otherwise are out of bounds
              ;; better matches how editors show EOF errors
              (str/ends-with? source "\n")
              (conj "")))

        excerpt-offset
        4 ;; +/- lines to show

        max-lines
        (count source-lines)

        make-source-excerpt
        (fn [line col]
          (let [idx (Math/max 0 (dec line))]

            ;; safety check just in case the line is invalid and not actual in the file
            (when (contains? source-lines idx)
              (let [before
                    (Math/max 0 (- line excerpt-offset))

                    after
                    (Math/min max-lines (+ line excerpt-offset))]

                {:start-idx before
                 :before (subvec source-lines before idx)
                 :line (nth source-lines idx)
                 :after (subvec source-lines line after)}
                ))))]

    (->> (for [{:keys [line column]} locations]
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

(defn print-warning-header
  [{::keys [idx] :keys [resource-name file line column source-excerpt msg] :as warning}]
  (if idx
    (println (coded-str [:bold] (sep-line (str " WARNING #" idx " - " (:warning warning) " ") 6)))
    (println (coded-str [:bold] (sep-line (str " WARNING - " (:warning warning) " ") 6))))
  (print (if file
           " File: "
           " Resource: "))
  (println (name-with-loc (or file resource-name) line column)))

(defn print-warning-msg
  [{::keys [idx] :keys [msg] :as warning}]
  (println (str " " (coded-str [:yellow :bold] msg))))

(defn print-warning
  [warning]
  (print-warning-header warning)
  (print-source-excerpt-header warning)
  (print-warning-msg warning)
  (println (sep-line))
  (print-source-excerpt-footer warning))

(defn print-short-warning
  [{:keys [msg] :as warning}]
  (print-warning-header warning)
  (println (str " " msg))
  (println (sep-line)))

;; printed after optimizations, only warnings from the closure compiler
;; FIXME: should be handled elsewhere since they are printed before CLJS warnings
(defn print-closure-warnings
  [warnings]
  (let [too-many? (> (count warnings) 3)]
    (doseq [[idx {:keys [file source-excerpt] :as w}] (map-indexed vector warnings)
            :let [w (assoc w ::idx (inc idx))]]

      (if (or (not source-excerpt)
              (and too-many? (not file)))
        (print-short-warning w)
        (print-warning w)))))

(defn print-warnings-for-build-info
  [{:keys [compile-cycle sources] :as build-info}]
  (let [warnings
        (for [{:keys [warnings] :as src} sources
              warning warnings]
          warning)

        too-many? (> (count warnings) 3)]

    (doseq [[idx {:keys [file source-excerpt] :as w}] (map-indexed vector warnings)
            :let [w (assoc w ::idx (inc idx))]]

      (println)
      (if (or (not source-excerpt)
              (and too-many? (not file))
              (and (not file) (pos? compile-cycle)))
        (print-short-warning w)
        (print-warning w)))))