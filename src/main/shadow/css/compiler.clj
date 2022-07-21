(ns shadow.css.compiler
  (:require [clojure.string :as str])
  (:import [java.io StringWriter Writer]))


;; helper methods for eventual data collection for source mapping
(defn emits
  ([^Writer w ^String s]
   (.write w s))
  ([w s & more]
   (emits w s)
   (doseq [s more]
     (emits w s))))

(defn emitln
  ([^Writer w]
   (.write w "\n"))
  ([^Writer w & args]
   (doseq [s args]
     (emits w s))
   (emitln w)))

(defn emit-rule [w sel rules]
  (doseq [[group-sel group-rules] rules]
    (emitln w (str/replace group-sel #"&" sel) " {")
    (doseq [prop (sort (keys group-rules))]
      (emitln w "  " (name prop) ": " (get group-rules prop) ";"))
    (emitln w "}")))

(defn emit-def [w {:keys [sel rules at-rules ns line column rules] :as def}]
  ;; (emitln w (str "/* " ns " " line ":" column " */"))

  (emit-rule w sel rules)

  (doseq [[media-query rules] at-rules]
    (emitln media-query "{")
    (emit-rule w sel rules)
    (emitln "}")))

(defn generate-css [defs]
  ;; FIXME: accept writer as arg?
  ;; returning a map so we can add source map data later
  {:css
   (let [sw (StringWriter.)]
     (doseq [def defs]
       (emit-def sw def))
     (.toString sw))})