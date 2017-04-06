(ns shadow.cljs.devtools.targets.browser
  (:refer-clojure :exclude (flush))
  (:require [clojure.java.io :as io]
            [clojure.spec :as s]
            [clojure.data.json :as json]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.devtools.server.compiler :as comp]
            [shadow.cljs.devtools.targets.shared :as shared]
            [shadow.cljs.devtools.server.config :as config]
            ))

(s/def ::entries
  (s/coll-of symbol? :kind vector?))

(s/def ::public-path shared/non-empty-string?)

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
     ::public-dir
     ::public-path]
    ))

(defmethod config/target-spec :browser [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(def default-browser-config
  {:public-dir "public/js"
   :public-path "/js"})

(defn- configure-modules
  [state modules]
  (reduce-kv
    (fn [state module-id {:keys [entries depends-on] :as module-config}]
      (cljs/configure-module state module-id entries (or depends-on #{}) module-config))
    state
    modules))

(defn json [obj]
  (json/write-str obj :escape-slash false))

;; FIXME: assumes default module is the loader
;; FIXME: I think this should rewrite the config instead of the state
;; feels wrong to configure-modules normally and then modify it
(defn inject-loader-callbacks [{:keys [public-path modules] :as state}]
  (when (<= (count modules) 1)
    (throw (ex-info "cannot use module-loader with just one module" {})))

  (let [modules
        (reduce-kv
          (fn [mi mod-name {:keys [default] :as mod}]
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
                    (not default)
                    (update :append-js str "\nshadow.loader.set_loaded('" (name mod-name) "');"))
                  )))
          {}
          modules)]
    (assoc state :modules modules)
    ))

(defn inject-loader-setup
  [{:keys [public-path cljs-runtime-path build-modules] :as state} release?]
  (let [[loader-module & modules]
        build-modules

        modules
        (remove :web-worker modules)

        loader-sources
        (into #{} (:sources loader-module))

        module-uris
        (reduce
          (fn [m {:keys [name foreign-files sources] :as module}]
            (assoc m name
              (if release?
                (let [foreign-uris
                      (->> foreign-files
                           (map (fn [{:keys [js-name]}]
                                  (str public-path "/" js-name)))
                           (into []))
                      mod-uri
                      (str public-path "/" (:js-name module))]
                  (conj foreign-uris mod-uri))

                ;; :dev, never bundles foreign
                (->> sources
                     (remove loader-sources)
                     (map (fn [src-name]
                            (let [js-name (get-in state [:sources src-name :js-name])]
                              (str public-path "/" cljs-runtime-path "/" js-name))))
                     (into [])
                     ))))
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

(defn init [state mode {:keys [modules module-loader] :as config}]
  (let [{:keys [public-dir public-path bundle-foreign]}
        (merge default-browser-config config)]

    (-> state
        (cond->
          public-path
          (cljs/merge-build-options {:public-path public-path})

          public-dir
          (cljs/merge-build-options {:public-dir (io/file public-dir)})

          bundle-foreign
          (cljs/merge-build-options {:bundle-foreign bundle-foreign}))

        (configure-modules modules)
        (cond->
          module-loader
          (inject-loader-callbacks)))))

(defn flush [state mode config]
  (case mode
    :dev
    (cljs/flush-unoptimized state)
    :release
    (cljs/flush-modules-to-disk state)))

(defn process
  [{::comp/keys [stage mode config] :as state}]
  (case stage
    :init
    (init state mode config)

    :compile-finish
    (if (:module-loader config)
      (inject-loader-setup state (= :release mode))
      state)

    :flush
    (flush state mode config)

    state
    ))
