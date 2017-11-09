(ns shadow.cljs.devtools.server.nrepl
  (:refer-clojure :exclude (send select))
  (:require [clojure.pprint :refer (pprint)]
            [clojure.tools.nrepl.middleware :as middleware]
            [clojure.tools.nrepl.middleware.session :as nrepl-session]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.misc :as misc]
            [clojure.tools.nrepl.server :as server]
            [clojure.edn :as edn]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.api :as api]
            [clojure.tools.logging :as log]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.devtools.server.repl-impl :as repl-impl]
            [shadow.cljs.devtools.fake-piggieback :as fake-piggieback]
            [clojure.java.io :as io])
  (:import (java.io StringReader)))

(def ^:private print-cause-trace
  (delay
   (do
     (require 'clojure.stacktrace)
     (resolve 'clojure.stacktrace/print-cause-trace))))

(defn send [{:keys [transport session id] :as req} {:keys [status] :as msg}]
  (let [res
        (-> msg
            (cond->
              id
              (assoc :id id)
              session
              (assoc :session (-> session meta :id))
              (and (some? status)
                   (not (coll? status)))
              (assoc :status #{status})))]

    (log/debug "nrepl-send" id (pr-str res))
    (transport/send transport res)))

(defn do-cljs-eval [{::keys [build-id worker] :keys [session code] :as msg}]
  (let [reader (StringReader. code)]

    (loop []
      (when-let [repl-state (repl-impl/worker-repl-state worker)]

        ;; need the repl state to properly support reading ::alias/foo
        (let [{:keys [eof? form] :as read-result}
              (repl/read-one repl-state reader {})]

          (cond
            eof?
            :eof

            (nil? form)
            (recur)

            (= :repl/quit form)
            (do (swap! session dissoc #'api/*nrepl-cljs*)
                (send msg {:value ":repl/quit" :ns (-> *ns* ns-name str)}))

            (= :cljs/quit form)
            (do (swap! session dissoc #'api/*nrepl-cljs*)
                (send msg {:value ":cljs/quit" :ns (-> *ns* ns-name str)}))

            ;; Cursive supports
            ;; {:status :eval-error :ex <exception name/message> :root-ex <root exception name/message>}
            ;; {:err string} prints to stderr
            :else
            (when-some [result (worker/repl-eval worker ::stdin read-result)]
              (log/debug "nrepl-eval-result" (pr-str result))
              (case (:type result)
                :repl/result
                (let [repl-state (repl-impl/worker-repl-state worker)

                      repl-ns
                      (-> repl-state :current :ns)]
                  (send msg (assoc result :ns (pr-str repl-ns)))) ;; :value is already a edn string

                :repl/set-ns-complete
                (let [repl-state (repl-impl/worker-repl-state worker)

                      repl-ns
                      (-> repl-state :current :ns)]
                  (send msg {:value (pr-str repl-ns) :ns (pr-str repl-ns)}))

                :repl/require-complete
                nil

                :repl/interrupt
                nil

                :repl/timeout
                (send msg {:err "REPL command timed out.\n"})

                :repl/no-eval-target
                (send msg {:err "There is no connected JS runtime.\n"})

                :repl/too-many-eval-clients
                (send msg {:err "There are too many JS runtimes, don't know which to eval in.\n"})

                :repl/error
                (send msg {:ex (str (class (:ex result)))
                           :err (with-out-str (@print-cause-trace (:ex result)))})

                ;; Default
                (send msg {:err (pr-str [:FIXME result])}))

              (recur))
            ))))

    (send msg {:status :done})
    ))

(defn cljs-select [next]
  (fn [{:keys [session op] :as msg}]
    (let [repl-var #'api/*nrepl-cljs*]
      (when-not (contains? @session repl-var)
        (swap! session assoc repl-var nil))

      (swap! session assoc #'api/*nrepl-active* true)

      (let [build-id
            (get @session repl-var)

            worker
            (when build-id
              (api/get-worker build-id))]

        ;; (prn [:cljs-select op build-id (some? worker) (keys msg)])
        #_(when (= op "eval")
            (println)
            (println (:code msg))
            (println)
            (flush))

        (if worker
          (swap! session assoc #'cemerick.piggieback/*cljs-compiler-env* (-> worker :state-ref deref :build-state :compiler-env))
          (swap! session dissoc #'cemerick.piggieback/*cljs-compiler-env*))

        (-> msg
            (cond->
              worker
              ;; FIXME: add :cljs.env/compiler key for easier access?
              (assoc ::worker worker ::build-id build-id))
            (next)
            )))))

(middleware/set-descriptor!
  #'cljs-select
  {:requires
   #{#'clojure.tools.nrepl.middleware.session/session}

   :handles
   {"cljs/select" {}}})

(defn cljs-eval [next]
  (fn [{::keys [worker] :keys [op] :as msg}]
    (cond
      (and worker (= op "eval"))
      (do-cljs-eval msg)

      :else
      (next msg))))

(middleware/set-descriptor!
  #'cljs-eval
  {:requires
   #{#'cljs-select}

   :expects
   #{"eval"}})

(defn do-cljs-load-file [{::keys [worker] :keys [file file-path] :as msg}]
  (worker/load-file worker {:file-path file-path :source file})
  (send msg {:status :done}))

(defn cljs-load-file [next]
  (fn [{::keys [worker] :keys [op] :as msg}]
    (cond
      (and worker (= op "load-file"))
      (do-cljs-load-file msg)

      :else
      (next msg))))

(middleware/set-descriptor!
  #'cljs-load-file
  {:requires
   #{#'cljs-select}

   :expects
   #{"load-file"}})

;; fake piggieback descriptor
(middleware/set-descriptor!
  #'cemerick.piggieback/wrap-cljs-repl
  {:requires
   #{#'cljs-select}

   ;; it doesn't do anything, it is just here for cider-nrepl
   :expects
   #{"eval" "load-file"}})

(defn middleware-load [sym]
  {:pre [(qualified-symbol? sym)]}
  (let [sym-ns (-> sym (namespace) (symbol))]
    (require sym-ns)
    (or (find-var sym)
        (println (format "nrepl middleware not found: %s" sym)))
    ))

(defn make-middleware-stack [extra-middleware]
  (-> []
      (into (->> extra-middleware
                 (map middleware-load)
                 (remove nil?)))
      (into [#'clojure.tools.nrepl.middleware/wrap-describe
             #'clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval
             #'clojure.tools.nrepl.middleware.load-file/wrap-load-file

             ;; provided by fake-piggieback, only because tools expect piggieback
             #'cemerick.piggieback/wrap-cljs-repl

             ;; cljs support
             #'cljs-load-file
             #'cljs-eval
             #'cljs-select

             #'clojure.tools.nrepl.middleware.session/add-stdin
             #'clojure.tools.nrepl.middleware.session/session])
      (middleware/linearize-middleware-stack)))

(comment
  (pprint
    (make-middleware-stack
      '[cider.nrepl.middleware.apropos/wrap-apropos
        cider.nrepl.middleware.classpath/wrap-classpath
        cider.nrepl.middleware.complete/wrap-complete
        cider.nrepl.middleware.debug/wrap-debug
        cider.nrepl.middleware.enlighten/wrap-enlighten
        cider.nrepl.middleware.format/wrap-format
        cider.nrepl.middleware.info/wrap-info
        cider.nrepl.middleware.inspect/wrap-inspect
        cider.nrepl.middleware.macroexpand/wrap-macroexpand
        cider.nrepl.middleware.ns/wrap-ns
        cider.nrepl.middleware.out/wrap-out
        cider.nrepl.middleware.pprint/wrap-pprint
        cider.nrepl.middleware.pprint/wrap-pprint-fn
        cider.nrepl.middleware.refresh/wrap-refresh
        cider.nrepl.middleware.resource/wrap-resource
        cider.nrepl.middleware.stacktrace/wrap-stacktrace
        cider.nrepl.middleware.test/wrap-test
        cider.nrepl.middleware.trace/wrap-trace
        cider.nrepl.middleware.track-state/wrap-tracker
        cider.nrepl.middleware.undef/wrap-undef
        cider.nrepl.middleware.version/wrap-version])))

(defn start
  [{:keys [host port middleware]
    :or {host "localhost"
         port 0}
    :as config}]

  (let [middleware-stack
        (make-middleware-stack middleware)

        handler-fn
        ((apply comp (reverse middleware-stack)) server/unknown-op)]

    (server/start-server
      :bind host
      :port port
      :handler (fn [msg]
                 (log/debug "nrepl-receive" (pr-str (dissoc msg :transport :session)))
                 (handler-fn msg)))))

(comment
  (prn (server/default-handler)))

(defn stop [server]
  (server/stop-server server))
