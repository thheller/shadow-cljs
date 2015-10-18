(ns shadow.devtools.server
  (:import (java.util UUID)
           (java.io InputStreamReader BufferedReader)
           (shadow.util FakeLispReader))
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.repl :as repl]
            [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
            [clojure.data.json :as json]
            [org.httpkit.server :as hk]
            [cljs.compiler :as comp]
            [clojure.edn :as edn]
            [clojure.core.async :as async :refer (go <! >! <!! >!! alt! timeout)]
            [shadow.cljs.repl :as repl]
            [clojure.string :as str]
            [cljs.stacktrace :as st]
            [shadow.devtools.sass :as sass]
            [shadow.devtools.util :as util]))

(defmulti handle-client-msg (fn [client-state msg] (:type msg)) :default ::default)

(defmethod handle-client-msg ::default
  [client-state msg]
  (prn [:client-state client-state])
  (prn [:unhandled-client-msg msg])
  client-state)

(defmethod handle-client-msg :devtools/dump
  [{:keys [server id] :as client-state} msg]
  (>!! server [:devtools/dump id msg]))

;; FIXME: don't print to *out*, we have repl-out for that
(defmethod handle-client-msg :repl/out
  [client-state {:keys [out] :as msg}]
  (doseq [s out]
    (println s))
  client-state)

(defmethod handle-client-msg :repl/result
  [client-state {:keys [id] :as msg}]
  (let [result-chan (get-in client-state [:pending id])]
    (if (nil? result-chan)
      ;; FIXME: result id does not have a pending channel
      (do (prn [:client-state client-state])
          (prn [:result-for-unknown-action msg])
          (.format System/err "REPL-RESULT: %s%n" (object-array [(pr-str msg)]))
          client-state)
      (do (>!! result-chan msg)
          (update client-state :pending dissoc id)))))

(defn handle-client-data [client-state msg]
  (let [msg (edn/read-string msg)]
    (handle-client-msg client-state msg)))

;; cast&call as in erlang (fire-and-forget, rpc-ish)

(defn client-cast [{:keys [channel] :as state} cmd]
  (let [msg (pr-str cmd)]
    (hk/send! channel msg))
  state)

(defn client-call [{:keys [channel] :as state} cmd idx result-chan]
  (let [msg (pr-str cmd)]
    (hk/send! channel msg)
    (update state :pending assoc idx result-chan)
    ))

(defn client-init-state [{:keys [channel] :as client-state} repl-state]
  (hk/send! channel (pr-str {:type :repl/init
                             :repl-state repl-state}))
  client-state)

(defn client-loop
  "out is a channel which should only contain function for (fn state) -> next-state
   in should only receive anything from the websocket"
  [id channel server in out]
  (go (loop [state {:channel channel
                    :server server
                    :id id}]
        (alt!
          out
          ([f]
            (when-not (nil? f)
              (if (fn? f)
                (recur (f state))
                (do (prn [:invalid-client-message "should only contain function that take the current client state"])
                    state))))
          in
          ([v]
            (when-not (nil? v)
              (recur (handle-client-data state v)))
            )))))


