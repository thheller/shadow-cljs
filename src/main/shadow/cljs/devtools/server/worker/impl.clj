(ns shadow.cljs.devtools.server.worker.impl
  (:refer-clojure :exclude (compile))
  (:require [cljs.compiler :as cljs-comp]
            [clojure.core.async :as async :refer (go >! <! >!! <!! alt!)]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.devtools.server.util :as util]
            [clojure.string :as str]
            [clojure.set :as set]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            ))

(defn proc? [x]
  (and (map? x) (::proc x)))

(defn worker-state? [x]
  (and (map? x) (::worker-state x)))

(defn >!!output [{:keys [system-bus] :as worker-state} msg]
  {:pre [(map? msg)
         (:type msg)]}

  (let [output (get-in worker-state [:channels :output])]
    (>!! output msg)
    worker-state))

(defn update-status!
  [{:keys [status-ref] :as worker-state} status-id status-info]
  (vreset! status-ref {:status status-id
                       :info status-info})
  worker-state)

(defn build-msg
  [worker-state msg]
  (>!!output worker-state
    {:type :build-message
     :msg msg}))

(defn repl-error [e]
  {:type :repl/error
   :message (.getMessage e)
   :data (ex-data e)
   :causes
   (loop [e (.getCause e)
          causes []]
     (if e
       (recur (.getCause e) (conj causes (.getMessage e)))
       causes))})

(defn build-failure
  [{:keys [build-config] :as worker-state} e]
  (update-status! worker-state :error {:error e})

  (>!!output worker-state
    {:type :build-failure
     :build-config build-config
     :e e}))

(defn build-configure
  "configure the build according to build-config in state"
  [{:keys [build-config proc-id http executor] :as worker-state}]

  (>!!output worker-state {:type :build-configure
                           :build-config build-config})

  (update-status! worker-state :configure {})

  (try
    ;; FIXME: allow the target-fn read-only access to worker-state? not just worker-info?
    ;; it may want to put things on the websocket?
    (let [worker-info
          {:proc-id proc-id
           :host (:host http)
           :port (:port http)}

          compiler-state
          (-> (cljs/init-state)
              (assoc
                :executor executor
                :logger (util/async-logger (-> worker-state :channels :output))
                :worker-info worker-info)
              (comp/init :dev build-config))]

      (update-status! worker-state :configured {})

      ;; FIXME: should maybe cleanup old :compiler-state if there is one (re-configure)
      (assoc worker-state :compiler-state compiler-state))
    (catch Exception e
      (-> worker-state
          (dissoc :compiler-state) ;; just in case there is an old one
          (build-failure e)))))

(defn build-compile
  [{:keys [compiler-state build-config] :as worker-state}]
  (>!!output worker-state {:type :build-start
                           :build-config build-config})

  ;; this may be nil if configure failed, just silently do nothing for now
  (if (nil? compiler-state)
    worker-state
    (try
      (update-status! worker-state :started {})

      (let [{::comp/keys [build-info] :as compiler-state}
            (-> compiler-state
                (comp/compile)
                (comp/flush))]

        (>!!output worker-state
          {:type
           :build-complete
           :build-config
           build-config
           :info
           build-info})

        (update-status! worker-state :complete {:build-info build-info})

        (assoc worker-state :compiler-state compiler-state))
      (catch Exception e
        (build-failure worker-state e)))))

(defmulti do-proc-control
  (fn [worker-state {:keys [type] :as msg}]
    type))

(defmethod do-proc-control :sync!
  [worker-state {:keys [chan] :as msg}]
  (async/close! chan)
  worker-state)

(defmethod do-proc-control :eval-start
  [worker-state {:keys [id eval-out client-out]}]
  (>!! eval-out {:type :repl/init
                 :repl-state (-> worker-state :compiler-state :repl-state)})

  (>!!output worker-state {:type :repl/eval-start :id id})
  (update worker-state :eval-clients assoc id {:id id
                                               :eval-out eval-out
                                               :client-out client-out}))


(defmethod do-proc-control :eval-stop
  [worker-state {:keys [id]}]
  (>!!output worker-state {:type :repl/eval-stop :id id})
  (update worker-state :eval-clients dissoc id))

