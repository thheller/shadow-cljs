(ns shadow.build.targets.browser
  (:refer-clojure :exclude (flush))
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.java.shell :as sh]
            [clojure.edn :as edn]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.util :as util]
            [shadow.build.api :as build-api]
            [shadow.build :as build]
            [shadow.build.targets.shared :as shared]
            [shadow.build.targets.external-index :as external-index]
            [shadow.build.config :as config]
            [shadow.build.output :as output]
            [shadow.build.closure :as closure]
            [shadow.build.modules :as modules]
            [shadow.build.data :as data]
            [shadow.build.log :as log]
            [shadow.core-ext :as core-ext]
            [cljs.compiler :as cljs-comp]
            [shadow.build.npm :as npm]
            [shadow.cljs.devtools.server.npm-deps :as npm-deps]
            [shadow.build.log :as build-log])
  (:import [com.google.javascript.jscomp.deps SourceCodeEscapers]
           [com.google.common.escape Escaper]))

(s/def ::module-loader boolean?)

(defn has-either-chunks-or-modules? [x]
  (or (contains? x :modules)
      (contains? x :chunks)
      false))

(s/def ::target
  (s/and
    (s/keys
      :req-un
      []
      :opt-un
      [::shared/modules
       ::shared/chunks
       ::module-loader
       ::shared/output-dir
       ::shared/asset-path
       ::shared/public-dir
       ::shared/public-path
       ::shared/devtools])
    has-either-chunks-or-modules?))

(comment
  (s/explain :shadow.build.config/build+target
    '{:target :browser
      :build-id :foo
      :modulesx {:foo {:init-fn foo/bar}}}))

