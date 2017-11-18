(ns shadow.cljs.repl
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.repl :as repl]
            [clojure.java.io :as io]
            [clojure.tools.reader.reader-types :as readers]
            [clojure.tools.reader :as reader]
            [cljs.env :as env]
            [cljs.source-map :as sm]
            [cljs.tagged-literals :as tags]
            [cljs.compiler :as cljc-comp]
            [cljs.analyzer :as ana]
            [shadow.build.log :as log]
            [shadow.cljs.util :as util]
            [shadow.build.api :as build-api]
            [shadow.build.output :as output]
            [shadow.build.ns-form :as ns-form]
            [shadow.build.cljs-bridge :as cljs-bridge]
            [shadow.build.macros :as macros]
            [shadow.build.compiler :as comp]
            [shadow.build.data :as data]
            [shadow.build.resolve :as res]
            [shadow.build.classpath :as classpath])
  (:import (java.io StringReader BufferedReader)))

(comment
  (def repl-state
    {:current cljs-resource
     :repl-sources list-of-source-names-required-on-repl-init
     :repl-actions list-of-repl-actions-and-the-input-that-created-them}))

(defn repl-state? [x]
  (and (map? x) (::repl-state x)))

(defn make-repl-resource* [ns ns-form]
  (let [resource-name
        (util/ns->cljs-filename ns)

        output-name
        (util/flat-js-name resource-name)]

    {:resource-id [::resource resource-name]
     :resource-name resource-name
     :output-name output-name
     :type :cljs
     :source [ns-form]
     :virtual true}))

