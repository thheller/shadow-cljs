(ns shadow.build.targets.shared
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [cljs.compiler :as cljs-comp]
            [shadow.build.api :as cljs]
            [shadow.build.data :as data]
            [shadow.build.modules :as modules]
            [clojure.data.json :as json])
  (:import [java.net InetAddress]))

(defn unquoted-qualified-symbol? [sym]
  (and (qualified-symbol? sym)
       (not (str/starts-with? (str sym) "'"))))

(defn unquoted-simple-symbol? [sym]
  (and (simple-symbol? sym)
       (not (str/starts-with? (str sym) "'"))))

(defn non-empty-string? [x]
  (and (string? x)
       (not (str/blank? x))))

(s/def ::output-dir non-empty-string?)

(s/def ::output-to non-empty-string?)

(s/def ::http-root non-empty-string?)

(s/def ::http-port pos-int?)

(s/def ::http-handler unquoted-qualified-symbol?)

(s/def ::enabled boolean?)

(s/def ::autoload boolean?)

(s/def ::after-load unquoted-qualified-symbol?)

(s/def ::before-load unquoted-qualified-symbol?)

(s/def ::before-load-async unquoted-qualified-symbol?)

(s/def ::build-notify unquoted-qualified-symbol?)

(s/def ::devtools-url non-empty-string?)

(s/def ::use-document-host boolean?)

(s/def ::devtools
  (s/keys
    :opt-un
    [::http-root
     ::http-port
     ::http-handler
     ::enabled
     ::autoload
     ::after-load
     ::before-load
     ::before-load-async
     ::build-notify
     ::use-document-host
     ::devtools-url]))

(s/def ::entry
  (s/or :sym unquoted-simple-symbol?
    :str string?))

(s/def ::init-fn unquoted-qualified-symbol?)

(s/def ::entries
  (s/coll-of ::entry :kind vector?))

(s/def ::output-dir non-empty-string?)
(s/def ::asset-path non-empty-string?)

;; OLD, only allowed in config so they don't break.
;; rewritten to :output-dir and :asset-path
(s/def ::public-dir non-empty-string?)
(s/def ::public-path non-empty-string?)
;; ---

;; will just be added as is (useful for comments, license, ...)
(s/def ::prepend string?)
(s/def ::append string?)

;; these go through closure optimized, should be valid js
(s/def ::prepend-js string?)
(s/def ::append-js string?)

(s/def ::depends-on
  (s/coll-of keyword? :kind set?))

(s/def ::module
  (s/and
    ;; {init-fn foo.bar/init} should fail
    (s/map-of keyword? any?)
    (s/keys
      :opt-un
      [::entries
       ::entry
       ::init-fn
       ::depends-on
       ::prepend
       ::prepend-js
       ::append-js
       ::append])))

(s/def ::modules
  (s/map-of
    simple-keyword?
    ::module
    :min-count 1))

(s/def ::chunks
  (s/map-of
    simple-keyword?
    ::module
    :min-count 1))

(defn prepend [tail head]
  {:pre [(vector? head)]}
  (into head tail))

(defn hud-defines [hud]
  (let [all-off
        '{shadow.cljs.devtools.client.hud/show-warnings false
          shadow.cljs.devtools.client.hud/show-progress false
          shadow.cljs.devtools.client.hud/show-errors false}]

    (cond
      (true? hud)
      {} ;; defaults to all true, no need to set them

      (false? hud)
      all-off

      (set? hud)
      (reduce
        (fn [m key]
          (assoc m
            (symbol "shadow.cljs.devtools.client.hud" (str "show-" (name key)))
            true))
        all-off
        hud))))

(comment
  (hud-defines #{:progress}))

(defn repl-defines
  [{:keys [worker-info] :as state} build-config]
  (let [{:keys [proc-id ssl host port]}
        worker-info

        {:keys [build-id]}
        build-config

        {:keys [ignore-warnings
                devtools-url
                before-load
                before-load-async
                after-load
                autoload
                use-document-host
                reload-strategy
                repl-pprint
                log-style]
         :as devtools}
        (:devtools build-config)]

    (merge
      {'shadow.cljs.devtools.client.env/enabled
       true

       'shadow.cljs.devtools.client.env/autoload
       (not (false? autoload))

       'shadow.cljs.devtools.client.env/module-format
       (name (get-in state [:build-options :module-format]))

       'shadow.cljs.devtools.client.env/use-document-host
       (not (false? use-document-host))

       'shadow.cljs.devtools.client.env/server-host
       (or (and (not= host "0.0.0.0") host) "localhost")

       'shadow.cljs.devtools.client.env/server-port
       port

       'shadow.cljs.devtools.client.env/repl-pprint
       (true? repl-pprint)

       'shadow.cljs.devtools.client.env/ignore-warnings
       (true? ignore-warnings)

       'shadow.cljs.devtools.client.env/ssl
       (true? ssl)

       'shadow.cljs.devtools.client.env/reload-strategy
       (if (= :full reload-strategy)
         "full"
         "optimized")

       'shadow.cljs.devtools.client.env/build-id
       (name build-id)

       'shadow.cljs.devtools.client.env/proc-id
       (str proc-id)

       'shadow.cljs.devtools.client.env/devtools-url
       (or devtools-url "")}
      (when log-style
        {'shadow.cljs.devtools.client.env/log-style log-style})
      (when (contains? devtools :hud)
        (hud-defines (:hud devtools))))))

(defn merge-repl-defines [state config]
  (update-in state [:compiler-options :closure-defines] merge (repl-defines state config)))

(defn inject-node-repl
  [state {:keys [devtools] :as config}]
  (if (false? (:enabled devtools))
    state
    (-> state
        (update-in [:compiler-options :closure-defines] merge (repl-defines state config))
        (update-in [::modules/config :main :entries] prepend '[shadow.cljs.devtools.client.node])
        )))

(defn set-output-dir [state mode {:keys [id output-dir] :as config}]
  (-> state
      (cond->
        (seq output-dir)
        (assoc-in [:build-options :output-dir] (io/file output-dir))

        (not (seq output-dir))
        (assoc-in [:build-options :output-dir] (data/cache-file state "out")))))

(defn inject-preloads [state module-id config]
  (let [preloads (get-in config [:devtools :preloads])]
    (if-not (seq preloads)
      state
      (update-in state [::modules/config module-id :entries] prepend preloads)
      )))

(defn bootstrap-host-build? [{:keys [sym->id] :as state}]
  (contains? sym->id 'shadow.cljs.bootstrap.env))

(defn bootstrap-host-info [state]
  (reduce
    (fn [state {:keys [module-id sources] :as mod}]
      (let [all-provides
            (->> sources
                 (map #(data/get-source-by-id state %))
                 (map :provides)
                 (reduce set/union))

            load-str
            (str "shadow.cljs.bootstrap.env.set_loaded(" (json/write-str all-provides) ");")]

        (update-in state [:output [:shadow.build.modules/append module-id] :js] str "\n" load-str "\n")
        ))
    state
    (:build-modules state)))


