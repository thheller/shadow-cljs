(ns shadow.cljs.ui.fulcro-mods
  (:require [shadow.cljs.devtools.graph.util :as graph-util]))

;; safe one import, delegate the macro?
(defmacro deftx [& args]
  `(shadow.cljs.devtools.graph.util/deftx ~@args))

