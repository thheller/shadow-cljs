(ns shadow.build.targets.browser
  (:refer-clojure :exclude (flush))
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.set :as set]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.util :as util]
            [shadow.build.api :as build-api]
            [shadow.build :as build]
            [shadow.build.targets.shared :as shared]
            [shadow.build.config :as config]
            [shadow.build.output :as output]
            [shadow.build.closure :as closure]
            [shadow.build.data :as data]
            [clojure.java.shell :as sh]
            [clojure.edn :as edn]
            [shadow.build.log :as log]))

(s/def ::entry
  (s/or :sym simple-symbol?
        :str string?))

(s/def ::entries
  (s/coll-of ::entry :kind vector?))

(s/def ::output-dir shared/non-empty-string?)
(s/def ::asset-path shared/non-empty-string?)

;; OLD, only allowed in config so they don't break.
;; rewritten to :output-dir and :asset-path
(s/def ::public-dir shared/non-empty-string?)
(s/def ::public-path shared/non-empty-string?)
;; ---

;; will just be added as is (useful for comments, license, ...)
(s/def ::prepend string?)
(s/def ::append string?)

;; these go through closure optimized, should be valid js
(s/def ::prepend-js string?)
(s/def ::append-js string?)

(s/def ::module-loader boolean?)

(s/def ::depends-on
  (s/coll-of keyword? :kind set?))

(s/def ::module
  (s/keys
    :req-un
    [::entries]
    :opt-un
    [::depends-on
     ::prepend
     ::prepend-js
     ::append-js
     ::append]))

(s/def ::modules
  (s/map-of
    keyword?
    ::module))

(s/def ::target
  (s/keys
    :req-un
    [::modules]
    :opt-un
    [::module-loader
     ::output-dir
     ::asset-path
     ::public-dir
     ::public-path]
    ))

