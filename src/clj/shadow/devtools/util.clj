(ns shadow.devtools.util
  (:require [cljs.stacktrace :as st]
            [clojure.string :as str]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]))

;; FIXME: doesn't do mapping yet

(defn parse-stacktrace
  [{:keys [stacktrace] :as msg}]
  (with-redefs [st/parse-file
                ;; asset-root is part of the error information received from the client
                ;; instead of trying to reconstruct it from repl-env/compiler-options
                ;; probably only works in browser env
                (fn [repl-env file {:keys [asset-root]}]
                  (str/replace file asset-root ""))]
    (st/parse-stacktrace {} stacktrace msg msg)))
