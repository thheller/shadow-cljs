(ns shadow.cljs.devtools.cli
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.node :as node]))

;; FIXME: spec for cli
(defn- parse-args [[build-id & more :as args]]
  {:build (keyword build-id)})

(defn once [& args]
  (-> args (parse-args) (api/once)))

(defn dev [& args]
  (-> args (parse-args) (api/dev)))

(defn node-repl [& args]
  ;; FIXME: ignoring args
  (api/node-repl))

(defn release [& args]
  (-> args (parse-args) (api/release)))

(defn autotest
  "no way to interrupt this, don't run this in nREPL"
  []
  (-> (api/test-setup)
      (cljs/watch-and-repeat!
        (fn [state modified]
          (-> state
              (cond->
                ;; first pass, run all tests
                (empty? modified)
                (node/execute-all-tests!)
                ;; only execute tests that might have been affected by the modified files
                (not (empty? modified))
                (node/execute-affected-tests! modified))
              )))))


(defn test-all []
  (api/test-all))

(defn test-affected [test-ns]
  (api/test-affected [(cljs/ns->cljs-file test-ns)]))