(defn- ring-handler [server-control ring-request]
  (let [client-in (async/chan)
        client-out (async/chan)
        [_ client-id client-type] (str/split (:uri ring-request) #"/")
        client-type (keyword client-type)]

    (hk/with-channel
      ring-request channel
      (if (hk/websocket? channel)
        (do

          (hk/on-receive channel
            (fn [data] (>!! client-in data)))

          (hk/on-close channel
            (fn [status]
              (>!! server-control [:disconnect client-id])
              (async/close! client-out)
              (async/close! client-in)
              ))

          (client-loop client-id channel server-control client-in client-out)
          (>!! server-control [:connect client-id client-type client-out])

          channel)

        ;; only expecting a websocket connection yet
        (hk/send! channel {:status 406 ;; not-acceptable
                           :headers {"Content-Type" "text/plain"}
                           :body "websocket required"})))))

(defn- start-server [state {:keys [port host] :as config}]
  (let [server-control (async/chan)
        handler #(ring-handler server-control %)]

    (let [host (or host "localhost")
          instance (hk/run-server handler {:ip host
                                           :port (or port 0)})]

      {:instance instance
       :port (:local-port (meta instance))
       :host host
       :server-control server-control})))

(defn setup-server
  "config is a map with these options:
   :host the interface to create the websocket server on (defaults to \"localhost\")
   :port the port to listen to (defaults to random port)
   :before-load fully qualified function name to execute BEFORE reloading new files
   :after-load fully qualified function name to execute AFTER reloading ALL files

   live-reload will only load namespaces that were already required"
  [{:keys [compiler-state config] :as state}]
  (let [{:keys [logger]} compiler-state
        {:keys [before-load after-load css-packages]} config]

    (let [{:keys [host port] :as server} (start-server state config)

          ;; setup compiler to inject what we need
          compiler-state
          (-> compiler-state
              (update :closure-defines merge {"shadow.devtools.enabled"
                                              true

                                              "shadow.devtools.url"
                                              (str "http://" host ":" port)

                                              "shadow.devtools.before_load"
                                              (when before-load
                                                (str (comp/munge before-load)))

                                              "shadow.devtools.after_load"
                                              (when after-load
                                                (str (comp/munge after-load)))
                                              })

              (update-in [:modules (:default-module compiler-state) :mains] conj 'shadow.devtools.browser))]

      (cljs/log-progress logger (format "DEVTOOLS started: %s" (pr-str config)))
      (-> state
          (assoc :server server
                 :config config
                 :compiler-state compiler-state)
          ))))

(defn shutdown-server [state]
  (when-let [instance (get-in state [:server :instance])]
    (try
      (instance)
      (catch Throwable t
        ;; ignore
        ))))

(defn- send-to-clients-of-type [state client-type msg]
  (doseq [{:keys [out type]} (vals (:clients state))
          :when (= type client-type)]
    (>!! out #(client-cast % msg))))

(defn- notify-clients-about-cljs-changes!
  [{:keys [compiler-state] :as state} modified]
  (when (seq modified)
    (let [js-sources
          (->> modified
               (mapcat #(cljs/get-deps-for-src compiler-state %))
               (distinct)
               (map #(get-in compiler-state [:sources % :js-name]))
               (into []))

          reload
          (->> modified
               (map #(get-in compiler-state [:sources % :js-name]))
               (into #{}))

          msg {:type :js/reload
               :js-sources js-sources
               :reload reload}]

      (send-to-clients-of-type state :browser msg)
      )))

(defn- notify-clients-about-css-changes!
  [state {:keys [name public-path manifest]}]
  (let [msg {:type :css/reload
             :name name
             :public-path public-path
             :manifest manifest}]
    (send-to-clients-of-type state :browser msg)
    ))

(defn setup-repl [state]
  (update state :compiler-state repl/prepare))

(defn get-clients-by-type [{:keys [clients] :as state} type]
  (->> clients
       (vals)
       (filter #(= type (:type %)))
       (into [])))

(defn handle-repl-input [{:keys [compiler-state] :as state} repl-input result-chan]
  (let [clients (get-clients-by-type state :browser)]
    (cond
      ;; FIXME: could send to all?
      (> (count clients) 1)
      (do (prn [:too-many-clients (count clients)])
          state)

      (zero? (count clients))
      (do (prn [:no-browser-connected])
          state)

      :else
      (let [{:keys [id out] :as client} (first clients)

            start-idx (count (get-in compiler-state [:repl-state :repl-actions]))

            {:keys [repl-state] :as compiler-state}
            (try
              (repl/process-input compiler-state repl-input)
              (catch Throwable e
                (prn [:failed-to-process-repl-input e])
                (pprint (:repl-state compiler-state))
                compiler-state
                ))

            new-actions (subvec (:repl-actions repl-state) start-idx)]

        (doseq [[idx action] (map-indexed vector new-actions)
                :let [idx (+ idx start-idx)
                      action (assoc action :id idx)]]

          ;; (prn [:invoke client-id action])
          (>!! out #(client-call % action idx result-chan)))

        (assoc state :compiler-state compiler-state)
        ))))


(defmulti handle-server-control
  (fn [state cmd] (first cmd))
  :default ::default)

(defmethod handle-server-control ::default [state cmd]
  (prn [:unrecognized cmd])
  state)

(defmethod handle-server-control :devtools/dump
  [state cmd]
  (prn [:dump cmd])
  state)

(defmethod handle-server-control :connect [state [_ client-id client-type client-out]]
  (prn [:client-connect client-id client-type])
  (when (= client-type :browser)
    (let [init-state (get-in state [:compiler-state :repl-state])]
      (>!! client-out #(client-init-state % init-state))))

  (update state :clients assoc client-id {:id client-id
                                          :type client-type
                                          :out client-out}))

(defmethod handle-server-control :disconnect [state [_ client-id]]
  (prn [:client-disconnect client-id])
  (update state :clients dissoc client-id))

(defn check-for-fs-changes
  [{:keys [compiler-state compile-callback fs-seq] :as state}]
  (let [modified (cljs/scan-for-modified-files compiler-state)
        ;; scanning for new files is expensive, don't do it that often
        modified (if (zero? (mod fs-seq 5))
                   (concat modified (cljs/scan-for-new-files compiler-state))
                   modified)]
    (if-not (seq modified)
      (assoc state ::fs-seq (inc fs-seq))
      (do (prn [:reloading-modified])
          (let [change-names (mapv :name modified)
                state (update state :compiler-state cljs/reload-modified-files! modified)]
            (try
              (let [state (update state :compiler-state compile-callback change-names)]
                (notify-clients-about-cljs-changes! state change-names)
                state)
              (catch Exception e
                ;; FIXME: notify clients
                (prn [:compilation-error e])
                state
                )))))))


(defn check-for-css-changes [{:keys [css-packages] :as state}]
  (let [new-packages
        (reduce
          (fn [pkgs {:keys [dirty-check] :as pkg}]
            (if-not (dirty-check)
              (conj pkgs pkg)
              (let [new-pkg (sass/build-package pkg)]
                (prn [:dirty-css (:name new-pkg)])
                (notify-clients-about-css-changes! state new-pkg)
                (conj pkgs new-pkg))))
          []
          css-packages)]

    (assoc state :css-packages new-packages)))

(defmethod handle-server-control :idle
  [{:keys [config] :as state} _]
  (let [js-reload (:js-reload config true)
        css-reload (and (:css-reload config true)
                        (seq (:css-packages config)))]
    (-> state
        (cond->
          js-reload
          (check-for-fs-changes)
          css-reload
          (check-for-css-changes)
          ))))

(defn setup-css [{:keys [config] :as state}]
  (let [css-packages (:css-packages config)]
    (if (seq css-packages)
      (do (let [initial-state (->> css-packages
                                   (map sass/build-package)
                                   (map sass/create-package-watch)
                                   (into []))]
            (assoc state :css-packages initial-state)))
      state)))

(defn default-compile-callback [state modified]
  (-> state
      (cljs/compile-modules)
      (cljs/flush-unoptimized)))

(defn start
  ([state config]
    (start state config default-compile-callback))
  ([state config callback]
   (let [repl-input (async/chan)
         repl-output (async/chan)

         state (-> {:compiler-state state
                    :clients {}
                    :fs-seq 1
                    :compile-callback callback
                    :repl-input repl-input
                    :config config
                    :repl-output repl-output}
                   (setup-server)
                   (setup-css)
                   (setup-repl))

         state (assoc state :compiler-state (callback (:compiler-state state) []))
         server-control (get-in state [:server :server-control])
         server-loop
         (go (loop [state state]
               (alt!
                 server-control
                 ([v]
                   (when-not (nil? v)
                     (recur
                       (try
                         (handle-server-control state v)
                         (catch Exception e
                           (prn [:server-error e v])
                           state
                           )))))

                 repl-input
                 ([v]
                   (when-not (nil? v)
                     (recur
                       (try
                         (let [[msg result-chan] v]
                           (handle-repl-input state msg result-chan))
                         (catch Exception e
                           (prn [:repl-error e v])
                           state
                           )))))

                 (timeout 500)
                 ([_]
                   (recur
                     (try
                       (handle-server-control state [:idle])
                       (catch Exception e
                         (prn [:idle-error e])
                         state))
                     ))))

             (shutdown-server state)
             ;; return value of this channel is its last state
             (update state :server dissoc :instance))]

     (assoc state :server-loop server-loop))))

(def current-instance (atom {}))

(defn start-nrepl [state config callback]
  (let [{:keys [repl-output repl-input]} (start state config callback)]
    (swap! current-instance merge {:repl-input repl-input
                                   :repl-output repl-output})
    ::nrepl
    ))

(defn start-loop
  ([state config]
    (start-loop state config default-compile-callback))
  ([state config callback]
   (let [{:keys [repl-output repl-input]} (start state config callback)]

     (go (loop []
           (let [msg (<! repl-output)]
             (when-not (nil? msg)
               (try
                 (prn [:repl-out (:type msg)])
                 (let [{:keys [value error]} msg]
                   (when error
                     (println "===== ERROR ========")
                     (println error)
                     (when-let [trace (:stacktrace msg)]
                       (pprint (util/parse-stacktrace msg)))
                     (println "===================="))
                   (when value
                     (println value)))
                 (catch Exception e
                   (prn [:print-ex e])))
               (recur)
               ))))

     ;; this really sucks but I don't care much for the streaming nature of a REPL
     ;; still want to be able to eval multi-line forms though

     ;; stuff like [1 2 : 3] will cause hazard though, so that needs to be handled somehow


     (let [in (FakeLispReader.)]
       (prn [:repl-ready])
       (loop []
         (let [msg (.next in)]
           (when-not (nil? msg)
             (when (not= msg ":cljs/quit")
               (>!! repl-input [msg repl-output])
               (recur)))))
       (async/close! repl-input)
       (async/close! repl-output)
       (prn [:repl-quit])
       ))))

