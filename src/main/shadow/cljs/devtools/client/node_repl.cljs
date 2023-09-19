(ns shadow.cljs.devtools.client.node-repl
  (:require [shadow.cljs.devtools.client.node :as node]
            [shadow.cljs.devtools.client.console])) ;; for --inspect, doesn't hurt otherwise

(defn uncaught-exception [e origin]
  (tap> [:uncaught-exception e origin])
  (js/console.warn "---- UNCAUGHT EXCEPTION --------------")
  (js/console.warn e)
  (js/console.warn "--------------------------------------"))

(defn unhandled-rejection [e promise]
  (tap> [:unhandled-rejection e promise])
  (js/console.warn "---- UNHANDLED REJECTION --------------")
  (js/console.warn e)
  (js/console.warn "--------------------------------------"))

;; repl related things will already have executed and started to connect (async)
(defn main []
  ;; (js/process.on "uncaughtException" uncaught-exception)
  ;; not doing this, so we only attach this once on startup but
  ;; can redefine it during operation by redefining the var

  (js/process.on "uncaughtException"
    (fn [e origin]
      (uncaught-exception e origin)))

  (js/process.on "unhandledRejection"
    (fn [e promise]
      (unhandled-rejection e promise))))