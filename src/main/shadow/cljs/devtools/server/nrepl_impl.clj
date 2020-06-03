(ns shadow.cljs.devtools.server.nrepl-impl
  (:refer-clojure :exclude (send))
  (:require
    [cider.piggieback :as piggieback]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server.nrepl-bridge :as nrepl-bridge]))

(def ^:dynamic *repl-state* nil)

;; this runs in an eval context and therefore cannot modify session directly
;; must use set! as they are captured AFTER eval finished and would overwrite
;; what we did in here. reading is fine though.
(defn repl-init
  [msg
   build-id
   {:keys [init-ns]
    :or {init-ns 'cljs.user}
    :as opts}]

  ;; must keep the least amount of state here since it will be shared by clones
  (set! *repl-state*
    {:build-id build-id
     :opts opts
     :clj-ns *ns*})

  ;; doing this to make cider prompt not show "user" as prompt after calling this
  (set! *ns* (create-ns init-ns))

  ;; make tools happy, we do not use it
  ;; its private for some reason so we can't set! it directly
  (let [pvar #'piggieback/*cljs-compiler-env*]
    (when (thread-bound? pvar)
      (.set pvar
        (reify
          clojure.lang.IDeref
          (deref [_]
            (some->
              (api/get-worker build-id)
              :state-ref
              deref
              :build-state
              :compiler-env)))))))

(defn reset-session [session]
  (let [{:keys [clj-ns] :as repl-state} (get @session #'*repl-state*)]
    ;; (tap> [:reset-session repl-state session])
    (swap! session assoc
      #'*ns* clj-ns
      #'*repl-state* nil
      #'cider.piggieback/*cljs-compiler-env* nil)))

(defn set-build-id [{:keys [op session] :as msg}]
  ;; re-create this for every eval so we know exactly which msg started a REPL
  ;; only eval messages can "upgrade" a REPL
  (when (= "eval" op)
    (swap! session assoc #'api/*nrepl-init* #(repl-init msg %1 %2)))

  ;; DO NOT PUT ATOM'S INTO THE SESSION!
  ;; clone will result in two session using the same atom
  ;; so any change to the atom will affect all session clones
  (when-not (contains? @session #'*repl-state*)
    (swap! session assoc #'*repl-state* nil))

  (let [{:keys [build-id] :as repl-state} (get @session #'*repl-state*)]
    (if-not build-id
      msg
      (assoc msg
        ::nrepl-bridge/build-id build-id
        ::nrepl-bridge/reset-session #(reset-session session)
        ::nrepl-bridge/repl-state repl-state
        ;; keeping these since cider uses it in some middleware
        ;; not keeping worker reference in repl-state since that would leak
        ;; since we can't cleanup sessions reliably
        ::worker (api/get-worker build-id)
        ::build-id build-id))))

(defn shadow-init-ns!
  [{:keys [session] :as msg}]
  (let [config
        (config/load-cljs-edn)

        init-ns
        (or (get-in config [:nrepl :init-ns])
            (get-in config [:repl :init-ns])
            'shadow.user)]

    (try
      (require init-ns)
      (swap! session assoc #'*ns* (find-ns init-ns))
      (catch Exception e
        (log/warn-ex e ::init-ns-ex {:init-ns init-ns})))))

(defn handle [{:keys [session op] :as msg} next]
  (let [{::nrepl-bridge/keys [build-id] :as msg} (set-build-id msg)]
    ;; (tap> [:nrepl-handle msg])
    (log/debug ::handle {:session-id (-> session meta :id)
                         :msg-op op
                         :build-id build-id
                         :code (when-some [code (:code msg)]
                                 (subs code 0 (min (count code) 100)))})
    (cond
      (and build-id (= op "eval"))
      (nrepl-bridge/nrepl-in
        (:nrepl-bridge (api/get-runtime!))
        msg)

      (and build-id (= op "load-file"))
      (nrepl-bridge/nrepl-in
        (:nrepl-bridge (api/get-runtime!))
        msg)

      :else
      (next msg))))