(defmethod do-proc-control :client-start
  [worker-state {:keys [id in]}]
  (>!!output worker-state {:type :repl/client-start :id id})
  (update worker-state :repl-clients assoc id {:id id :in in}))

(defmethod do-proc-control :client-stop
  [worker-state {:keys [id]}]
  (>!!output worker-state {:type :repl/client-stop :id id})
  (update worker-state :repl-clients dissoc id))

(defmethod do-proc-control :start-autobuild
  [{:keys [build-config autobuild] :as worker-state} msg]
  (if autobuild
    ;; do nothing if already in auto mode
    worker-state
    ;; compile immediately, autobuild is then checked later
    (-> worker-state
        (build-configure)
        (build-compile)
        (assoc :autobuild true)
        )))

(defmethod do-proc-control :stop-autobuild
  [worker-state msg]
  (assoc worker-state :autobuild false))

(defmethod do-proc-control :compile
  [worker-state {:keys [reply-to] :as msg}]
  (let [result
        (-> worker-state
            (build-configure)
            (build-compile))]

    (when reply-to
      (>!! reply-to :done))

    result
    ))

(defmethod do-proc-control :stop-autobuild
  [worker-state msg]
  (assoc worker-state :autobuild false))

(defmethod do-proc-control :repl-eval
  [{:keys [compiler-state eval-clients pending-results] :as worker-state}
   {:keys [result-chan input] :as msg}]
  (let [eval-count (count eval-clients)]

    (cond
      (nil? compiler-state)
      (do (>!! result-chan {:type :repl/illegal-state})
          worker-state)

      (> eval-count 1)
      (do (>!! result-chan {:type :repl/too-many-eval-clients :count eval-count})
          worker-state)

      (zero? eval-count)
      (do (>!! result-chan {:type :repl/no-eval-target})
          worker-state)

      :else
      (try
        (let [{:keys [eval-out] :as eval-client}
              (first (vals eval-clients))

              start-idx
              (count (get-in compiler-state [:repl-state :repl-actions]))

              {:keys [repl-state] :as compiler-state}
              (if (string? input)
                (repl/process-input compiler-state input)
                (repl/process-read-result compiler-state input))

              new-actions
              (subvec (:repl-actions repl-state) start-idx)

              pending-results
              (reduce
                (fn [pending [idx action]]
                  (let [idx (+ idx start-idx)]
                    (assoc pending idx (assoc action :reply-to result-chan))))
                pending-results
                (map-indexed vector new-actions))]

          (doseq [[idx action] (map-indexed vector new-actions)
                  :let [idx (+ idx start-idx)
                        action (assoc action :id idx)]]
            (>!!output worker-state {:type :repl/action
                                     :action action})
            (>!! eval-out action))

          (assoc worker-state
            :compiler-state compiler-state
            :pending-results pending-results))

        (catch Exception e
          (let [msg (repl-error e)]
            (>!! result-chan msg)
            (>!!output worker-state msg))
          worker-state)))))

(defmethod do-proc-control :repl/result
  [{:keys [pending-results] :as worker-state}
   {:keys [result] :as msg}]

  ;; forward everything to out as well
  (>!!output worker-state msg)

  (let [{:keys [id]}
        result

        {:keys [reply-to] :as waiting}
        (get pending-results id)]

    (if (nil? waiting)
      worker-state

      ;; FIXME: should the reply include the msg that triggered it?
      (do (>!! reply-to result)
          (update worker-state :pending-results dissoc id))
      )))

;; FIXME: this always modifies the compiler-state for any cljs/cljc file
;; this means it always triggers a compile even if the build is not affected by it
;; it still won't compile the files but it will trigger everything else
;; should probably be smarter about this somehow so we can avoid some work
;; still need to merge updates in case we use them later though
(defn merge-fs-update-cljs [compiler-state {:keys [event name] :as fs-update}]
  (let [rc (get-in compiler-state [:sources name])]

    ;; skip the update if a name exists but points to a different file
    (if (and rc (not= (:file rc) (:file fs-update)))
      (do (prn [:updated-file-does-not-match fs-update])
          compiler-state)
      (cond
        (= :del event)
        (cljs/unmerge-resource compiler-state name)

        (not rc) ;; :new or :mod can cause this
        (let [new-rc (cljs/make-fs-resource compiler-state (.getCanonicalPath (:dir fs-update)) name (:file fs-update))]
          (cljs/merge-resource compiler-state new-rc))

        ;; FIXME: could get here with :new but the rc already existing
        (= :mod event)
        (let [dependents (cljs/find-dependent-names compiler-state (:ns rc))]
          (reduce cljs/reset-resource-by-name compiler-state (conj dependents name)))

        :else
        compiler-state
        ))))

