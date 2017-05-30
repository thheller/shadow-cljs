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
            [cljs.compiler :as comp]
            [cljs.analyzer :as ana]
            [shadow.cljs.log :as log]
            [shadow.cljs.util :as util]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.output :as output]
            [shadow.cljs.ns-form :as ns-form])
  (:import (java.io StringReader BufferedReader)))

(comment
  (def repl-state
    {:current cljs-resource
     :repl-sources list-of-source-names-required-on-repl-init
     :repl-actions list-of-repl-actions-and-the-input-that-created-them}))

(defn repl-state? [x]
  (and (map? x) (::repl-state x)))

(defn as-client-resources [state source-names]
  (->> source-names
       (map (fn [src-name]
              (let [src (get-in state [:sources src-name])]
                (select-keys src [:type :name :js-name]))))
       (into [])))

(defn setup [state]
  {:pre [(util/compiler-state? state)]}
  (let [cljs-user-requires
        '[cljs.core cljs.repl]

        ;; FIXME: less hardcoded cljs.user
        cljs-user-src
        '(ns cljs.user
           (:require [cljs.repl :refer (doc find-doc source apropos pst dir)]))

        cljs-user
        {:type :cljs
         :ns 'cljs.user
         :name "cljs/user.cljs"
         :js-name "cljs.user.js"
         :input (atom [cljs-user-src])
         :provides #{'cljs.user}
         :require-order cljs-user-requires
         :requires (into #{} cljs-user-requires)
         :last-modified (System/currentTimeMillis)}

        ns-info
        (ns-form/parse cljs-user-src)

        {:keys [ns-info] :as cljs-user}
        (cljs/update-rc-from-ns state cljs-user ns-info)

        state
        (-> state
            (cljs/merge-resource cljs-user)
            (cljs/prepare-compile))

        repl-sources
        (cljs/get-deps-for-ns state 'cljs.user)

        ;; FIXME: proper ns-info for cljs.user, can use analyzer data because nothing was compiled yet

        repl-state
        {::repl-state true
         :current {:ns 'cljs.user
                   :name "cljs/user.cljs"
                   :ns-info ns-info}

         ;; the sources required to get the repl started
         :repl-sources
         (as-client-resources state repl-sources)

         ;; each input and the action it should execute
         ;; keeps the entire history of the repl
         :repl-actions []}]

    (assoc state :repl-state repl-state)
    ))

(defn prepare
  [state]
  {:pre [(util/compiler-state? state)]}

  ;; must compile an empty cljs.user to properly populate the ::ana/namespaces
  ;; could just manually set the values needed but I don't want to keep track what gets set
  ;; so just pretend there is actually an empty ns we never user
  (let [{:keys [repl-state] :as state}
        (setup state)

        {:keys [repl-sources]}
        repl-state]

    (-> state
        (cljs/prepare-compile)
        (cljs/do-compile-sources repl-sources)
        )))

(defn remove-quotes [quoted-form]
  (walk/prewalk
    (fn [form]
      (if (and (list? form)
               (= 'quote (first form)))
        (second form)
        form
        ))
    quoted-form))

(defn repl-require
  ([state read-result quoted-require]
   (repl-require state read-result quoted-require nil))
  ([{:keys [repl-state] :as state}
    read-result
    quoted-require
    reload-flag]
    ;; FIXME: verify quoted
   (let [current-ns
         (get-in repl-state [:current :ns])

         require
         (remove-quotes quoted-require)

         ;; parsing this twice to easily get a diff, could probably be simpler
         {:keys [requires]}
         (util/parse-ns-require-parts :requires {} [require])

         new-requires
         (into #{} (vals requires))

         ;; returns the updated ns-info
         ns-info
         (util/parse-ns-require-parts :requires (get-in repl-state [:current :ns-info]) [require])

         deps
         (cljs/get-deps-for-entries state new-requires)

         load-macros-and-set-ns-info
         (fn [state]
           (cljs/with-compiler-env state
             (let [full-ns-info
                   (-> ns-info
                       ;; FIXME: these work with env/*compiler* but shouldn't
                       (util/load-macros)
                       (util/infer-macro-require)
                       (util/infer-macro-use))]

               ;; FIXME: util/check-uses!
               (-> state
                   (cljs/swap-compiler-env! update-in [::ana/namespaces current-ns] merge full-ns-info)
                   (assoc-in [:repl-state :current :ns-info] full-ns-info))
               )))

         state
         (-> state
             (cljs/do-compile-sources deps)
             (load-macros-and-set-ns-info))

         action
         {:type :repl/require
          :sources (as-client-resources state deps)
          :reload reload-flag}]

     (update-in state [:repl-state :repl-actions] conj action)
     )))

(defn repl-load-file [{:keys [source-paths] :as state} read-result file-path]
  ;; FIXME: could clojure.core/load-file .clj files?

  (let [matched-paths
        (->> source-paths
             (vals)
             (filter :file)
             (filter
               (fn [{:keys [path] :as src-path}]
                 ;; without the / it will create 2 matches for
                 ;; something/src/clj
                 ;; something/src/cljs
                 (.startsWith file-path (str path "/"))))
             (into []))]

    (if (not= 1 (count matched-paths))
      ;; FIXME: configure it?
      (do (prn [:not-on-registered-source-path file-path matched-paths])
          state)

      ;; on registered source path
      ;; FIXME: could just reload if it exists? might be a recently created file, this covers both cases
      (let [{:keys [path] :as the-path}
            (first matched-paths)

            rc-name
            (subs file-path (-> path (count) (inc)))

            rc
            (cljs/make-fs-resource state path rc-name (io/file file-path))

            state
            (cljs/merge-resource state rc)

            deps
            (cljs/get-deps-for-src state rc-name)

            state
            (cljs/do-compile-sources state deps)

            action
            {:type :repl/require
             :sources (as-client-resources state deps)
             :reload :reload}]
        (update-in state [:repl-state :repl-actions] conj action)
        ))))

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
   (fn repl-in-ns
     [state read-result [q ns :as quoted-ns]]
     ;; quoted-ns is (quote the-ns)
     (if (nil? (get-in state [:provide->source ns]))
       ;; FIXME: create empty ns and switch to it
       (do (prn [:did-not-find ns])
           state)
       (let [{:keys [name ns-info]}
             (cljs/get-resource-for-provide state ns)

             set-ns-action
             {:type :repl/set-ns
              :ns ns
              :name name}]
         (-> state
             ;; FIXME: clojure in-ns doesn't actually do the ns setup
             ;; so we should merge an ns-info only if ns is already loaded
             ;; otherwise keep it empty
             (update-in [:repl-state :current] merge {:ns ns
                                                      :name name
                                                      :ns-info ns-info})
             (update-in [:repl-state :repl-actions] conj set-ns-action)
             ))))

   'repl-state
   (fn [state read-result]
     (prn (:repl-state state))
     state)

   'ns
   (fn [state read-result & args]
     (prn [:ns-not-yet-supported args])
     state)})

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
    ;; ('special-fn ...)
    ;; (require 'something)
    (and (list? form)
         (contains? repl-special-forms (first form)))
    (let [[special-fn & args]
          form

          handler
          (get repl-special-forms special-fn)]

      (apply handler state read-result args))

    ;; compile normally
    :else
    (-> (cljs/with-compiler-env state
          (let [repl-action
                (cljs/with-warnings state
                  ;; populated by comp/emit
                  (binding [comp/*source-map-data*
                            (atom {:source-map (sorted-map)
                                   :gen-col 0
                                   :gen-line 0})]

                    (let [ast
                          (cljs/analyze state (:current repl-state) form :expr)

                          js
                          (with-out-str
                            (comp/emit ast))

                          sm-json
                          (sm/encode
                            {"<eval>"
                             (:source-map @comp/*source-map-data*)}
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
         :features #{:cljs}}

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
  {:pre [(util/compiler-state? state)]}
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
  {:pre [(util/compiler-state? state)]}
  (let [reader
        (readers/input-stream-reader input-stream)

        {:keys [eof?] :as read-result}
        (read-one repl-state reader {})]
    (if eof?
      state
      (process-read-result state read-result))))


