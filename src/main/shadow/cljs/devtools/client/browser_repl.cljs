(ns shadow.cljs.devtools.client.browser-repl
  (:require [shadow.dom :as dom]))

(def log-node (dom/by-id "log"))

(let [actual-print-fn
      cljs.core/*print-fn*]

  (set-print-fn!
    (fn [s]
      (dom/append log-node (str s "\n"))
      (actual-print-fn s))))
