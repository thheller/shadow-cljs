(ns shadow.cljs.aot-helper
  (:gen-class))

(defn -main [& args]
  (compile 'shadow.cljs.devtools.server))