(defn make-repl-resource [{:keys [compiler-env] :as state} [_ ns :as ns-form]]
  ;; ns in the REPL is additive, if we have a resource loaded use that otherwise define pseudo rc
  (let [rc-id
        (get-in state [:sym->id ns])

        rc
        (if (nil? rc-id)
          (make-repl-resource* ns ns-form)
          (get-in state [:sources rc-id]))

        current-ns-info
        (-> (get-in compiler-env [::ana/namespaces ns])
            (dissoc :defs))

        {:keys [name requires deps] :as ns-info}
        (if current-ns-info
          (ns-form/parse current-ns-info ns-form)
          (ns-form/parse ns-form))]

    (assoc rc
      :ns name
      :ns-info ns-info
      :provides #{name}
      :requires (into #{} (vals requires))
      :macro-requires
      (-> #{}
          (into (-> ns-info :require-macros vals))
          (into (-> ns-info :use-macros vals)))
      :deps deps
      :last-modified (System/currentTimeMillis)
      :cache-key (System/currentTimeMillis)
      )))

(defn setup [state]
  {:pre [(build-api/build-state? state)]}
  (let [;; FIXME: less hardcoded cljs.user
        cljs-user-ns
        '(ns cljs.user
           (:require [cljs.repl :refer (doc find-doc source apropos pst dir)]))

        cljs-user
        (make-repl-resource state cljs-user-ns)

        [repl-sources state]
        (-> state
            (data/add-source cljs-user)
            (build-api/resolve-entries '[cljs.user]))

        ;; FIXME: proper ns-info for cljs.user, can use analyzer data because nothing was compiled yet

        repl-state
        {::repl-state true
         :current cljs-user

         :reader-features
         (data/get-reader-features state)

         ;; the sources required to get the repl started
         :repl-sources
         repl-sources

         ;; each input and the action it should execute
         ;; keeps the entire history of the repl
         :repl-actions []}]

    (assoc state :repl-state repl-state)
    ))

(defn prepare
  [state]
  {:pre [(build-api/build-state? state)]}

  ;; must compile an empty cljs.user to properly populate the ::ana/namespaces
  ;; could just manually set the values needed but I don't want to keep track what gets set
  ;; so just pretend there is actually an empty ns we never user
  (let [{:keys [repl-state] :as state}
        (setup state)

        {:keys [repl-sources]}
        repl-state]

    (build-api/compile-sources state repl-sources)))

(defn load-macros-and-set-ns-info
  "modifies the repl and analyzer state to reflect the updated ns changes done by require in the REPL"
  [state {:keys [name] :as new-ns-info}]
  (cljs-bridge/with-compiler-env state
    (let [full-ns-info
          (-> new-ns-info
              (macros/load-macros)
              (macros/infer-macro-require)
              (macros/infer-macro-use))]

      ;; FIXME: util/check-uses!
      (-> state
          (cljs-bridge/swap-compiler-env! update-in [::ana/namespaces name] merge full-ns-info)
          (assoc-in [:repl-state :current :ns-info] full-ns-info))
      )))

(defn repl-require
  [{:keys [repl-state] :as state} read-result require-form]
  (let [{:keys [current]}
        repl-state

        ns-info
        (:ns-info current)

        new-ns-info
        (-> (dissoc ns-info :flags)
            (ns-form/merge-repl-require require-form))

        known-deps
        (set (concat
               (:deps ns-info)
               ;; :deps already had the string aliased
               ;; need to remember which strings we aliased
               ;; so we don't reimport the whole thing again
               (keys (:js-deps ns-info))))

        new-deps
        (->> (:deps new-ns-info)
             (remove known-deps)
             (into []))

        [new-sources state]
        (res/resolve-repl state (:name new-ns-info) new-deps)

        ;; can only rewrite after resolving since that discovers what needs to be rewritten
        ;; which may have created a new alias for a string
        new-ns-info
        (-> new-ns-info
            (ns-form/rewrite-ns-aliases state)
            (ns-form/rewrite-js-deps state))

        state
        (-> state
            (build-api/compile-sources new-sources)
            (load-macros-and-set-ns-info new-ns-info))

        action
        {:type :repl/require
         :sources new-sources
         :flags (:flags new-ns-info)}]

    (output/flush-sources state new-sources)

    (update-in state [:repl-state :repl-actions] conj action)
    ))

(defn repl-load-file*
  [{:keys [source-paths classpath] :as state} {:keys [file-path source]}]
  ;; FIXME: could clojure.core/load-file .clj files?

  (let [matched-paths
        (->> (classpath/get-classpath-entries classpath)
             (filter #(.isDirectory %))
             (map #(.getAbsolutePath %))
             (filter
               (fn [path]
                 ;; without the / it will create 2 matches for
                 ;; something/src/clj
                 ;; something/src/cljs
                 (.startsWith file-path (str path "/"))))
             (into []))]

    (cond
      (not (util/is-cljs-file? file-path))
      (throw (ex-info "can only load .cljs and .cljc files"
               {:file-path file-path}))

      (not (seq matched-paths))
      (throw (ex-info "file not on classpath"
               {:file-path file-path}))

      (not= 1 (count matched-paths))
      (throw (ex-info "file matched more than one classpath entry?"
               {:file-path file-path
                :matches matched-paths}))

      ;; FIXME: could make the requirement for the file to be on the classpath optional
      :else
      (let [path
            (first matched-paths)

            rc-name
            (subs file-path (-> path (count) (inc)))

            {:keys [ns] :as rc}
            (-> (classpath/make-fs-resource (io/file file-path) rc-name)
                (assoc :type :cljs)
                (cond->
                  (seq source)
                  (-> (assoc :source source)
                      ;; make sure we are not using cache when loading file for REPL with source
                      (update :cache-key conj :repl (System/currentTimeMillis))))
                (classpath/inspect-cljs)
                (classpath/set-output-name))

            [deps-sources state]
            (-> state
                (data/overwrite-source rc)
                (build-api/resolve-entries [ns]))

            {:keys [build-sources] :as state}
            (build-api/compile-sources state deps-sources)

            action
            {:type :repl/require
             :sources build-sources
             :reload :reload}]

        (output/flush-sources state build-sources)
        (update-in state [:repl-state :repl-actions] conj action)
        ))))

(defn repl-load-file
  [state read-result [_ file-path :as form]]
  (repl-load-file* state {:file-path file-path}))

(defn repl-ns [{:keys [compiler-env] :as state} read-result [_ ns :as form]]
  (let [{:keys [ns deps ns-info] :as ns-rc}
        (make-repl-resource state form)

        [dep-sources state]
        (-> state
            (data/overwrite-source ns-rc)
            (res/resolve-repl ns deps))

        {:keys [deps] :as ns-info}
        (-> ns-info
            (ns-form/rewrite-ns-aliases state)
            (ns-form/rewrite-js-deps state))

        state
        (cljs-bridge/with-compiler-env state
          (comp/post-analyze-ns ns-info state true)
          state)

        {:keys [build-sources] :as state}
        (build-api/compile-sources state dep-sources)

        new-repl-current
        (assoc ns-rc :ns-info ns-info)

        action
        {:type :repl/require
         :sources build-sources}

        ns-provide
        {:type :repl/invoke
         :name "<eval>"
         :js (with-out-str
               (comp/shadow-emit state (assoc ns-info :op :ns)))}]

    (output/flush-sources state build-sources)

    (-> state
        (assoc-in [:repl-state :current] new-repl-current)
        (update-in [:repl-state :repl-actions] conj action)
        (update-in [:repl-state :repl-actions] conj ns-provide))
    ))

(defn repl-in-ns
  [state read-result [_ quoted-ns :as form]]
  ;; form is (in-ns (quote the-ns))
  (let [[q ns] quoted-ns]
    (if (nil? (get-in state [:sym->id ns]))
      ;; FIXME: create empty ns and switch to it
      (do (prn [:did-not-find ns])
          state)
      (let [{:keys [name ns-info] :as rc}
            (data/get-source-by-provide state ns)

            set-ns-action
            {:type :repl/set-ns
             :ns ns
             :name name}]
        (-> state
            ;; FIXME: do we need to ensure that the ns is compiled?
            (assoc-in [:repl-state :current] rc)
            (update-in [:repl-state :repl-actions] conj set-ns-action)
            )))))

(def repl-special-forms
  {'require
   repl-require

   'cljs.core/require
   repl-require

   'load-file
   repl-load-file

   'cljs.core/load-file
   repl-load-file

   'in-ns
   repl-in-ns

   'ns
   repl-ns
   })

(defmethod log/event->str ::special-fn-error
  [{:keys [source special-fn error]}]
  (str special-fn " failed. " (str error)))

(defn process-read-result
  [{:keys [repl-state] :as state}
   {:keys [form source] :as read-result}]

  ;; cljs.env/default-compiler-env always populates 'cljs.user for some reason
  ;; we can't work with that as we need the analyzed version
  (let [x (get-in state [:compiler-env ::ana/namespaces 'cljs.user])]
    (when (= x {:name 'cljs.user})
      (throw (ex-info "missing cljs.user, repl not properly configured (must have analyzed cljs.user by now)" {}))))

  (cond
    ;; (special-fn ...) eg. (require 'something)
    (and (list? form)
         (contains? repl-special-forms (first form)))
    (let [[special-fn & args]
          form

          handler
          (get repl-special-forms special-fn)]

      (handler state read-result form))

    ;; compile normally
    :else
    (-> (cljs-bridge/with-compiler-env state
          (let [repl-action
                (comp/with-warnings state
                  ;; populated by comp/emit
                  (binding [cljc-comp/*source-map-data*
                            (atom {:source-map (sorted-map)
                                   :gen-col 0
                                   :gen-line 0})

                            ]

                    (let [ast
                          (comp/analyze state (:current repl-state) form true)

                          js
                          (with-out-str
                            (comp/shadow-emit state ast))

                          sm-json
                          (sm/encode
                            {"<eval>"
                             (:source-map @cljc-comp/*source-map-data*)}
                            {:source-map-pretty-print true
                             :file "<eval>"
                             :lines
                             (count (line-seq (BufferedReader. (StringReader. source))))
                             :sources-content
                             [source]})]

                      {:type :repl/invoke
                       :name "<eval>"
                       :js js
                       :source source
                       :source-map-json sm-json})))]
            (update-in state [:repl-state :repl-actions] conj repl-action)
            )))))

(defn read-one
  [repl-state reader {:keys [filename] :or {filename "repl-input.cljs"} :as opts}]
  {:pre [(repl-state? repl-state)]}
  (let [eof-sentinel
        (Object.)

        opts
        {:eof eof-sentinel
         :read-cond :allow
         :features (:reader-features repl-state)}

        in
        (readers/source-logging-push-back-reader
          reader ;; (PushbackReader. reader (object-array buf-len) buf-len buf-len)
          1
          filename)

        {:keys [ns ns-info] :as repl-rc}
        (:current repl-state)

        _ (assert (symbol? ns))

        form
        (binding [*ns*
                  (create-ns ns)

                  ana/*cljs-ns*
                  ns

                  ana/*cljs-file*
                  name

                  reader/*data-readers*
                  tags/*cljs-data-readers*

                  reader/*alias-map*
                  (merge reader/*alias-map*
                         (:requires ns-info)
                         (:require-macros ns-info))]

          (reader/read opts in))

        eof?
        (identical? form eof-sentinel)]

    (-> {:eof? eof?}
        (cond->
          (not eof?)
          (assoc :form form
            :source
            ;; FIXME: poking at the internals of SourceLoggingPushbackReader
            ;; not using (-> form meta :source) which log-source provides
            ;; since there are things that do not support IMeta, still want the source though
            (-> @(.-source-log-frames in)
                (:buffer)
                (str)))))
    ))

(defn process-input
  "processes a string of forms, may read multiple forms"
  [state ^String repl-input]
  {:pre [(build-api/build-state? state)]}
  (let [reader
        (readers/string-reader repl-input)]

    (loop [{:keys [repl-state] :as state} state]

      (let [{:keys [eof?] :as read-result}
            (read-one repl-state reader {})]

        (if eof?
          state
          (recur (process-read-result state read-result))))
      )))

(defn process-input-stream
  "reads one form of the input stream and calls process-form"
  [{:keys [repl-state] :as state} input-stream]
  {:pre [(build-api/build-state? state)]}
  (let [reader
        (readers/input-stream-reader input-stream)

        {:keys [eof?] :as read-result}
        (read-one repl-state reader {})]
    (if eof?
      state
      (process-read-result state read-result))))


