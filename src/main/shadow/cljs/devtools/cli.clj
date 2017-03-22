(ns shadow.cljs.devtools.cli
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.devtools.api :as api]))

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

(defn autotest []
  (api/autotest))

(defn test-all []
  (api/test-all))

(defn test-affected [test-ns]
  (api/test-affected [(cljs/ns->cljs-file test-ns)]))
