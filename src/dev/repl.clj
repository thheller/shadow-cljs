(ns repl
  (:require
    [shadow.cljs.devtools.server :as server]
    [shadow.cljs.devtools.api :as cljs]
    [shadow.cljs.devtools.cli]
    [shadow.cljs.devtools.server.fs-watch :as fs-watch]
    [clojure.java.io :as io]
    [build]))

(defonce css-watch-ref (atom nil))

(comment
  (generate-css))

(defn start []
  (server/start!)

  ;; (cljs/watch :ui)

  (build/css-release)

  (reset! css-watch-ref
    (fs-watch/start
      {}
      [(io/file "src" "main")]
      ["cljs" "cljc" "clj"]
      (fn [updates]
        (try
          (build/css-release)
          (catch Exception e
            (prn [:css-failed e])))
        )))

  ::started)

(defn stop []
  (when-some [css-watch @css-watch-ref]
    (fs-watch/stop css-watch))

  (server/stop!)
  ::stopped)

(defn go []
  (stop)
  (start))