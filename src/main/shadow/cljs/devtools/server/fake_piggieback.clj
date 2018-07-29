(ns shadow.cljs.devtools.server.fake-piggieback)

;; tools like cider-nrepl expect to use piggieback
;; but piggieback does a lot of weird things that we neither want or need
;; so this makes it look like we are using piggieback but instead just does nothing

;; https://github.com/cemerick/piggieback/blob/master/src/cemerick/piggieback.clj
;; https://github.com/clojure-emacs/cider-nrepl/blob/master/src/cider/nrepl/middleware/util/cljs.clj

(ns cemerick.piggieback
  (:require [shadow.cljs.devtools.api :as api]))

(if (find-ns 'clojure.tools.nrepl.server)
  (require '[clojure.tools.nrepl.middleware :refer (set-descriptor!)])
  (require '[nrepl.middleware :refer (set-descriptor!)]))

;; tools access this directly via resolve
(def ^:dynamic *cljs-compiler-env* nil)

;; vim-fireplace calls this directly with a repl-env
;; can only configure which repl-env to use
;; :Piggieback (adzerk.boot-cljs-repl/repl-env)
;; so we just take a keyword?
;; :Piggieback :build-id
(defn cljs-repl [repl-env & options]
  {:pre [(keyword? repl-env)]}
  (api/nrepl-select repl-env))

(defn wrap-cljs-repl [next]
  (fn [{:keys [session] :as msg}]
    (when-not (contains? @session #'*cljs-compiler-env*)
      (swap! session assoc
        #'*cljs-compiler-env* *cljs-compiler-env*))
    (next msg)))

(ns cider.piggieback
  (:require [shadow.cljs.devtools.api :as api]))

(if (find-ns 'clojure.tools.nrepl.server)
  (require '[clojure.tools.nrepl.middleware :refer (set-descriptor!)])
  (require '[nrepl.middleware :refer (set-descriptor!)]))

;; tools access this directly via resolve
(def ^:dynamic *cljs-compiler-env* nil)

;; vim-fireplace calls this directly with a repl-env
;; can only configure which repl-env to use
;; :Piggieback (adzerk.boot-cljs-repl/repl-env)
;; so we just take a keyword?
;; :Piggieback :build-id
(defn cljs-repl [repl-env & options]
  {:pre [(keyword? repl-env)]}
  (api/nrepl-select repl-env))

(defn wrap-cljs-repl [next]
  (fn [{:keys [session] :as msg}]
    (when-not (contains? @session #'*cljs-compiler-env*)
      (swap! session assoc
        #'*cljs-compiler-env* *cljs-compiler-env*))
    (next msg)))
