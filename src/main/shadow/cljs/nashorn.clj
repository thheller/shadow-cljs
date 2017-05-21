(ns shadow.cljs.nashorn
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.repl :as repl]
            [clojure.java.io :as io])
  (:import [javax.script ScriptEngine ScriptEngineManager Invocable]))

;; just testing some nashorn related things
;; this works fine, just not that interested in nashorn

(defn -main [& args]
  (let [sem
        (ScriptEngineManager.)

        se
        (.getEngineByName sem "nashorn")

        public-path
        "/js"

        output-dir
        (doto (io/file "target/nashorn-cljs/js")
          (io/make-parents))

        {:keys [repl-state] :as state}
        (-> (cljs/init-state)
            (assoc :output-dir output-dir
                   :public-path public-path)
            (cljs/find-resources-in-classpath)
            (repl/prepare))

        {:keys [repl-sources]}
        repl-state]

    ;; load in goog/base.js
    (.eval se @(get-in state [:sources "goog/base.js" :input]))

    ;; setup repl
    (doseq [src repl-sources]
      (let [js (get-in state [:sources src :output])]
        (.eval se js)))

    ;; (prn (.invokeMethod ^Invocable se (.eval se "cljs.core") "pr_str" (into-array ["foo" "bar"])))
    ))
