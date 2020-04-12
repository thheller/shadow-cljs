(ns shadow-test
  (:require
    [clojure.java.io :refer [file]]
    [nrepl.core :as nrepl]))

(defn message [s msg]
  (-> s
      (nrepl/message msg)
      (nrepl/combine-responses)
      prn))

(defn require-ns [{:keys [path-ns port build-id]}]
  (let [transport (nrepl/connect :port port)
        session (-> transport
                    (nrepl/client 1000)
                    (nrepl/client-session))]
    (message session {:op :eval
                      :code (pr-str
                              `(cider.piggieback/cljs-repl
                                 ~build-id))})
    (message session {:op :eval
                      :ns "cljs.user"
                      :code (str "(ns cljs.user (:require " path-ns " ))")})
    (.close transport)))

(defn touch-file [file-path]
  (-> (file file-path)
      (.setLastModified (System/currentTimeMillis))))

(defn -main [path path-ns port build-id]
  (let [config
        {:path path
         :path-ns path-ns
         :port (Integer/parseInt port)
         :build-id (keyword build-id)}]

    (prn config)
    (touch-file (:path config))
    (Thread/sleep 100)
    (require-ns config)))







