(ns shadow.cljs.npm.client
  (:require [cljs.reader :as reader]
            ["readline" :as rl]
            ["net" :as node-net]
            [shadow.cljs.npm.util :as util]
            [clojure.string :as str]))

(defn repl-client
  "readline client that tries to maintain a prompt. not quite smart yet."
  [socket args]
  (let [last-prompt-ref
        (volatile! nil)

        rl
        (rl/createInterface
          #js {:input js/process.stdin
               :output js/process.stdout
               :completer
               (fn [prefix callback]
                 (let [last-prompt @last-prompt-ref]
                   ;; without a prompt we can't autocomplete
                   (if-not last-prompt
                     (callback nil (clj->js [[] prefix]))

                     ;; FIXME: hook this up properly
                     (callback nil (clj->js [[] prefix])))))})

        write
        (fn [text]
          ;; assume that everything we send is (read) which reads something
          ;; we can never autocomplete
          ;; and only a new prompt enables it
          (vreset! last-prompt-ref nil)
          (.write socket text))

        repl-mode?
        false

        exit-token
        (str (random-uuid))

        error-token
        (str (random-uuid))

        stop!
        (fn []
          (.close rl)
          (.end socket)
          (println))]

    (.on socket "connect"
      (fn [err]
        (if err
          (println "shadow-cljs - socket connect failed")

          (do (println "shadow-cljs - connected to server")
              ;; FIXME: this should be loaded but for some reason it sometimes can't find from_remote
              (write (str "(require 'shadow.cljs.devtools.cli)\n"))

              ;; FIXME: this is an ugly hack that will be removed soon
              ;; its just a quick way to interact with the server without a proper API protocol
              (write (str "(shadow.cljs.devtools.cli/from-remote " (pr-str exit-token) " " (pr-str error-token) " " (pr-str (into [] args)) ")\n"))

              (.on rl "line"
                (fn [line]
                  (write (str line "\n"))))

              ;; CTRL+D closes the rl
              (.on rl "close"
                (fn []
                  (stop!)))
              ))))

    (.on socket "data"
      (fn [data]
        (let [txt
              (.toString data)

              [action txt]
              (cond
                (str/includes? txt exit-token)
                [:close (str/replace txt (str exit-token "\n") "")]

                (str/includes? txt error-token)
                [:exit (str/replace txt (str error-token "\n") "")]

                :else
                [:continue txt])]

          (js/process.stdout.write txt)

          (case action
            :close
            (stop!)

            :exit
            (js/process.exit 1)

            :continue
            (let [prompts
                  (re-seq #"\[(\d+):(\d+)\]\~([^=> \n]+)=> " txt)]

              (doseq [[prompt root-id level-id ns :as m] prompts]
                (vreset! last-prompt-ref {:text prompt
                                          :ns (symbol ns)
                                          :level (js/parseInt level-id 10)
                                          :root (js/parseInt root-id 10)})
                (.setPrompt rl prompt))

              (when @last-prompt-ref
                (.prompt rl true)))))))

    (.on socket "end" #(.close rl))
    ))

(defn socket-pipe
  "client that just pipes everything through the socket without any processing"
  [socket args]
  (let [write
        (fn [text]
          (.write socket text))

        exit-token
        (str (random-uuid))

        error-token
        (str (random-uuid))

        stop!
        (fn []
          (.end socket))

        stdin-read
        (fn [buffer]
          (write (.toString buffer)))]

    (.on socket "connect"
      (fn [err]
        (if err
          (do (println "shadow-cljs - socket connect failed")
              (js/process.exit 1))

          (do (write (str "(shadow.cljs.devtools.cli/from-remote " (pr-str exit-token) " " (pr-str error-token) " " (pr-str (into [] args)) ")\n"))

              (js/process.stdin.on "data" stdin-read)
              (js/process.stdin.on "close" stop!)
              ))))

    (.on socket "data"
      (fn [data]
        (let [txt
              (.toString data)

              [action txt]
              (cond
                (str/includes? txt exit-token)
                [:close (str/replace txt (str exit-token "\n") "")]

                (str/includes? txt error-token)
                [:exit (str/replace txt (str error-token "\n") "")]

                :else
                [:continue txt])]

          (js/process.stdout.write txt)

          (case action
            :close
            (stop!)

            :exit
            (js/process.exit 1)

            :continue
            nil))))

    (.on socket "end"
      (fn []
        (js/process.stdin.removeListener "data" stdin-read)
        (js/process.stdin.removeListener "close" stop!)
        ))))

(defn run [project-root config server-pid opts args]
  (let [cli-repl
        (-> (util/slurp server-pid)
            (js/parseInt 10))]

    (if-not (pos-int? cli-repl)
      (prn [:no-socket-repl-port server-pid cli-repl])
      (let [socket (node-net/connect cli-repl "localhost")]
        (if (get-in opts [:options :stdin])
          (socket-pipe socket args)
          (repl-client socket args)
          )))))

