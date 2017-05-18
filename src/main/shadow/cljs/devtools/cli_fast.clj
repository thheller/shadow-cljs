(ns shadow.cljs.devtools.cli-fast
  (:gen-class))

(defn -main [& args]
  (let [par-loads
        [(future (require 'clojure.core.async))
         (future (require 'shadow.cljs.build))
         ]]


    ;; tries to load these in parallel
    ;; - shadow.cljs.build will load cljs and most of the compiler stuff
    ;; - clojure.core.async is needed by the devtools but not cljs.build

    (doseq [x par-loads] @x)

    (require 'shadow.cljs.devtools.cli)

    (let [main (find-var 'shadow.cljs.devtools.cli/main)]
      (apply main args)))

  (shutdown-agents))
