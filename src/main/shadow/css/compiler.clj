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

;; argument order in case this ever moves to some kind of ast or protocols dispatching on first arg
(defn emit-def [{:keys [sel at-rules ns line column rules] :as def} w]
  (let [prefix (str/join "" (map (constantly " ") at-rules))]
    (when ns
      (emitln w (str "/* " ns " " line ":" column " */")))

    ;; could be smarter and combine at-rules if there are multiple
    ;;   @media (prefers-color-scheme: dark) {
    ;;   @media (min-width: 768px) {
    ;; could be
    ;;  @media (prefers-color-scheme: dark) and (min-width: 768px)

    ;; could also me smarter about rules and group all defs
    ;; so each rule only needs to be emitted once
    (doseq [rule at-rules]
      (emitln w rule " {"))

    (emitln w prefix sel " {")
    (doseq [prop (sort (keys rules))]
      (emitln w "  " prefix (name prop) ": " (get rules prop) ";"))

    (emits w "}")

    (doseq [_ at-rules]
      (emits w "}"))

    (emitln w)
    (emitln w)))

(defn generate-css [svc defs]
  ;; FIXME: accept writer as arg
  ;; returning a map so we can add source map data later
  {:css
   (let [sw (StringWriter.)]
     (emitln sw (:normalize-src svc))
     (emitln sw)
     (doseq [def defs]
       (emit-def def sw))
     (.toString sw))})