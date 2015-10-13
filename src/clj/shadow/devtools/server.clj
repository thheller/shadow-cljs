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
            [shadow.devtools.sass :as sass]))

(defmulti handle-client-msg (fn [client-state msg] (:type msg)) :default ::default)

(defmethod handle-client-msg ::default
  [client-state msg]
  (prn [:client-state client-state])
  (prn [:unhandled-client-msg msg])
  client-state)

(defmethod handle-client-msg :devtools/dump
  [{:keys [server id] :as client-state} msg]
  (>!! server [:devtools/dump id msg]))

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

(defn get-css-state [packages]
  (reduce-kv
    (fn [s k {:keys [manifest] :as v}]
      (let [file (io/file manifest)]
        (assoc s k (if (.exists file)
                     (.lastModified file)
                     0))))
    {}
    packages))

(defn- read-css-manifest [{:keys [manifest path] :as package}]
  (->> (io/file manifest)
       (slurp)
       (json/read-str)
       (assoc package :manifest)))

(defn- setup-css-watch [state packages]
  (let [package-names (keys packages)
        css-watch (doto (Thread.
                          (fn []
                            (loop [css-state (get-css-state packages)]
                              (Thread/sleep 500) ;; FIXME: don't use sleep
                              (let [new-state (get-css-state packages)
                                    changed (reduce
                                              (fn [changed package-name]
                                                (let [old (get css-state package-name)
                                                      new (get new-state package-name)]
                                                  (if (not= old new)
                                                    (conj changed package-name)
                                                    changed)))
                                              #{}
                                              package-names)]
                                (when (seq changed)
                                  (let [change-data (reduce (fn [data package-name]
                                                              (assoc data package-name (read-css-manifest (get packages package-name))))
                                                            {}
                                                            changed)]
                                    (prn [:broadcast-css change-data])
                                    (comment
                                      (broadcast-fn :css change-data))))
                                (recur new-state)
                                ))))
                    (.start))]
    (assoc state :css-watch css-watch)))

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
          (cond->
            css-packages
            (setup-css-watch css-packages))
          ))))


(defn shutdown-server [state]
  (when-let [instance (get-in state [:server :instance])]
    (try
      (instance)
      (catch Throwable t
        ;; ignore
        )))

  (when-let [css-watch (:css-watch state)]
    (.interrupt css-watch)))


(defn- notify-clients-about-cljs-changes! [state modified]
  (when (seq modified)
    (let [data (->> modified
                    (map (fn [name]
                           (let [{:keys [js-name provides]} (get-in state [:compiler-state :sources name])]
                             {:name name
                              :js-name js-name
                              :provides (map #(str (comp/munge %)) provides)})))
                    (into []))
          msg {:type :js
               :data data}]

      (doseq [{:keys [id out type]} (:clients state)
              :when (= :browser type)]
        (prn [:notify-about-cljs-changes! id])
        (>!! out #(client-cast % msg)))
      )))

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
                state (update state :compiler-state (fn [compiler-state]
                                                      (-> compiler-state
                                                          (cljs/reload-modified-files! modified)
                                                          (compile-callback change-names))))]

            (notify-clients-about-cljs-changes! state change-names)
            state
            )))))


(defn check-for-css-changes [state]
  )

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
      (do (sass/build-packages css-packages)
          state)
      state)))

(defn start [state config callback]
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
        server-control (get-in state [:server :server-control])]

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
        (prn [:server-loop-death!!!]))

    state
    ))

(def current-instance (atom {}))

(defn start-nrepl [state config callback]
  (let [{:keys [repl-output repl-input]} (start state config callback)]
    (swap! current-instance merge {:repl-input repl-input
                                   :repl-output repl-output})
    ::nrepl
    ))

(defn start-loop
  [state config callback]
  (let [{:keys [repl-output repl-input]} (start state config callback)]

    (go (loop []
          (let [out (<! repl-output)]
            (when-not (nil? out)
              (prn [:repl-out (dissoc out :value)])
              (let [{:keys [value out error]} out]
                (when error
                  (println "===== ERROR ========")
                  (println error)
                  (println "===================="))
                (when (seq out)
                  (apply println out))
                (when value
                  (println value)))
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
      ))