(defn clj-name->ns [name]
  (-> name
      (str/replace #"\.clj(c)?$" "")
      (str/replace #"_" "-")
      (str/replace #"[/\\]" ".")
      (symbol)))

(defn merge-fs-update-clj
  [{:keys [build-sources] :as compiler-state}
   {:keys [event name] :as fs-update}]
  (if (not= :mod event)
    compiler-state
    ;; only do work if macro ns is modified
    ;; new will be required by the compile
    ;; del doesn't matter, we don't track CLJ files
    (let [macro-ns
          (clj-name->ns name)

          macros-used-by-build
          (->> build-sources
               (map #(get-in compiler-state [:sources % :macro-namespaces]))
               (reduce set/union))]

      (if-not (contains? macros-used-by-build macro-ns)
        compiler-state
        (do (require macro-ns :reload)
            (cljs/reset-resources-using-macro compiler-state macro-ns)
            )))))

(defn merge-fs-update [compiler-state {:keys [ext] :as fs-update}]
  (-> compiler-state
      (cond->
        (contains? #{"cljs" "cljc" "js"} ext)
        (merge-fs-update-cljs fs-update)
        (contains? #{"clj" "cljc"} ext)
        (merge-fs-update-clj fs-update))))

(defn do-cljs-watch
  [{:keys [autobuild compiler-state] :as worker-state}
   {:keys [updates] :as msg}]
  (if-not autobuild
    worker-state
    (let [next-state (reduce merge-fs-update compiler-state updates)]
      (if (identical? next-state compiler-state)
        worker-state
        ;; only recompile if something actually affected us
        (-> worker-state
            (assoc :compiler-state next-state)
            (build-compile))))))

(defn do-config-watch
  [{:keys [autobuild] :as worker-state} {:keys [config] :as msg}]
  (-> worker-state
      (assoc :build-config config)
      (cond->
        autobuild
        (-> (build-configure)
            (build-compile)))))

(defn repl-eval-connect
  [{:keys [proc-stop proc-control] :as proc} client-id client-out]
  {:pre [(proc? proc)]}
  ;; result-chan
  ;; creating a new chan here instead of just handing out repl-result
  ;; closing it is currently the only way to a eval-client can signal a disconnect
  ;; we will however just pipe messages through as we have nothing useful to do with them

  ;; eval-out
  ;; FIXME: just piping through but could just talk to client-out directly?
  (let [result-chan
        (async/chan)

        eval-out
        (async/chan)]

    (go (>! proc-control {:type :eval-start
                          :id client-id
                          :eval-out eval-out})

        (loop []
          (alt!
            proc-stop
            ([_] nil)

            result-chan
            ([msg]
              (when-not (nil? msg)
                (>! proc-control {:type :repl/result
                                  :result msg})
                (recur)))

            eval-out
            ([msg]
              (when-not (nil? msg)
                (>! client-out msg)
                (recur)))
            ))

        (>! proc-control {:type :eval-stop
                          :id client-id})

        (async/close! eval-out)
        (async/close! result-chan))

    result-chan))

;; FIXME: remove these ... just make worker do the stuff directly, this is nonsense

(defn watch
  [{:keys [output-mult] :as proc} log-chan close?]
  {:pre [(proc? proc)]}
  (async/tap output-mult log-chan close?)
  proc)

(defn compile
  [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (>!! proc-control {:type :compile :reply-to nil})
  proc)

(defn compile!
  [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (let [reply-to (async/chan)]
    (>!! proc-control {:type :compile :reply-to reply-to})
    (<!! reply-to)))

(defn start-autobuild
  [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (>!! proc-control {:type :start-autobuild})
  proc)

(defn stop-autobuild
  [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (>!! proc-control {:type :stop-autobuild})
  proc)

(defn sync! [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (let [chan (async/chan)]
    (>!! proc-control {:type :sync! :chan chan})
    (<!! chan))
  proc)


