(ns shadow.bootstrap
  (:require [clojure.set :as set]))

(defonce loaded-libs (atom #{}))

;; calls to this will be injected by shadow-cljs
;; it will receive an array of strings matching the goog.provide
;; names that where provided by the "app"
(defn set-loaded [namespaces]
  (let [loaded (into #{} (map symbol) namespaces)]
    (swap! loaded-libs set/union loaded)))

(defn init [init-cb]
  ;; FIXME: add goog-define to path
  ;; load /js/boostrap/index.transit.json
  ;; build load index
  ;; load cljs.core$macros
  ;; load cljs.core analyzer data + maybe others
  ;; call init-cb
  (js/console.log "bootstrap init"))

(defn load [compile-state {:keys [name path macros] :as rc} cb]
  ;; check index build by init
  ;; find all dependencies
  ;; load js and ana data via xhr
  ;; maybe eval?
  ;; call cb
  (js/console.log "boot/load" rc))