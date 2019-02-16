(ns demo.dce
  (:require
    ["react" :as r]
    ["react-dom" :as rd]))

(defn never-used []
  (js/console.log "never-used" r)
  1)

;; react-dom uses react so react should not be removed!
;; (js/console.log "react-dom" rd)
;; (js/console.log "react" r)

(def x (atom 1))

(js/console.log "demo.dce")