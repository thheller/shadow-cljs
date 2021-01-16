(ns shadow.esm)

;; workaround until we can tell the closure-compiler to not rewrite
;; a dynamic import(). we just want to keep it as is to import "foreign"
;; code dynamically at runtime.
;; FIXME: can't use import as name apparantly?
;;
;; (ns foo (:require [shadow.esm :refer (import)]))
;; (defn init []
;;   (js/console.log (import "./b.js")))
;; ------------------------- ^------------------------------------------------------
;; Error in phase :compilation
;; Arguments to import must be quoted. Offending spec: ./b.js at line 10 demo / esm/a.cljs
;; --------------------------------------------------------------------------------
(defn dynamic-import
  "dynamic import(path) where path is relative to the :output-dir of the build"
  [what]
  (js/shadow_esm_import what))
