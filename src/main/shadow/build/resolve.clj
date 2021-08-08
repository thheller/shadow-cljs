(ns shadow.build.resolve
  "utility functions for resolving dependencies from entries"
  (:require
    [clojure.string :as str]
    [shadow.cljs.util :as util]
    [shadow.build.classpath :as cp]
    [shadow.build.npm :as npm]
    [shadow.build.data :as data]
    [shadow.build.js-support :as js-support]
    [shadow.build.cljs-bridge :as cljs-bridge]
    [shadow.build.babel :as babel])
  (:import (java.io File)))

(defmulti resolve-deps*
  (fn [state {:keys [type]}]
    type))

(defn resolved? [{:keys [resolved-set] :as state} resource-id]
  (contains? resolved-set resource-id))

(defn stack-push [{:keys [resolved-stack] :as state} resource-id]
  (update state :resolved-stack conj {:resource-id resource-id :deps #{}}))

(defn stack-pop [{:keys [resolved-stack] :as state}]
  (let [{:keys [resource-id deps] :as last} (peek resolved-stack)]
    (-> state
        ;; collecting this here since we need it later for cache invalidation
        (update :immediate-deps assoc resource-id deps)
        (update :resolved-stack pop))))

(declare resolve-require)

(defn resolve-deps
  [{:keys [resolved-stack] :as state} {:keys [resource-id deps] :as rc}]
  {:pre [(data/build-state? state)]}
  (let [head-idx
        (when (seq resolved-stack)
          (-> resolved-stack count dec))]

    (-> state
        (cond->
          head-idx
          (update-in [:resolved-stack head-idx :deps] conj resource-id)

          ;; dont resolve twice
          (not (resolved? state resource-id))
          (-> (update :resolved-set conj resource-id)
              (stack-push resource-id)
              (util/reduce->
                (fn [state dep]
                  (resolve-require state rc dep))
                deps)
              (stack-pop)
              (update :resolved-order conj resource-id))))))

(defmulti find-resource-for-string*
  (fn [state require-from require was-symbol?]
    (get-in state [:js-options :js-provider]))
  :default ::default)

(defn classpath-resource? [{:keys [type classpath] :as rc}]
  (or classpath
      ;; only :js and :shadow-js are allowed to go off classpath
      (not (contains? #{:js :shadow-js} type))))

(defmethod find-resource-for-string* ::default [_ _ _ _]
  (throw (ex-info "invalid [:js-options :js-provider] config" {})))

(defn find-resource-for-string [state require-from require was-symbol?]
  ;; only allow (:require ["foo$bar.baz" :as x]) requires from CLJS and the REPL
  ;; but requires with $ in their name from JS should look up actual files
  (if (and (str/includes? require "$")
           (or (nil? require-from)
               (= :cljs (:type require-from))))
    ;; syntax sugar
    ;; (:require ["some$nested.access" :as foo])
    ;; results in 2 separate resources, one doing the require "some" the other doing "nested.access"
    (js-support/shim-require-sugar-resource require-from require was-symbol?)
    ;; regular require
    (find-resource-for-string* state require-from require was-symbol?)))

(def global-resolve-config
  {"jquery"
   {:export-globals ["$", "jQuery"]}})

(def native-node-modules
  #{"assert" "buffer_ieee754" "buffer" "child_process" "cluster" "console"
    "constants" "crypto" "_debugger" "dgram" "dns" "domain" "events" "freelist"
    "fs" "http" "http2" "https" "_linklist" "module" "net" "os" "path" "punycode"
    "querystring" "readline" "repl" "stream" "string_decoder" "sys" "timers"
    "tls" "tty" "url" "util" "vm" "zlib" "_http_server" "process" "v8" "worker_threads"})

(defn find-npm-resource
  [npm ^File require-from ^String require]
  {:pre [(npm/service? npm)
         (or (nil? require-from)
             (instance? File require-from))
         (string? require)]}

  ;; per build :resolve config that may override where certain requires go
  ;; FIXME: should this only allow overriding package requires?
  ;; relative would need to be relative to the project, otherwise a generic
  ;; "./something.js" would override anything from any package
  ;; just assume ppl will only override packages for now
  (let [resolve-cfg
        (let [x (get-in npm [:js-options :resolve require])]
          ;; can't use `or` since `false` is a legal return
          (if-not (nil? x)
            x
            (get global-resolve-config require)
            ))]

    (if (false? resolve-cfg)
      npm/empty-rc
      (let [{:keys [target]}
            resolve-cfg

            rc
            (case target
              ;; no resolve config, or resolve config without :target
              nil
              (npm/find-resource npm require-from require)

              ;; {"react" {:target :global :global "React"}}
              :global
              (npm/js-resource-for-global require resolve-cfg)

              ;; {"react" {:target :file :file "some/path.js"}}
              :file
              (npm/js-resource-for-file npm require resolve-cfg)

              ;; {"react" {:target :npm :require "preact"}}
              :npm
              (let [other
                    (if (and (= :release (get-in npm [:js-options :mode])) (contains? resolve-cfg :require-min))
                      (:require-min resolve-cfg)
                      (:require resolve-cfg))]

                ;; FIXME: maybe allow to add some additional stuff?
                (when (= require other)
                  (throw (ex-info "can't resolve to self" {:require require :other other})))

                (or (find-npm-resource npm require-from other)
                    (throw (ex-info (format ":resolve override for \"%s\" to \"%s\" which does not exist" require other)
                             {:tag ::invalid-override
                              :require-from require-from
                              :require require
                              :other other}))))

              (throw (ex-info "unknown resolve target"
                       {:require require
                        :config resolve-cfg})))]

        (when rc ;; don't assoc into nil (aka resource not found)
          (cond-> rc
            resolve-cfg
            (-> (assoc :resource-config (assoc resolve-cfg :original-require require))
                ;; make sure that any change to the resolve config invalidated the cache
                (update :cache-key conj resolve-cfg))))))))

;; FIXME: this is now a near duplicate of :shadow, should refactor and remove dupes
(defmethod find-resource-for-string* :closure
  [{:keys [js-options babel classpath] :as state} {:keys [file] :as require-from} require was-symbol?]

  (let [abs? (util/is-absolute? require)
        rel? (util/is-relative? require)
        cp-rc? (when require-from
                 (classpath-resource? require-from))]

    (cond
      ;; entry requires must always be absolute
      (and (nil? require-from) abs?)
      (cp/find-js-resource classpath require)

      ;; requires from non classpath resources go directly to npm resolve
      ;; as do ambiguous requires, eg "react" not "./foo"
      (or (not cp-rc?)
          (and (not abs?)
               (not rel?)))
      (find-npm-resource
        (:npm state)
        (when (and (= :js (:type :require-from))
                   (not (:classpath require-from)))
          file)
        require)

      (util/is-absolute? require)
      (cp/find-js-resource classpath require)

      (util/is-relative? require)
      (cp/find-js-resource classpath require-from require)

      :else
      (throw (ex-info "unsupported require" {:require require}))
      )))

(defn make-babel-source-fn [{:keys [source file] :as rc}]
  (-> rc
      (dissoc :source)
      (assoc :source-fn
             (fn [{:keys [babel] :as state}]
               (babel/convert-source babel state source (.getAbsolutePath file))))
      ))

(defn maybe-babel-rewrite [{:keys [js-esm deps] :as rc}]
  {:pre [(map? rc)]}
  (if (and (:classpath rc) js-esm)
    ;; es6 from the classpath is left as :js type so its gets processed by closure
    rc
    ;; es6 from node_modules and any commonjs is converted to :shadow-js
    ;; maybe rewritten by babel since closure doesn't support the __esModule convention
    ;; that babel created and it is still to widely used to not support
    (let [babel-rewrite?
          js-esm

          deps
          (-> '[shadow.js]
              ;; skip using global helpers for now
              #_(cond-> babel-rewrite? (conj 'shadow.js.babel))
              (into deps))]

      (-> rc
          (assoc :deps deps)
          (assoc :type :shadow-js)
          (cond->
            babel-rewrite?
            (make-babel-source-fn)
            )))))

(defmethod find-resource-for-string* :shadow
  [{:keys [js-options classpath] :as state} {:keys [file] :as require-from} require was-symbol?]

  (cond
    (and (:keep-native-requires js-options)
         (or (contains? native-node-modules require)
             (contains? (:keep-as-require js-options) require)))
    (js-support/shim-require-resource state require)

    (or (str/starts-with? require "esm:")
        (str/starts-with? require "https://")
        (str/starts-with? require "http://")
        (contains? (:keep-as-import js-options) require))
    (js-support/shim-import-resource state require)

    :else
    (let [abs? (util/is-absolute? require)
          rel? (util/is-relative? require)
          cp-rc? (when require-from
                   (classpath-resource? require-from))]

      (when-let [rc
                 (cond
                   ;; entry requires must always be absolute
                   (and (nil? require-from) abs?)
                   (cp/find-js-resource classpath require)

                   ;; requires from non classpath resources go directly to npm resolve
                   ;; as do ambiguous requires, eg "react" not "./foo"
                   (or (not cp-rc?)
                       (and (not abs?)
                            (not rel?)))
                   (find-npm-resource
                     (:npm state)
                     ;; only consider require-from for shadow-js files
                     ;; otherwise it may end up looking at directories it should not look at
                     ;; relative to classpath files
                     (when (= :shadow-js (:type require-from))
                       file)
                     require)

                   (util/is-absolute? require)
                   (cp/find-js-resource classpath require)

                   (util/is-relative? require)
                   (cp/find-js-resource classpath require-from require)

                   :else
                   (throw (ex-info "unsupported require" {:require require})))]

        (maybe-babel-rewrite rc)
        ))))

(defmethod find-resource-for-string* :require
  [{:keys [npm js-options classpath mode] :as state} require-from require was-symbol?]
  (let [cp-rc? (when require-from
                 (classpath-resource? require-from))]

    (cond
      (util/is-absolute? require)
      (if-not cp-rc?
        (throw (ex-info "absolute require not allowed for non-classpath resources" {:require require}))
        (some->
          (cp/find-js-resource classpath require)
          (maybe-babel-rewrite)))

      (util/is-relative? require)
      (some->
        (cp/find-js-resource classpath require-from require)
        (maybe-babel-rewrite))

      :else
      (when (or (contains? native-node-modules require)
                ;; if the require was a string we just pass it through
                ;; we don't need to check if it exists since the runtime
                ;; will take care of that. we do not actually need anything from the package
                (not was-symbol?)
                ;; if `(:require [react ...])` was used we need to check if the package
                ;; is actually installed. otherwise we will blindy return a shim for
                ;; every symbol, no matter it was a typo or actually intended JS package.
                (npm/find-package (:npm state) require)

                ;; treat every symbol as valid when :npm-deps in deps.cljs on the classpath contain it
                (npm/is-npm-dep? (:npm state) require))

        ;; technically this should be done before the find-package above
        ;; but I'm fine with this breaking for symbol requires
        (let [resolve-cfg (get-in js-options [:resolve require])]
          (cond
            (nil? resolve-cfg)
            (js-support/shim-require-resource state require)

            (false? resolve-cfg)
            (assoc npm/empty-rc :deps []) ;; FIXME: why the shadow.js dep in npm?

            (= :npm (:target resolve-cfg))
            (let [{:keys [require require-min]} resolve-cfg

                  require
                  (or (and (= :release mode) require-min)
                      require)]
              (find-resource-for-string state require-from require false))

            (= :file (:target resolve-cfg))
            (npm/js-resource-for-file npm require resolve-cfg)

            (= :global (:target resolve-cfg))
            (npm/js-resource-for-global require resolve-cfg)

            (= :resource (:target resolve-cfg))
            (cp/find-js-resource classpath (:resource resolve-cfg))

            :else
            (throw (ex-info "override not supported for :js-provider :require" resolve-cfg))
            ))))))

(defmethod find-resource-for-string* :import
  [{:keys [npm js-options classpath mode] :as state} require-from require was-symbol?]
  (let [cp-rc? (when require-from
                 (classpath-resource? require-from))]

    (cond
      (util/is-absolute? require)
      (if-not cp-rc?
        (throw (ex-info "absolute require not allowed for non-classpath resources" {:require require}))
        (some->
          (cp/find-js-resource classpath require)
          (maybe-babel-rewrite)))

      (util/is-relative? require)
      (some->
        (cp/find-js-resource classpath require-from require)
        (maybe-babel-rewrite))

      :else
      (when (or (contains? native-node-modules require)
                ;; if the require was a string we just pass it through
                ;; we don't need to check if it exists since the runtime
                ;; will take care of that. we do not actually need anything from the package
                (not was-symbol?)

                ;; treat every symbol as valid when :npm-deps in deps.cljs on the classpath contain it
                (npm/is-npm-dep? (:npm state) require))

        ;; technically this should be done before the find-package above
        ;; but I'm fine with this breaking for symbol requires
        (let [resolve-cfg (get-in js-options [:resolve require])]
          (cond
            (nil? resolve-cfg)
            (js-support/shim-import-resource state require)

            (false? resolve-cfg)
            (assoc npm/empty-rc :deps []) ;; FIXME: why the shadow.js dep in npm?

            (= :npm (:target resolve-cfg))
            (let [{:keys [require require-min]} resolve-cfg

                  require
                  (or (and (= :release mode) require-min)
                      require)]
              (find-resource-for-string state require-from require false))

            (= :file (:target resolve-cfg))
            (npm/js-resource-for-file npm require resolve-cfg)

            (= :global (:target resolve-cfg))
            (npm/js-resource-for-global require resolve-cfg)

            (= :resource (:target resolve-cfg))
            (cp/find-js-resource classpath (:resource resolve-cfg))

            :else
            (throw (ex-info "override not supported for :js-provider :import" resolve-cfg))
            ))))))

(defmethod find-resource-for-string* :external
  [{:keys [npm js-options classpath mode] :as state} require-from require was-symbol?]
  (let [cp-rc? (when require-from
                 (classpath-resource? require-from))]

    (cond
      (util/is-absolute? require)
      (if-not cp-rc?
        (throw (ex-info "absolute require not allowed for non-classpath resources" {:require require}))
        (maybe-babel-rewrite (cp/find-js-resource classpath require)))

      (util/is-relative? require)
      (maybe-babel-rewrite (cp/find-js-resource classpath require-from require))

      :else
      (when (or (contains? native-node-modules require)
                ;; if the require was a string we just pass it through
                ;; we don't need to check if it exists since the runtime
                ;; will take care of that. we do not actually need anything from the package
                (not was-symbol?)
                ;; if `(:require [react ...])` was used we need to check if the package
                ;; is actually installed. otherwise we will blindy return a shim for
                ;; every symbol, no matter it was a typo or actually intended JS package.
                (npm/find-package (:npm state) require)

                ;; treat every symbol as valid when :npm-deps in deps.cljs on the classpath contain it
                (npm/is-npm-dep? (:npm state) require))

        (js-support/shim-require-resource state require)))))

(defn resolve-string-require
  [state {require-from-ns :ns :as require-from} require]
  {:pre [(data/build-state? state)
         (string? require)]}

  ;; in a watch recompile cycle strings may already be resolved so we can re-use those
  ;; resolve is a bit costly since it has to do a bunch of FS interop
  ;; FIXME: properly cleanup :str->sym, should not be possible to get some ns here but nil for rc
  (let [rc
        (when-let [resolved-sym (get-in state [:str->sym require-from-ns require])]
          (when-let [resource-id (get-in state [:sym->id resolved-sym])]
            (get-in state [:sources resource-id])))]
    (if rc
      (resolve-deps state rc)

      ;; fresh resolve
      (let [{:keys [resource-id ns] :as rc}
            (find-resource-for-string state require-from require false)]

        (when-not rc
          (throw (ex-info
                   (if require-from
                     (format "The required JS dependency \"%s\" is not available, it was required by \"%s\"." require (:resource-name require-from))
                     (format "The required JS dependency \"%s\" is not available." require))
                   {:tag ::missing-js
                    :js-package-dirs (get-in state [:npm :js-package-dirs])
                    :require require
                    :require-from (:resource-name require-from)
                    :resolved-stack
                    (->> (:resolved-stack state)
                         (map :resource-id)
                         (map #(get-in state [:sources % :resource-name]))
                         (into []))})))

        (-> state
            (data/maybe-add-source rc)
            (cond->
              require-from-ns
              (data/add-string-lookup require-from-ns require ns)
              (nil? require-from)
              (-> (update :js-entries conj ns)
                  (update :resolved-entries conj resource-id)))
            (resolve-deps rc)
            )))))

(defn ensure-non-circular!
  [{:keys [resolved-stack] :as state} resource-id]
  (let [resolved-ids (into [] (map :resource-id) resolved-stack)]
    (when (some #(= resource-id %) resolved-ids)
      (let [path (->> (conj resolved-ids resource-id)
                      (drop-while #(not= resource-id %))
                      (map (fn [rc-id]
                             (let [{:keys [ns resource-name]} (data/get-source-by-id state rc-id)]
                               (or ns resource-name))))
                      (into []))]
        (throw (ex-info (format "Circular dependency detected: %s" (str/join " -> " path))
                 {:tag ::circular-dependency
                  :resource-id resource-id
                  :path path
                  :stack resolved-ids}))))))

(declare find-resource-for-symbol)

;; check if the provided namespace matches the filename
;; catches errors where demo.with_underscore is defined in demo/with_underscore.cljs
;; when it should be demo.with-underscore
(defn check-correct-ns! [{:keys [virtual macros-ns ns type resource-name]}]
  (when (and (= type :cljs) (not macros-ns) (not virtual))
    (let [expected-ns (util/filename->ns resource-name)]
      (when (not (= expected-ns ns))
        (throw (ex-info "Resource does not have expected namespace"
                 {:tag ::unexpected-ns
                  :resource resource-name
                  :expected-ns expected-ns
                  :actual-ns ns}))))))

(defn find-resource-for-symbol*
  [{:keys [classpath] :as state} require-from require]
  ;; check if ns is an alias
  (or (when-let [alias (get-in state [:ns-aliases require])]
        ;; if alias was previously used we already have all the data
        (if-let [rc-id (get-in state [:sym->id alias])]
          [(get-in state [:sources rc-id]) state]
          (find-resource-for-symbol state require-from alias)))

      ;; otherwise check if the classpath provides a symbol
      (when-let [rc (cp/find-resource-for-provide classpath require)]
        (check-correct-ns! rc)
        [rc state])

      ;; special cases where clojure.core.async gets aliased to cljs.core.async
      (when (str/starts-with? (str require) "clojure.")
        (let [cljs-sym
              (-> (str require)
                  (str/replace #"^clojure\." "cljs.")
                  (symbol))]

          ;; auto alias clojure.core.async -> cljs.core.async if it exists
          (when-let [rc (cp/find-resource-for-provide classpath cljs-sym)]
            (check-correct-ns! rc)
            [rc
             (-> state
                 (update :ns-aliases assoc require cljs-sym)
                 ;; must remember that we used an alias in both cases
                 ;; compiling cljs.core.async will not provide clojure.core.async since it is unaware
                 ;; that it was referenced via an alias, only really required by par-compile since that
                 ;; needs to know which namespaces where provided before compiling other namespaces
                 (update :ns-aliases-reverse assoc cljs-sym require))])))

      ;; special case for symbols that should be strings
      ;; (:require [react]) should be (:require ["react"]) as it is a magical symbol
      ;; that becomes available if node_modules/react exists but not otherwise
      (when-let [{:keys [ns] :as rc}
                 (find-resource-for-string state require-from (str require) true)]
        [rc
         (-> state
             (cond->
               ;; only need to turn ns into magic-symbol if it actually resolved to npm dependency
               ;; (:require [goog$global.Foo :as Foo]) is not a magic symbol, just sugar
               (not (::js-support/split-require rc))
               (update :magic-syms conj require))
             (update :ns-aliases assoc require ns))])

      ;; the ns was not found, handled elsewhere
      [nil state]
      ))

(defn find-resource-for-symbol
  [state require-from require]
  ;; check if already registered by add-source
  (let [rc-id (get-in state [:sym->id require])]
    (if-not rc-id
      ;; unknown resource, not defined in REPL
      (find-resource-for-symbol* state require-from require)

      ;; if the resource in the current compiler state was defined in the REPL
      ;; without a file attached but that file was created in the meantime
      ;; the file version should be picked over the virtual REPL resource
      (let [{:keys [defined-in-repl] :as rc} (get-in state [:sources rc-id])]
        (if-not defined-in-repl
          [rc state]
          (let [[actual state] (find-resource-for-symbol* state require-from require)]
            (if actual
              [actual
               (-> state
                   ;; FIXME: ugly smell. remove-source-by-id doesn't remove virtual sources
                   ;; since they usually can't change and are generated by the compiler
                   ;; REPL lets you get into a state where they can change though (file created after ns eval)
                   ;; should make a proper remove-source-by-id with force option or so
                   (update-in [:sources rc-id] dissoc :virtual)
                   (data/remove-source-by-id rc-id))]
              [rc state]
              )))))))

(defn reinspect-cljc-rc
  [state {:keys [url resource-name macros-ns] :as rc} reader-features]
  (let [{:keys [name deps requires] :as ast}
        (cljs-bridge/get-resource-info
          resource-name
          (or (:source rc) ;; nREPL load-file supplies source
              (slurp url))
          reader-features)

        provide-name
        (if-not macros-ns
          name
          (symbol (str name "$macros")))]

    (-> rc
        (assoc
          :ns-info (dissoc ast :env)
          :ns provide-name
          :provides #{provide-name}
          :requires (into #{} (vals requires))
          :macro-requires
          (-> #{}
              (into (-> ast :require-macros vals))
              (into (-> ast :use-macros vals)))
          :deps deps)
        (cond->
          macros-ns
          (assoc :macros-ns true)))))

(defn resolve-symbol-require [{:keys [classpath] :as state} require-from require]
  {:pre [(data/build-state? state)]}

  (let [[{:keys [resource-id ns resource-name type] :as rc} state]
        (find-resource-for-symbol state require-from require)]

    (when-not rc
      (throw
        (ex-info
          (if require-from
            (format "The required namespace \"%s\" is not available, it was required by \"%s\"." require (:resource-name require-from))
            (format "The required namespace \"%s\" is not available." require))
          {:tag ::missing-ns
           :stack (:resolved-stack state)
           :foreign-provide? (cp/is-foreign-provide? classpath require)
           :require require
           :require-from (:resource-name require-from)})))

    (let [reader-features
          (data/get-reader-features state)

          ;; when using custom :reader-features we need to reinspect the resource
          ;; just in case its requires are in some conditional we didn't cover before
          ;; since the classpath only reads with :cljs
          rc
          (if (or (not= type :cljs)
                  (not (str/ends-with? resource-name ".cljc"))
                  (= reader-features #{:cljs}))
            rc
            (reinspect-cljc-rc state rc reader-features))]

      ;; react symbol may have resolved to a JS dependency
      ;; CLJS does not allow circular dependencies, JS does
      (when (= :cljs type)
        (ensure-non-circular! state resource-id))

      (-> state
          ;; in case of clojure->cljs aliases the rc may already be present
          ;; if clojure.x and cljs.x are both used in sources, the resource is
          ;; resolved twice but added once
          (data/maybe-add-source rc)
          (resolve-deps rc)
          (cond->
            (nil? require-from)
            (update :resolved-entries conj resource-id))
          ))))

(defn resolve-require [state require-from require]
  {:pre [(data/build-state? state)]}
  (cond
    (symbol? require)
    (resolve-symbol-require state require-from require)

    (string? require)
    (resolve-string-require state require-from require)

    :else
    (throw (ex-info "invalid require, only symbols or strings are supported" {:require require}))
    ))

(defn resolve-entry [state entry]
  (resolve-require state nil entry))

(defn resolve-cleanup [state]
  (dissoc state :resolved-order :resolved-set :resolved-stack))

(defn resolve-entries
  "returns [resolved-ids updated-state] where each resolved-id can be found in :sources of the updated state"
  [{:keys [classpath] :as state} entries]
  (let [{:keys [resolved-order] :as state}
        (-> state
            (assoc
              :resolved-set #{}
              :resolved-order []
              :resolved-stack [])
            (util/reduce-> resolve-entry entries))

        auto-require-suffixes
        (get-in state [:build-options :auto-require-suffixes])

        auto-require-namespaces
        (when (seq auto-require-suffixes)
          (->> resolved-order
               (map #(get-in state [:sources %]))
               (filter #(= :cljs (:type %)))
               ;; FIXME: maybe let use configure to include files from jar?
               (remove :from-jar)
               (mapcat (fn [{:keys [ns]}]
                         (->> auto-require-suffixes
                              (map #(symbol (str ns %))))))
               (filter (fn [spec-ns]
                         (or (get-in state [:sym->id spec-ns])
                             (cp/has-resource? classpath spec-ns))))
               (vec)))]

    (if-not (seq auto-require-namespaces)
      [resolved-order (resolve-cleanup state)]
      (let [[extra-sources state]
            (resolve-entries state auto-require-namespaces)]
        [(into resolved-order extra-sources)
         (resolve-cleanup state)]))))

(defn resolve-repl
  "special case for REPL which always resolves based on the current ns"
  [state repl-ns deps]
  {:pre [(symbol? repl-ns)]}

  (let [{:keys [resource-id] :as repl-rc}
        (data/get-source-by-provide state repl-ns)

        {:keys [resolved-order] :as state}
        (-> state
            (assoc
              :resolved-set #{}
              :resolved-order []
              :resolved-stack [])
            (resolve-deps (assoc repl-rc :deps deps)))]

    ;; FIXME: resolve-deps will include the resource itself, might need a rework
    ;; for now just remove it since we just want to know the new deps
    [(into [] (remove #{resource-id}) resolved-order)
     (dissoc state :resolved-order :resolved-set :resolved-stack)]))