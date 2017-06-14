(ns shadow.cljs.npm.client
  (:require [cljs.reader :as reader]
            ["readline" :as rl]
            ["net" :as node-net]
            [shadow.cljs.npm.util :as util]
            [clojure.string :as str]))

(defn run [project-root config server-pid args]
  (let [{:keys [socket-repl] :as ports}
        (-> (util/slurp server-pid)
            (reader/read-string))]

    (if-not socket-repl
      (prn [:no-socket-repl-port server-pid ports])

      (let [socket
            (node-net/connect socket-repl "localhost")

            last-prompt-ref
            (volatile! nil)

            rl
            (rl/createInterface
              #js {:input js/process.stdin
                   :output js/process.stdout
                   :completer
                   (fn [prefix callback]
                     ;; FIXME: hook this up properly
                     (callback nil #js [#js [] prefix]))})]

        (.on socket "connect"
          (fn [err]
            (if err
              (println "shadow-cljs - socket connect failed")

              (do (println "shadow-cljs - connected to server")

                  (.write socket (str "(shadow.cljs.devtools.cli/from-remote " (pr-str (into [] args)) ") :repl/quit\n"))

                  (.on rl "line"
                    (fn [line]
                      (.write socket (str line "\n"))))

                  ;; CTRL+D closes the rl
                  (.on rl "close"
                    (fn []
                      (.end socket)
                      (println)))
                  ))))

        (.on socket "data"
          (fn [data]
            (.pause rl)
            (let [txt (.toString data)

                  prompts
                  (re-seq #"\[(\d+):(\d+)\]\~([^=> \n]+)=> " txt)]

              (doseq [[prompt root-id level-id ns :as m] prompts]
                (vreset! last-prompt-ref {:text prompt
                                          :ns (symbol ns)
                                          :level (js/parseInt level-id 10)
                                          :root (js/parseInt root-id 10)})
                (.setPrompt rl prompt))

              (js/process.stdout.write txt)
              (.resume rl)
              )))

        (.on socket "end" #(.close rl))
        ))))

