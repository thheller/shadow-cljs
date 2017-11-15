(ns shadow.cljs.bootstrap.browser
  (:require [clojure.set :as set]
            [cljs.js :as cljs]
            [cognitect.transit :as transit]
            [shadow.js] ;; ensures that bootstrap namespaces can use js deps
            [shadow.cljs.bootstrap.env :as env]
            [goog.async.run]
            [goog.net.XhrIo :as xhr])
  (:import [goog.net BulkLoader]))


(defonce init-opts (atom {:path "/bootstrap"
                          :load-on-init []}))

(defn asset-path [& args]
  (apply str (:path @init-opts) args))

(defn compile-state-ref? [x]
  (and (instance? cljs.core/Atom x) (map? @x)))

(defn transit-read [txt]
  (let [r (transit/reader :json)]
    (transit/read r txt)))

(defn transit-load [path callback]
  (xhr/send
    path
    (fn [res]
      (this-as req
        (if-not (.isSuccess req)
          (throw (ex-info (str "failed to download boostrap file:" path " status:" (.getStatus req)) {:path path}))
          (let [data (-> (.getResponseText req)
                         (transit-read))]
            (callback data)
            ))))))

(defn script-eval
  "js/eval doesn't get optimized properly, this hack seems to do the trick"
  [code]
  (let [node (js/document.createElement "script")]
    (.appendChild node (js/document.createTextNode code))
    (js/document.body.appendChild node)
    (js/document.body.removeChild node)))

(defn execute-load! [compile-state-ref {:keys [type text uri ns provides] :as load-info}]
  #_ (js/console.log "load" type ns load-info)
  ;; quick hack for worker experiment, needs proper design
  (when-let [load-fn (:load @init-opts)]
    (load-fn load-info))
  (case type
    :analyzer
    (let [data (transit-read text)]
      (cljs/load-analysis-cache! compile-state-ref ns data))
    :js
    (do (swap! env/loaded-ref set/union provides)
        (swap! cljs/*loaded* set/union provides)
        (let [js (str text "\n//# sourceURL=" uri "\n")]
          (script-eval js)))))

(defn queue-task! [task]
  ;; FIXME: this is a very naive queue that does all pending tasks at once
  ;; should use something like window.requestIdleCallback that does as much work as
  ;; possible in the time it was given and then yield control back to the browser
  (js/goog.async.run task))

(defn load-namespaces
  "loads a set of namespaces, must be called after init"
  [compile-state-ref namespaces cb]
  {:pre [(compile-state-ref? compile-state-ref)
         (set? namespaces)
         (every? symbol? namespaces)
         (fn? cb)]}
  (let [deps-to-load-for-ns
        (env/find-deps namespaces)

        macro-deps
        (->> deps-to-load-for-ns
             (filter #(= :cljs (:type %)))
             (map :macro-requires)
             (reduce set/union)
             (map #(symbol (str % "$macros")))
             (into #{}))

        ;; second pass due to circular dependencies in macros
        deps-to-load-with-macros
        (env/find-deps (set/union namespaces macro-deps))

        compile-state
        @compile-state-ref

        things-already-loaded
        (->> deps-to-load-with-macros
             (filter #(set/superset? @env/loaded-ref (:provides %)))
             (map :provides)
             (reduce set/union))

        js-files-to-load
        (->> deps-to-load-with-macros
             (remove #(set/superset? @env/loaded-ref (:provides %)))
             (map (fn [{:keys [ns provides js-name]}]
                    {:type :js
                     :ns ns
                     :provides provides
                     :uri (asset-path js-name)})))

        analyzer-data-to-load
        (->> deps-to-load-with-macros
             (filter #(= :cljs (:type %)))
             ;; :dump-core still populates the cljs.core analyzer data with an empty map
             (filter #(nil? (get-in compile-state [:cljs.analyzer/namespaces (:ns %) :name])))
             (map (fn [{:keys [ns ana-name]}]
                    {:type :analyzer
                     :ns ns
                     :uri (asset-path ana-name)})))

        load-info
        (-> []
            (into js-files-to-load)
            (into analyzer-data-to-load))]

    #_ (js/console.log "going to load" load-info)

    ;; this is transfered to cljs/*loaded* here to delay it as much as possible
    ;; the JS may already be loaded but the analyzer data may be missing
    ;; this way cljs.js is forced to ask first
    (swap! cljs/*loaded* set/union things-already-loaded)

    ;; may not need to load anything sometimes?
    (if (empty? load-info)
      (cb {:lang :js :source ""})

      (let [uris
            (into [] (map :uri) load-info)

            loader
            (BulkLoader. (into-array uris))]

        (.listen loader js/goog.net.EventType.SUCCESS
          (fn [e]
            (let [texts (.getResponseTexts loader)]
              (doseq [load (map #(assoc %1 :text %2) load-info texts)]
                (queue-task! #(execute-load! compile-state-ref load)))

              #_ (queue-task! #(js/console.log "compile-state after load" @compile-state-ref))

              ;; callback with dummy so cljs.js doesn't attempt to load deps all over again
              (queue-task! #(cb {:lang :js :source ""}))
              )))

        (.load loader)))
    ))

(defn load
  ":load fn for cljs.js, must be passed the compile-state as first arg
   eg. :load (partial boot/load compile-state-ref)"
  [compile-state-ref {:keys [name path macros] :as rc} cb]
  {:pre [(compile-state-ref? compile-state-ref)
         (symbol? name)
         (fn? cb)]}
  (let [ns (if macros
             (symbol (str name "$macros"))
             name)]
    (or (get-in @compile-state-ref [:cljs.analyzer/namespaces ns])
        (env/get-ns-info ns))
    (load-namespaces compile-state-ref #{ns} cb)))

(defn fix-provide-conflict! []
  ;; since cljs.js unconditionally does a goog.require("cljs.core$macros")
  ;; the compile pretended to provide this but didn't
  ;; need to remove that before we load it, otherwise it would goog.provide conflict
  ;; FIXME: should test if actually empty, might delete something accidentally?
  (js-delete js/cljs "core$macros"))

(defn init
  "initializes the bootstrapped compiler by loading the dependency index
   and loading cljs.core + macros (and namespaces specified in :load-on-init)"
  [compile-state-ref {:keys [load-on-init] :as opts} init-cb]
  {:pre [(compile-state-ref? compile-state-ref)
         (map? opts)
         (fn? init-cb)
         (string? (:path opts))]}
  ;; FIXME: add goog-define to path

  (reset! init-opts opts)

  (if @env/index-ref
    (init-cb)
    (do (fix-provide-conflict!)
        (transit-load (asset-path "/index.transit.json")
          (fn [data]
            ;; pretend that all excluded macro namespaces are loaded
            ;; so CLJS doesn't request them
            ;; the macro are never available so any code trying to use them will fail
            (let [{:keys [exclude] :as idx} (env/build-index data)]
              (swap! cljs/*loaded* set/union (into #{} (map #(symbol (str % "$macros"))) exclude)))

            (load-namespaces
              compile-state-ref
              (into '#{cljs.core cljs.core$macros} load-on-init)
              init-cb))))))
