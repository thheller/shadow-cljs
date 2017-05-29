(ns shadow.cljs.devtools.targets.browser
  (:refer-clojure :exclude (flush))
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.devtools.targets.shared :as shared]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.output :as output]
            [shadow.cljs.closure :as closure]
            [shadow.cljs.util :as util]))

(s/def ::entries
  (s/coll-of simple-symbol? :kind vector?))

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

(defn- configure-modules
  [state mode config modules]
  (reduce-kv
    (fn [state module-id module-config]
      (let [{:keys [entries depends-on] :as module-config}
            (-> module-config
                (cond->
                  (and (:worker-info state) (not (:web-worker module-config)))
                  (update :append-js str "\nshadow.cljs.devtools.client.browser.module_loaded('" (name module-id) "');\n")))]

        (cljs/configure-module state module-id entries (or depends-on #{}) module-config)))
    state
    modules))

;; FIXME: I think this should rewrite the config instead of the state
;; feels wrong to configure-modules normally and then modify it

(defn inject-devtools
  [{:keys [default-module] :as state} config]
  (let [{:keys [console-support enabled]}
        (:devtools config)]

    (if (false? enabled)
      state
      (-> state
          (update :closure-defines merge (shared/repl-defines state config))

          ;; inject an entry for 'cljs.user to ensure that it is loaded as the repl eval will begin in that namespace
          (update-in [:modules default-module :entries] shared/prepend '[cljs.user shadow.cljs.devtools.client.browser])
          (cond->
            (not (false? console-support))
            (update-in [:modules default-module :entries] shared/prepend '[shadow.cljs.devtools.client.console]))
          ))))

(defn json [obj]
  (json/write-str obj :escape-slash false))

;; FIXME: assumes default module is the loader
(defn inject-loader-callbacks [{:keys [asset-path modules] :as state}]
  (when (<= (count modules) 1)
    (throw (ex-info "cannot use module-loader with just one module" {})))

  (let [modules
        (reduce-kv
          (fn [mi mod-name {:keys [default web-worker] :as mod}]
            (assoc mi mod-name
                   (-> mod
                       (cond->
                         ;; default module brings in shadow.loader
                         ;; must create some :append-js so the pseudo-rc is created
                         ;; the enable call is currently a noop
                         default
                         (-> (update :entries #(into '[shadow.loader] %))
                             (update :append-js str "\nshadow.loader.enable();"))

                         ;; other modules just need to tell the loader they finished loading
                         (and (not default) (not web-worker))
                         (update :append-js str "\nshadow.loader.set_loaded('" (name mod-name) "');"))
                       )))
          {}
          modules)]
    (assoc state :modules modules)
    ))

(defn inject-loader-setup
  [{:keys [asset-path cljs-runtime-path build-modules] :as state} release?]
  (let [[loader-module & modules]
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
                    (let [foreign-uris
                          (->> foreign-files
                               (map (fn [{:keys [js-name]}]
                                      (str asset-path "/" js-name)))
                               (into []))
                          mod-uri
                          (str asset-path "/" (:js-name module))]
                      (conj foreign-uris mod-uri))

                    ;; :dev, never bundles foreign
                    (->> sources
                         (remove loader-sources)
                         (map (fn [src-name]
                                (let [js-name (get-in state [:sources src-name :js-name])]
                                  (str asset-path "/" cljs-runtime-path "/" js-name))))
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
        (str "shadow/module/append/" (-> loader-module :name name) ".js")]

    (when-not (get-in state [:sources loader-append-rc])
      (throw (ex-info "no loader append rc" {:rc loader-append-rc})))

    (update-in state [:sources loader-append-rc :output]
      ;; prepend so it is emitted called before the enable()
      #(str "\nshadow.loader.setup(" (json module-uris) ", " (json module-infos) ");\n" %))
    ))

(defn init
  [state mode {:keys [modules module-loader] :as config}]
  (let [{:keys [output-dir asset-path bundle-foreign public-dir public-path]}
        (merge default-browser-config config)]

    (-> state
        (cond->
          asset-path
          (cljs/merge-build-options {:asset-path asset-path})

          output-dir
          (cljs/merge-build-options {:output-dir (io/file output-dir)})

          ;; backwards compatibility so it doesn't break existing configs
          public-dir
          (cljs/merge-build-options {:output-dir (io/file public-dir)})
          public-path
          (cljs/merge-build-options {:asset-path public-path})

          bundle-foreign
          (cljs/merge-build-options {:bundle-foreign bundle-foreign}))

        ;; FIXME: add config option
        ;; (assoc :emit-js-require false)

        (configure-modules mode config modules)

        (cond->
          (:worker-info state)
          (-> (repl/setup)
              (inject-devtools config))

          module-loader
          (inject-loader-callbacks)))))


(defn flush-manifest
  [{:keys [output-dir] :as state} include-foreign?]
  (spit
    (io/file output-dir "manifest.json")
    (let [data
          (->> (or (::closure/modules state) ;; must use :optimized for :release builds because of :module-hash-names
                   (:build-modules state))
               (map (fn [{:keys [name js-name entries depends-on default sources foreign-files] :as mod}]
                      (-> {:name name
                           :js-name js-name
                           :entries entries
                           :depends-on depends-on
                           :default default
                           :sources sources
                           :js-modules
                           (->> sources
                                (map #(get-in state [:sources %]))
                                (map :js-module)
                                (remove nil?)
                                (into []))}

                          (cond->
                            (and include-foreign? (seq foreign-files))
                            (assoc :foreign (mapv #(select-keys % [:js-name :provides]) foreign-files))))
                      )))]
      (with-out-str
        (json/pprint data :escape-slash false))))

  state)

(defn hash-optimized-module [{:keys [output js-name] :as mod}]
  (let [signature (cljs/md5hex output)]
    (assoc mod :js-name (str/replace js-name #".js$" (str "." signature ".js")))))

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

(defn foreign-js-source-for-mod [state {:keys [sources] :as mod}]
  (->> sources
       (map #(get-in state [:sources %]))
       (filter util/foreign?)
       (map :output)
       (str/join "\n")))

(defn module-wrap
  "add web specific prepends to each module"
  ;; FIXME: node environments should not require the Math.imul fix right?
  [{:keys [bundle-foreign] :as state}]
  (update state :build-modules
    (fn [modules]
      (->> modules
           (map (fn [{:keys [name prepend append default] :as mod}]
                  (let [module-prefix
                        (cond
                          default
                          (:unoptimizable state)

                          (:web-worker mod)
                          (let [deps (:depends-on mod)]
                            (str (str/join "\n" (for [other modules
                                                      :when (contains? deps (:name other))]
                                                  (str "importScripts('" (:js-name other) "');")))
                                 "\n\n"))

                          :else
                          "")

                        module-prefix
                        (if (= :inline bundle-foreign)
                          (str prepend (foreign-js-source-for-mod state mod) module-prefix)
                          (str prepend "\n" module-prefix))

                        module-prefix
                        (if (seq module-prefix)
                          (str module-prefix "\n")
                          "")]

                    (assoc mod :prepend module-prefix)
                    )))
           (into [])
           ))))

(defn process
  [{::comp/keys [stage mode config] :as state}]
  (case stage
    :init
    (init state mode config)

    :compile-finish
    (-> state
        (module-wrap)
        (cond->
          (:module-loader config)
          (inject-loader-setup (= :release mode))
          ))

    :flush
    (flush state mode config)

    state
    ))