(defmethod config/target-spec :browser [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(def default-browser-config
  {:output-dir "public/js"
   :asset-path "/js"})

(defn json [obj]
  (json/write-str obj :escape-slash false))

(defn module-loader-data [{::build/keys [mode config] :keys [build-options] :as state}]
  (let [release?
        (= :release mode)

        {:keys [asset-path cljs-runtime-path]}
        build-options

        build-modules
        (or (::closure/modules state)
            (:build-modules state))

        loader-module
        (first build-modules)

        modules
        (remove :web-worker build-modules)

        loader-sources
        (into #{} (:sources loader-module))

        module-uris
        (reduce
          (fn [m {:keys [module-id default sources] :as module}]
            (let [uris
                  (cond
                    default
                    []

                    (or release? (= :eval (get-in config [:devtools :loader-mode] :eval)))
                    [(str asset-path "/" (:output-name module))]

                    :else ;; dev-mode
                    (->> sources
                         (remove loader-sources)
                         (map (fn [src-id]
                                (let [{:keys [output-name] :as rc}
                                      (data/get-source-by-id state src-id)]
                                  (str asset-path "/" cljs-runtime-path "/" output-name))))
                         (into [])))]
              (assoc m module-id uris)))
          {}
          modules)

        module-infos
        (reduce
          (fn [m {:keys [module-id depends-on]}]
            (assoc m module-id depends-on))
          {}
          modules)]

    {:module-uris module-uris
     :module-infos module-infos}))


(defn inject-repl-client
  [{:keys [entries] :as module-config} state build-config]
  (let [{:keys [enabled]}
        (:devtools build-config)

        entries
        (-> []
            (cond->
              (not (false? enabled))
              (into '[cljs.user shadow.cljs.devtools.client.browser]))

            (into entries))]

    (assoc module-config :entries entries)))

(defn inject-devtools-console [{:keys [entries] :as module-config} state build-config]
  (cond
    (or (false? (get-in build-config [:devtools :console-support]))
        (->> (get-in build-config [:devtools :preloads])
             ;; automatically back off if cljs-devtools is used manually
             (filter #(str/starts-with? (str %) "devtools."))
             (seq)))
    module-config

    ;; automatically inject `cljs-devtools` when found on the classpath
    (io/resource "devtools/preload.cljs")
    (assoc module-config :entries (into '[devtools.preload] entries))

    :else
    (assoc module-config :entries (into '[shadow.cljs.devtools.client.console] entries))
    ))

(defn inject-preloads [{:keys [entries] :as module-config} state build-config]
  (let [preloads (get-in build-config [:devtools :preloads])]
    (if-not (seq preloads)
      module-config
      (assoc module-config :entries (into (vec preloads) entries)))))

(defn pick-default-module-from-config [modules]
  (or (reduce-kv
        (fn [default module-id {:keys [depends-on] :as module-config}]
          (cond
            (:default module-config)
            (reduced module-id)

            (seq depends-on)
            default

            ;; empty depends-on, but encountered one previously
            ;; FIXME: be smarter about detecting default modules, can look at entries for cljs.core or shadow.loader
            default
            (throw (ex-info "two modules without deps, please specify which one is the default" {:a default
                                                                                                 :b module-id}))

            :else
            module-id
            ))
        nil
        modules)

      (throw (ex-info "all modules have deps, can't find default" {}))
      ))

(defn wrap-output [{:keys [default prepend append] :as module-config} state]
  (let [ppns (get-in state [:compiler-options :rename-prefix-namespace])]
    (-> module-config
        (assoc :prepend (str prepend
                             (when (and default (seq ppns))
                               (str "var " ppns " = {};\n"))
                             "(function(){\n")
               :append (str append "\n}).call(this);")))))

(defn apply-output-wrapper
  ([state]
   (update state ::closure/modules apply-output-wrapper state))
  ([modules state]
   (->> modules
        (map #(wrap-output % state))
        (into [])
        )))

(defn merge-init-fn [module init-fn]
  (-> module
      (update :entries util/vec-conj (output/ns-only init-fn))
      (update :append-js str (output/fn-call init-fn))))

(defn rewrite-modules
  "rewrites :modules to add browser related things"
  [{:keys [worker-info] :as state} mode {:keys [modules module-loader release-version] :as config}]

  (let [default-module (pick-default-module-from-config modules)]
    (reduce-kv
      (fn [mods module-id {:keys [web-worker init-fn preloads] :as module-config}]
        (let [default?
              (= default-module module-id)

              module-config
              (-> module-config
                  (assoc :force-append true
                         :default default?)
                  (cond->
                    ;; MODULE-LOADER
                    ;; default module brings in shadow.loader
                    (and module-loader default?)
                    (-> (update :entries #(into '[shadow.loader] %))
                        (cond->
                          (not (false? (:module-loader-init config)))
                          ;; call this before init
                          (update :append-js str "\nshadow.loader.init(\"\");")))

                    init-fn
                    (merge-init-fn init-fn)

                    ;; REPL client - only for watch (via worker-info), not compile

                    (and default? (= :dev mode) worker-info)
                    (inject-repl-client state config)

                    (and worker-info (not web-worker) (not (false? (get-in config [:devtools :enabled]))))
                    (update :append-js str "\nshadow.cljs.devtools.client.browser.module_loaded('" (name module-id) "');\n")

                    ;; other modules just need to tell the loader they finished loading
                    (and module-loader (not (or default? web-worker)))
                    (-> (update :prepend-js str "\nshadow.loader.set_load_start('" (name module-id) "');")
                        (update :append-js str "\nshadow.loader.set_loaded();"))

                    ;; per module :preloads
                    (and (seq preloads) (= :dev mode))
                    (update :entries shared/prepend preloads)

                    ;; global :devtools :preloads
                    (and default? (= :dev mode))
                    (inject-preloads state config)

                    (and release-version (= :release mode))
                    (assoc :output-name (str (name module-id) "." release-version ".js"))

                    ;; DEVTOOLS console, it is prepended so it loads first in case anything wants to log
                    (and default? (= :dev mode))
                    (inject-devtools-console state config)))]

          (assoc mods module-id module-config)))
      {}
      modules)))

(defn configure-modules
  [state mode config]
  (let [modules (rewrite-modules state mode config)]
    (build-api/configure-modules state modules)))

(defn configure
  [state mode {:keys [chunks modules] :as config}]
  ;; accept :chunks as an alias for :modules since closure started calling them
  ;; that internally as well and the name is a bit more clear given how overused
  ;; :modules is in other contexts
  (when (and modules chunks)
    (throw (ex-info "can only have :modules OR :chunks not both" config)))

  (let [{:keys [output-dir asset-path public-dir public-path modules] :as config}
        (-> (merge default-browser-config config)
            (cond->
              (not (false? (get-in config [:devtools :autoload])))
              (assoc-in [:devtools :autoload] true)
              chunks
              (assoc :modules chunks)))

        output-wrapper?
        (and (= :release mode)
             (not (false? (get-in state [:compiler-options :output-wrapper]))))]

    (-> state
        (assoc ::build/config config) ;; so the merged defaults don't get lost
        (assoc-in [:compiler-options :output-wrapper] output-wrapper?)
        (cond->
          ;; only turn this on with 2+ modules, not required for single file
          (and output-wrapper?
               (not= 1 (count modules))
               (not (seq (get-in state [:compiler-options :rename-prefix-namespace]))))
          (assoc-in [:compiler-options :rename-prefix-namespace] "$APP")

          asset-path
          (build-api/merge-build-options {:asset-path asset-path})

          output-dir
          (build-api/merge-build-options {:output-dir (io/file output-dir)})

          ;; backwards compatibility so it doesn't break existing configs
          public-dir
          (build-api/merge-build-options {:output-dir (io/file public-dir)})

          public-path
          (build-api/merge-build-options {:asset-path public-path})

          (not (contains? (:js-options config) :js-provider))
          (build-api/with-js-options {:js-provider :shadow})

          (and (= :dev mode) (:worker-info state))
          (shared/merge-repl-defines config))

        (configure-modules mode config)
        )))

(defn flush-manifest
  [{:keys [build-options] :as state}]
  (let [data
        (->> (or (::closure/modules state)
                 (:build-modules state))
             (map (fn [{:keys [module-id output-name entries depends-on sources foreign-files] :as mod}]
                    {:module-id module-id
                     :name module-id
                     :output-name output-name
                     :entries entries
                     :depends-on depends-on
                     :sources
                     (->> sources
                          (map #(get-in state [:sources %]))
                          (map :resource-name)
                          (into []))}))
             (into []))

        manifest-name
        (:manifest-name build-options "manifest.edn")

        manifest-file
        (data/output-file state manifest-name)

        manifest
        (cond
          (str/ends-with? manifest-name ".edn")
          (core-ext/safe-pr-str data)

          (str/ends-with? manifest-name ".json")
          (with-out-str
            (json/pprint data :escape-slash false))

          :else
          (throw (ex-info (format "invalid manifest output format: %s" manifest-name) {:manifest-name manifest-name})))]

    (spit manifest-file manifest))
  state)

(defn flush-module-data [state]
  (let [module-data
        (module-loader-data state)

        json-file
        (data/output-file state "module-loader.json")

        edn-file
        (data/output-file state "module-loader.edn")]

    (io/make-parents json-file)

    (spit json-file (json module-data))
    (spit edn-file (core-ext/safe-pr-str module-data))

    state
    ))

(defn hash-optimized-module [{:keys [sources prepend append output output-name] :as mod} state module-hash-names]
  (let [signature
        (->> sources
             (map #(data/get-source-by-id state %))
             ;; these are prepended after closure compilation
             ;; so they need to be included in the hash calculation
             ;; not just output
             (filter #(= :shadow-js (:type %)))
             (map #(data/get-output! state %))
             (map :js)
             (into [prepend output append])
             (remove nil?)
             (util/md5hex-seq))

        signature
        (cond
          (true? module-hash-names)
          signature
          (and (number? module-hash-names)
               (<= 0 module-hash-names 32))
          (subs signature 0 module-hash-names)
          :else
          (throw (ex-info (format "invalid :module-hash-names value %s" module-hash-names)
                   {:tag ::module-hash-names
                    :module-hash-names module-hash-names})))]

    (assoc mod :output-name (str/replace output-name #".js$" (str "." signature ".js")))))

(defn hash-optimized-modules [state module-hash-names]
  (update state ::closure/modules
    (fn [optimized]
      (->> optimized
           (map #(hash-optimized-module % state module-hash-names))
           (into [])))))

;; in case the module is loaded by shadow.loader and not via its generated index file
(defn append-module-sources-set-loaded-calls [{:keys [build-modules] :as state}]
  (reduce
    (fn [state {:keys [module-id sources] :as mod}]
      (let [set-loaded-info
            (->> sources
                 (map #(get-in state [:sources %]))
                 (map #(str "SHADOW_ENV.setLoaded(" (pr-str (:output-name %)) ");"))
                 (str/join "\n"))

            append-id
            [::modules/append module-id]]

        (update-in state [:sources append-id :source] str ";\n" set-loaded-info)))
    state
    build-modules))

(defn inject-loader-setup-dev
  [state config]
  (let [{:keys [module-uris module-infos]}
        (module-loader-data state)]

    (update-in state [:build-modules 0 :prepend]
      str "\nvar shadow$modules = " (json {:uris module-uris :infos module-infos}) ";\n")
    ))

;; in release just append to first (base) module
(defn inject-loader-setup-release
  [state {:keys [module-loader module-hash-names] :as config}]
  (let [{:keys [module-uris module-infos] :as md} (module-loader-data state)]

    (update-in state [::closure/modules 0]
      (fn [{:keys [module-id] :as mod}]
        ;; since prepending this text changes the md5 of the output
        ;; we need to re-hash that module again
        (-> mod
            (update :prepend str "\nvar shadow$modules = " (json {:uris module-uris :infos module-infos}) ";\n")
            (cond->
              module-hash-names
              ;; previous hash already changed the output-name, reset it back
              (-> (assoc :output-name (str (name module-id) ".js"))
                  (hash-optimized-module state module-hash-names))))))))

(defn get-all-module-deps [{:keys [build-modules] :as state} {:keys [depends-on] :as mod}]
  ;; FIXME: not exactly pretty, just abusing the fact that build-modules is already ordered
  (->> (reverse (or (get-in state [:shadow.build.closure/modules])
                    (get-in state [:build-modules])))
       (remove :dead)
       (reduce
         (fn [{:keys [deps order] :as x} {:keys [module-id] :as mod}]
           (if-not (contains? deps module-id)
             x
             {:deps (set/union deps (:depends-on mod))
              :order (conj order mod)}))
         {:deps depends-on
          :order []})
       (:order)
       (reverse)
       (into [])))

(def ^Escaper js-escaper
  (SourceCodeEscapers/javascriptEscaper))

(defn generate-eval-js-output [state {:keys [sources] :as mod}]
  (->> sources
       (remove #{output/goog-base-id})
       (reduce
         (fn [state src-id]
           (let [{:keys [output-name] :as rc} (data/get-source-by-id state src-id)
                 {:keys [js eval-js] :as output} (data/get-output! state rc)]
             (if eval-js
               state
               (let [source-map? (output/has-source-map? output)
                     eval-js (str "SHADOW_ENV.evalLoad(\"" output-name "\", " source-map? " , \"" (.escape js-escaper ^String js) "\");")]
                 (assoc-in state [:output src-id :eval-js] eval-js)))))
         state)))

(defn flush-unoptimized-module-eval
  [{:keys [unoptimizable] :as state}
   {:keys [goog-base prepend append sources web-worker] :as mod}
   target]

  (let [sources
        (if-not web-worker
          sources
          (let [mods (get-all-module-deps state mod)]
            (-> []
                (into (mapcat :sources) mods)
                (into sources))))

        source-loads
        (->> sources
             (map #(get-in state [:output % :eval-js]))
             (str/join "\n"))

        out
        (str prepend
             ;; (str "SHADOW_ENV.load({}, " (json/write-str require-files) ");\n")
             source-loads
             append)

        out
        (if (or goog-base web-worker)
          (str unoptimizable
               ;; always include this in dev builds
               ;; a build may not include any shadow-js initially
               ;; but load some from the REPL later
               "var shadow$provide = {};\n"

               (when (and web-worker (get-in state [::build/config :module-loader]))
                 "var shadow$modules = false;\n")

               (let [{:keys [polyfill-js]} state]
                 (when (and (or goog-base web-worker) (seq polyfill-js))
                   (str "\n" polyfill-js)))

               (-> state
                   (cond->
                     web-worker
                     (assoc-in [:compiler-options :closure-defines "shadow.cljs.devtools.client.env.enabled"] false))
                   (output/closure-defines-and-base))

               (if web-worker
                 (slurp (io/resource "shadow/boot/worker.js"))
                 (slurp (io/resource "shadow/boot/browser.js")))
               "\n\n"
               ;; create the $CLJS var so devtools can always use it
               ;; always exists for :module-format :js
               "goog.global[\"$CLJS\"] = goog.global;\n"
               "\n\n"
               out)
          ;; else
          out)]

    (io/make-parents target)
    (spit target out))
  state)

(defn flush-unoptimized-module-fetch
  [{:keys [unoptimizable build-options] :as state}
   {:keys [goog-base output-name prepend append sources web-worker] :as mod}
   target]

  (let [{:keys [dev-inline-js]}
        build-options

        sources
        (if-not web-worker
          sources
          (let [mods (get-all-module-deps state mod)]
            (-> []
                (into (mapcat :sources) mods)
                (into sources))))

        inlineable-sources
        (if-not dev-inline-js
          []
          (->> sources
               (map #(data/get-source-by-id state %))
               (filter output/inlineable?)
               (into [])))

        inlineable-set
        (into #{} (map :resource-id) inlineable-sources)

        inlined-js
        (->> inlineable-sources
             (map #(data/get-output! state %))
             (map :js)
             (str/join "\n"))

        ;; goog.writeScript_ (via goog.require) will set these
        ;; since we skip these any later goog.require (that is not under our control, ie REPL)
        ;; won't recognize them as loaded and load again
        closure-require-hack
        (->> inlineable-sources
             (map :output-name)
             (map (fn [output-name]
                    ;; not entirely sure why we are setting the full path and just the name
                    ;; goog seems to do that
                    (str "SHADOW_ENV.setLoaded(\"" + output-name "\");")
                    ))
             (str/join "\n"))

        require-files
        (->> sources
             (remove inlineable-set)
             (remove #{output/goog-base-id})
             (map #(data/get-source-by-id state %))
             (map :output-name)
             (into []))

        out
        (str inlined-js
             prepend
             closure-require-hack
             (str "SHADOW_ENV.load({}, " (json/write-str require-files) ");\n")
             append)

        out
        (if (or goog-base web-worker)
          (str unoptimizable
               ;; always include this in dev builds
               ;; a build may not include any shadow-js initially
               ;; but load some from the REPL later
               "var shadow$provide = {};\n"

               (when (and web-worker (get-in state [::build/config :module-loader]))
                 "var shadow$modules = false;\n")

               (let [{:keys [polyfill-js]} state]
                 (when (and goog-base (seq polyfill-js))
                   (str "\n" polyfill-js)))

               (-> state
                   (cond->
                     web-worker
                     (assoc-in [:compiler-options :closure-defines "shadow.cljs.devtools.client.env.enabled"] false))
                   (output/closure-defines-and-base))

               (if web-worker
                 (slurp (io/resource "shadow/boot/worker.js"))
                 (slurp (io/resource "shadow/boot/browser.js")))
               "\n\n"
               ;; create the $CLJS var so devtools can always use it
               ;; always exists for :module-format :js
               "goog.global[\"$CLJS\"] = goog.global;\n"
               "\n\n"
               out)
          ;; else
          out)]

    (io/make-parents target)
    (spit target out))

  state)

(defn flush-unoptimized-module
  [state module target]
  (if (= :eval (get-in state [:shadow.build/config :devtools :loader-mode] :eval))
    (-> state
        (generate-eval-js-output module)
        (flush-unoptimized-module-eval module target))
    (flush-unoptimized-module-fetch state module target)))

(defn flush-unoptimized
  [{:keys [build-modules] :as state}]

  ;; FIXME: this always flushes
  ;; it could do partial flushes when nothing was actually compiled
  ;; a change in :closure-defines won't trigger a recompile
  ;; so just checking if nothing was compiled is not reliable enough
  ;; flushing really isn't that expensive so just do it always

  (when-not (seq build-modules)
    (throw (ex-info "flush before compile?" {})))

  (output/flush-sources state)

  (util/with-logged-time
    [state {:type :flush-unoptimized}]

    (reduce
      (fn [state {:keys [output-name] :as mod}]
        (flush-unoptimized-module state mod (data/output-file state output-name)))
      state
      build-modules)))

(defn flush [state mode {:keys [module-loader module-hash-names] :as config}]
  (-> state
      (cond->
        (= :external (get-in state [:js-options :js-provider]))
        (external-index/flush-js)

        (= :dev mode)
        (-> (cond->
              module-loader
              (-> (inject-loader-setup-dev config)
                  (flush-module-data)))
            (flush-unoptimized)
            (flush-manifest))

        (= :release mode)
        (-> (cond->
              ;; must hash before adding loader since it needs to know the final uris of the modules
              ;; it will change the uri of the base module after
              module-hash-names
              (hash-optimized-modules module-hash-names)

              ;; true to inject the loader data (which changes the signature)
              ;; any other true-ish value still generates the module-loader.edn data files
              ;; but does not inject (ie. change the signature)
              (true? module-loader)
              (inject-loader-setup-release config)

              (get-in state [:compiler-options :output-wrapper])
              (apply-output-wrapper))
            (output/flush-optimized)
            (cond->
              module-loader
              (flush-module-data))
            (flush-manifest)))))

(defn make-web-worker-prepend [state mod]
  (let [all
        (get-all-module-deps state mod)

        import-script-names
        (->> all
             (map :output-name)
             (map pr-str)
             (str/join ","))]

    (str "importScripts(" import-script-names ");")))

(def imul-js-fix
  (str/trim (slurp (io/resource "cljs/imul.js"))))

(defn module-wrap
  "add web specific prepends to each module"
  ;; FIXME: node environments should not require the Math.imul fix right?
  [{::build/keys [mode] :as state}]
  (update state :build-modules
    (fn [modules]
      (->> modules
           (map (fn [{:keys [goog-base web-worker] :as mod}]
                  (-> mod
                      (cond->
                        goog-base
                        (update :prepend str imul-js-fix "\n")

                        (and (= :release mode) web-worker)
                        (update :prepend str (make-web-worker-prepend state mod) "\n")
                        ))))
           (into [])
           ))))

(defn maybe-inject-cljs-loader-constants
  [{:keys [sym->id] :as state} mode config]
  (if-not (contains? sym->id 'cljs.loader)
    state
    (let [{:keys [module-uris module-infos] :as data}
          (module-loader-data state)]
      (assoc state :loader-constants {'cljs.core/MODULE_URIS module-uris
                                      'cljs.core/MODULE_INFOS module-infos})
      )))


(defmethod build-log/event->str ::npm-version-check
  [event]
  "Checking used npm package versions")

(defmethod build-log/event->str ::npm-version-conflict
  [{:keys [package-name wanted-dep wanted-version installed-version] :as event}]
  (format "npm package \"%s\" expected version \"%s@%s\" but \"%s\" is installed."
    package-name
    wanted-dep
    wanted-version
    installed-version))

(defn check-npm-versions [{::keys [version-checked] :keys [npm] :as state}]
  (let [pkg-index
        (->> (data/get-build-sources state)
             (filter #(= :shadow-js (:type %)))
             (map :package-name)
             (remove nil?)
             (remove #(contains? version-checked %))
             (into #{})
             (reduce
               (fn [m package-name]
                 (assoc m package-name (npm/find-package npm package-name)))
               {}))]

    (if-not (seq pkg-index)
      ;; prevent the extra verbose log entry when no check is done
      state
      ;; keeping track of what we checked so its not repeatedly check during watch
      ;; FIXME: updating npm package while watch is running will not check again
      (util/with-logged-time [state {:type ::npm-version-check}]
        (reduce
          (fn [state package-name]
            (doseq [[dep wanted-version]
                    (merge (get-in pkg-index [package-name :package-json "dependencies"])
                      (get-in pkg-index [package-name :package-json "peerDependencies"]))
                    ;; not all deps end up being used so we don't need to check the version
                    :when (get pkg-index dep)
                    :let [installed-version (get-in pkg-index [dep :package-json "version"])]
                    :when (not (npm-deps/semver-intersects wanted-version installed-version))]

              (util/warn state {:type ::npm-version-conflict
                                :package-name package-name
                                :wanted-dep dep
                                :wanted-version wanted-version
                                :installed-version installed-version}))

            (update state ::version-checked util/set-conj package-name))
          state
          (keys pkg-index))))))

(defn process
  [{::build/keys [stage mode config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :compile-prepare
    (-> state
        ;; (maybe-inject-cljs-loader-constants mode config)
        (cond->
          (and (= :dev mode) (> (count (:modules config)) 1))
          (append-module-sources-set-loaded-calls)))

    :compile-finish
    (-> state
        (module-wrap)
        (check-npm-versions)
        (cond->
          (shared/bootstrap-host-build? state)
          (shared/bootstrap-host-info)))

    :flush
    (flush state mode config)

    state
    ))

(comment
  (shadow.cljs.devtools.api/compile :browser)
  (shadow.cljs.devtools.api/release :browser)
  (shadow.cljs.devtools.api/watch :browser {:verbose true}))
