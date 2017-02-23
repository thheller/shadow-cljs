(ns shadow.repl-demo
  (:require [shadow.repl :as repl]
            [shadow.repl.cljs :as r-cljs]
            [shadow.devtools.api :as api]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.net.ServerSocket))

(defn append-to-file [x]
  (spit "target/demo.log" (str (pr-str x) "\n") :append true))

(defn level-server []
  (let [ss (ServerSocket. 5000)]
    (future
      (loop []
        (let [client (.accept ss)]
          (let [out (.getOutputStream client)

                report
                (->> (for [{::repl/keys [level]
                            ::r-cljs/keys [get-current-ns]}
                           (repl/levels)]
                       (pr-str [level (get-current-ns)]))
                     (str/join "\n"))]
            (spit out report))
          (.close client))
        (recur)))
    ss
    ))

(defn tool []
  (with-open [x (level-server)]
    (let [result
          (repl/repl
            {::r-cljs/compiler-info
             append-to-file}
            {})]

      (println "Tool stop.")
      result)))