(defmethod config/target-spec :browser [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(def default-browser-config
  {:output-dir "public/js"
   :asset-path "/js"})

(defn json [obj]
  (json/write-str obj :escape-slash false))

(defn inject-loader-setup
  [{:keys [build-modules build-options] :as state} release?]
  (let [{:keys [asset-path cljs-runtime-path]}
        build-options

        [loader-module & modules]
        build-modules

        modules
        (remove :web-worker modules)

        loader-sources
        (into #{} (:sources loader-module))

        module-uris
        (reduce
          (fn [m {:keys [name foreign-files sources] :as module}]
            (let [uris
                  (if release?
                    [(str asset-path "/" (:output-name module))]

                    ;; :dev, never bundles foreign
                    (->> sources
                         (remove loader-sources)
                         (map (fn [src-id]
                                (let [{:keys [output-name] :as rc}
                                      (data/get-source-by-id state src-id)]
                                  (str asset-path "/" cljs-runtime-path "/" output-name))))
                         (into [])))]
              (assoc m name uris)))
          {}
          modules)

        module-infos
        (reduce
          (fn [m {:keys [name depends-on]}]
            (assoc m name (disj depends-on (:name loader-module))))
          {}
          modules)

        loader-append-rc
        (-> loader-module :sources last)]

    (when-not (data/get-output! state loader-append-rc)
      (throw (ex-info "no loader append rc" {:rc loader-append-rc})))

    (update-in state [:output loader-append-rc :js]
      ;; prepend so it is emitted called before the enable()
      #(str "\nshadow.loader.setup(" (json module-uris) ", " (json module-infos) ");\n" %))
    ))

(defn inject-repl-client
  [{:keys [entries] :as module-config} state build-config]
  (let [{:keys [enabled preloads]}
        (:devtools build-config)

        entries
        (-> []
            (cond->
              (not (false? enabled))
              (into '[cljs.user shadow.cljs.devtools.client.browser]))
            (cond->
              (seq preloads)
              (into preloads))
            (into entries))]

    (assoc module-config :entries entries)))

(defn inject-devtools-console [{:keys [entries] :as module-config} state build-config]
  (if (false? (get-in build-config [:devtools :console-support]))
    module-config
    (assoc module-config :entries (into '[shadow.cljs.devtools.client.console] entries))))


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

(defn rewrite-modules
  "rewrites :modules to add browser related things"
  [{:keys [worker-info] :as state} mode {:keys [modules module-loader] :as config}]

  (let [default-module (pick-default-module-from-config modules)]
    (reduce-kv
      (fn [mods module-id {:keys [web-worker default] :as module-config}]
        (let [default?
              (= default-module module-id)

              module-config
              (-> module-config
                  (cond->
                    ;; REPL client - only for watch (via worker-info), not compile
                    (and default? (= :dev mode) worker-info)
                    (inject-repl-client state config)

                    ;; DEVTOOLS console, it is prepended so it loads first in case anything wants to log
                    (and default? (= :dev mode))
                    (inject-devtools-console state config)

                    (and worker-info (not web-worker))
                    (update :append-js str "\nshadow.cljs.devtools.client.browser.module_loaded('" (name module-id) "');\n")

                    ;; MODULE-LOADER
                    ;; default module brings in shadow.loader
                    ;; must create some :append-js so the pseudo-rc is created
                    ;; the enable call is currently a noop
                    (and module-loader default?)
                    (-> (update :entries #(into '[shadow.loader] %))
                        (update :append-js str "\nshadow.loader.enable();"))

                    ;; other modules just need to tell the loader they finished loading
                    (and module-loader (not (or default? web-worker)))
                    (update :append-js str "\nshadow.loader.set_loaded('" (name module-id) "');")
                    ))]

          (assoc mods module-id module-config)))
      {}
      modules)))

(defn configure-modules
  [{:keys [worker-info] :as state} mode {:keys [modules module-loader] :as config}]
  (let [modules (rewrite-modules state mode config)]
    (build-api/configure-modules state modules)))

(defn configure
  [state mode config]
  (let [{:keys [output-dir asset-path public-dir public-path] :as config}
        (merge default-browser-config config)]

    (-> state
        (assoc ::build/config config) ;; so the merged defaults don't get lost
        (cond->
          asset-path
          (build-api/merge-build-options {:asset-path asset-path})

          output-dir
          (build-api/merge-build-options {:output-dir (io/file output-dir)})

          ;; backwards compatibility so it doesn't break existing configs
          public-dir
          (build-api/merge-build-options {:output-dir (io/file public-dir)})

          public-path
          (build-api/merge-build-options {:asset-path public-path})

          (and (= :dev mode) (:worker-info state))
          (-> (repl/setup)
              (update-in [:compiler-options :closure-defines] merge (shared/repl-defines state config))))

        (configure-modules mode config)
        )))

(defn flush-manifest
  [{:keys [build-options] :as state} include-foreign?]
  (spit
    (data/output-file state "manifest.json")
    (let [data
          (->> (:build-modules state)
               (map (fn [{:keys [name output-name entries depends-on sources foreign-files] :as mod}]
                      {:name name
                       :output-name output-name
                       ;; FIXME: this is old, should always use :output-name
                       :js-name output-name
                       :entries entries
                       :depends-on depends-on
                       :sources
                       (->> sources
                            (map #(get-in state [:sources %]))
                            (map :resource-name)
                            (into []))
                       :js-modules
                       (->> sources
                            (map #(get-in state [:sources %]))
                            (map :js-module)
                            (remove nil?)
                            (into []))}
                      )))]
      (with-out-str
        (json/pprint data :escape-slash false))))

  state)

(defn hash-optimized-module [{:keys [output output-name] :as mod}]
  (let [signature (util/md5hex output)]
    (assoc mod :output-name (str/replace output-name #".js$" (str "." signature ".js")))))

(defn hash-optimized-modules [state]
  (update state ::closure/modules
    (fn [optimized]
      (->> optimized
           (map hash-optimized-module)
           (into [])))))

(defn flush [state mode {:keys [module-loader module-hash-names] :as config}]
  (case mode
    :dev
    (-> state
        (output/flush-unoptimized)
        (flush-manifest false))
    :release
    (do (when (and (true? module-loader)
                   (true? module-hash-names))
          ;; FIXME: provide a way to export module config instead of appending it always.
          (throw (ex-info ":module-loader true defeats purpose of :module-hash-names" {})))
        (-> state
            (cond->
              module-hash-names
              (hash-optimized-modules))
            (output/flush-optimized)
            (flush-manifest true)))))

(defn get-all-module-deps [{:keys [build-modules] :as state} {:keys [depends-on] :as mod}]
  ;; FIXME: not exactly pretty, just abusing the fact that build-modules is already ordered
  (->> (reverse build-modules)
       (reduce
         (fn [{:keys [deps order] :as x} {:keys [module-id] :as mod}]
           (if-not (contains? deps module-id)
             x
             {:deps (set/union deps (:depends-on mod))
              :order (conj order mod)}))
         {:deps depends-on
          :order []})
       (:order)
       (reverse)))

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
  [state]
  (update state :build-modules
    (fn [modules]
      (->> modules
           (map (fn [{:keys [goog-base web-worker] :as mod}]
                  (-> mod
                      (cond->
                        goog-base
                        (update :prepend str imul-js-fix "\n")

                        web-worker
                        (update :prepend str (make-web-worker-prepend state mod) "\n")
                        ))))
           (into [])
           ))))

(defmethod log/event->str ::browserify
  [{:keys [module-id js-packages] :as event}]
  (format "Bundling %s for module %s" js-packages module-id))

(defn bundle-js [{:keys [build-modules] :as state}]
  (let [js-index
        (reduce
          (fn [js-index {:keys [module-id sources] :as module}]
            (let [js-packages
                  (->> sources
                       (map #(get-in state [:sources % :js-require]))
                       (remove nil?)
                       (into #{}))]

              (assoc js-index module-id js-packages)))
          {}
          build-modules)

        dev?
        (= :dev (::build/mode state))

        js-index-file
        (data/cache-file state "js-index.edn")

        js-index-cached?
        (and (.exists js-index-file)
             (= js-index (-> (slurp js-index-file)
                             (edn/read-string))))

        build-modules
        (->> build-modules
             (map (fn [{:keys [module-id] :as mod}]
                    (let [all-provided-js
                          (->> (get-all-module-deps state mod)
                               (map :module-id)
                               (map js-index)
                               (reduce set/union #{}))

                          js-packages
                          (get js-index module-id)]

                      ;; if a given module doesn't have js requires we don't need to generate anything
                      (if-not (seq js-packages)
                        mod
                        ;; otherwise generated an extra include via browserify
                        (let [include-name
                              (str "shadow.browserify." (name module-id) ".js")

                              ;; in :dev mode we will load the file directly so it must be in :output-dir
                              ;; in :release mode the file will be concatenated so it doesn't matter where it lives
                              include-file
                              (if dev?
                                (data/output-file state "cljs-runtime" include-name)
                                (data/cache-file state "js-cache" include-name))]

                          ;; don't recreate files that already exist and were built off the same js-index
                          ;; browserify is quite slow and js packages shouldn't change very frequently
                          (when (or (not js-index-cached?)
                                    (not (.exists include-file)))

                            (let [executable
                                  ;; npm and yarn handle installing bin files differenly for dependencies
                                  ;; so use the first thing that exists
                                  (->> ["./node_modules/.bin/browserify" ;; npm
                                        "./node_modules/shadow-cljs/node_modules/.bin/browserify"] ;; yarn
                                       (filter #(.exists (io/file %)))
                                       (first))

                                  ;; if neither exists attempt to use the global command
                                  browserify-cmd
                                  (-> [(or executable "browserify")]
                                      (cond->
                                        dev?
                                        (conj "-d"))
                                      (conj "-g" "envify")
                                      (cond->
                                        (not dev?)
                                        (conj "-g" "uglifyify"))
                                      (util/reduce->
                                        (fn [cmd pkg]
                                          (conj cmd "-r" pkg))
                                        js-packages)
                                      (util/reduce->
                                        (fn [cmd ext]
                                          (conj cmd "-x" ext))
                                        all-provided-js))

                                  {:keys [exit out err] :as result}
                                  (try
                                    (util/with-logged-time [state {:type ::browserify
                                                                   :module-id module-id
                                                                   :cmd browserify-cmd
                                                                   :js-packages js-packages}]

                                      (util/exec
                                        browserify-cmd
                                        {:env {"NODE_ENV" (if dev?
                                                            "development"
                                                            "production")}}))
                                    (catch Exception e
                                      (throw (ex-info
                                               (format "Failed to run: %s" (str/join " " browserify-cmd))
                                               {:tag ::browserify
                                                :command browserify-cmd}
                                               e))))]

                              (when-not (zero? exit)
                                (throw (ex-info "Failed to run browserify" result)))

                              (io/make-parents include-file)
                              (spit include-file out)))

                          (update mod :includes util/vec-conj {:name include-name
                                                               :file include-file}))
                        ))))
             (into []))]

    ;; index must not be written if anything above throws
    (when-not js-index-cached?
      (spit js-index-file (pr-str js-index)))

    (assoc state :build-modules build-modules)))

(defn process
  [{::build/keys [stage mode config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :compile-finish
    (-> state
        (module-wrap)
        (cond->
          (:module-loader config)
          (inject-loader-setup (= :release mode))

          (= :require (get-in state [:js-options :js-provider]))
          (bundle-js)))

    :flush
    (flush state mode config)

    state
    ))
