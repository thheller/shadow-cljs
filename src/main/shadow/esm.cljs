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

;; the compiler fills this with data after compilation, so that all vars marked
;; as ^:lazy-loadable result calls to add-loadable appended to this file
;; (shadow.esm/add-loadable "the.fully.qualified/name" "foo" (fn [] the.fully.qualified/name))
;; we do all this because of the "foo" which represents the name of the module we need to load
;; this means the user doesn't need to remember or change which module a given thing is in
;; it uses an accessor function so that it can work with hot-reload and doesn't return outdated references
(def loadables #js {})

(def loaded #js {})

(defn get-loaded [the-name]
  (unchecked-get loaded (str the-name)))

(defn load-by-name [the-name]
  (let [s (str the-name)
        info (unchecked-get loadables s)]
    (when-not info
      (throw (js/Error. (str "could not find loadable info for: " s))))

    (let [module (unchecked-get info "module")
          accessor (unchecked-get info "get")]

      (-> (dynamic-import (str "./" module ".js"))
          (.then (fn [module]
                   (unchecked-set loaded s accessor)
                   ;; don't want to rely on module exports, makes config too verbose
                   accessor))))))


;; the compiler emits JS directly, and doesn't actually call this
;; making it private just to people don't assume this is something they are supposed to call
(defn- add-loadable [the-name module accessor]
  (unchecked-set loadables (str the-name) #js {"module" module "get" accessor}))