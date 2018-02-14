(ns shadow.build.resolve
  "utility functions for resolving dependencies from entries"
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [cljs.analyzer :as cljs-ana]
            [cljs.compiler :as cljs-comp]
            [shadow.cljs.util :as util]
            [shadow.build.resource :as rc]
            [shadow.build.classpath :as cp]
            [shadow.build.npm :as npm]
            [shadow.build.data :as data]
            [shadow.build.js-support :as js-support]
            [shadow.build.cljs-bridge :as cljs-bridge]
            [shadow.build.babel :as babel])
  (:import (java.io File)
           (java.net URL)
           [java.nio.file Paths]))

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
  {:pre [(data/build-state? state)
         (rc/valid-resource? rc)]}
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

(defmulti find-resource-for-string
  (fn [state rc require]
    (get-in state [:js-options :js-provider]))
  :default ::default)

(defn classpath-resource? [{:keys [type classpath] :as rc}]
  (or classpath
      ;; only :js and :shadow-js are allowed to go off classpath
      (not (contains? #{:js :shadow-js} type))))

(defmethod find-resource-for-string ::default [_ _ _]
  (throw (ex-info "invalid [:js-options :js-provider] config" {})))

;; FIXME: this is now a near duplicate of :shadow, should refactor and remove dupes
(defmethod find-resource-for-string :closure
  [{:keys [js-options babel classpath] :as state} {:keys [file] :as require-from} require]

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
      (when-let [rc (npm/find-resource (:npm state) file require
                      (assoc js-options
                        :mode (:mode state)
                        :target :browser))]
        (update rc :deps #(into ['shadow.process] %)))

      (util/is-absolute? require)
      (cp/find-js-resource classpath require)

      (util/is-relative? require)
      (cp/find-js-resource classpath require-from require)

      :else
      (throw (ex-info "unsupported require" {:require require}))
      )))

(defn as-shadow-js
  [{:keys [babel] :as state}
   {:keys [js-esm deps resource-name source] :as rc}]

  (let [babel-rewrite?
        js-esm

        deps
        (-> '[shadow.js]
            (cond->
              babel-rewrite?
              (conj 'shadow.js.babel))
            (into deps))]

    (-> rc
        (assoc :deps deps)
        (assoc :type :shadow-js)
        (cond->
          babel-rewrite?
          (-> (dissoc :source)
              ;; babel turns it into commonjs
              (assoc :source-fn
                     (fn [state]
                       (babel/convert-source babel state source resource-name)))
              )))))

(defmethod find-resource-for-string :shadow
  [{:keys [js-options babel classpath] :as state} {:keys [file] :as require-from} require]

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
      (some->>
        (npm/find-resource (:npm state) file require
          (assoc js-options
            :mode (:mode state)
            :target :browser))
        (as-shadow-js state))

      (util/is-absolute? require)
      (cp/find-js-resource classpath require)

      (util/is-relative? require)
      (cp/find-js-resource classpath require-from require)

      :else
      (throw (ex-info "unsupported require" {:require require}))
      )))

(def native-node-modules
  #{"assert" "buffer_ieee754" "buffer" "child_process" "cluster" "console"
    "constants" "crypto" "_debugger" "dgram" "dns" "domain" "events" "freelist"
    "fs" "http" "https" "_linklist" "module" "net" "os" "path" "punycode"
    "querystring" "readline" "repl" "stream" "string_decoder" "sys" "timers"
    "tls" "tty" "url" "util" "vm" "zlib" "_http_server" "process" "v8"})

(defmethod find-resource-for-string :require
  [{:keys [project-dir npm js-options classpath] :as state} {:keys [resource-name] :as require-from} require]
  (let [cp-rc? (when require-from
                 (classpath-resource? require-from))]

    (cond
      (util/is-absolute? require)
      (if-not cp-rc?
        (throw (ex-info "absolute require not allowed for non-classpath resources" {:require require}))
        (cp/find-js-resource classpath require))

      (util/is-relative? require)
      (cp/find-js-resource classpath require-from require)

      :else
      (let [js-packages
            (get-in state [:js-options :packages])

            ;; require might be react-dom or react-dom/server
            ;; we need to check the package name only
            [package-name suffix]
            (npm/split-package-require require)]

        ;; I hate magic symbols buts its the way chosen by CLJS
        ;; so if the package is configured or exists in node_modules we allow it
        ;; FIXME: actually use configuration from :packages to use globals and such
        (when (or (contains? native-node-modules package-name)
                  (contains? js-packages package-name)
                  (npm/find-package (:npm state) package-name))
          (js-support/shim-require-resource require))
        ))))

(defn resolve-string-require
  [state {require-from-ns :ns :as require-from} require]
  {:pre [(data/build-state? state)
         (string? require)]}

  (let [{:keys [resource-id ns] :as rc}
        (find-resource-for-string state require-from require)]

    (when-not rc
      (throw (ex-info
               (if require-from
                 (format "The required JS dependency \"%s\" is not available, it was required by \"%s\"." require (:resource-name require-from))
                 (format "The required JS dependency \"%s\" is not available." require))
               {:tag ::missing-js
                :require require
                :require-from (:resource-name require-from)})))

    (-> state
        (data/maybe-add-source rc)
        (cond->
          require-from-ns
          (data/add-string-lookup require-from-ns require ns)
          (nil? require-from)
          (update :js-entries conj ns))
        (resolve-deps rc)
        )))

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

(defn find-resource-for-symbol
  [{:keys [classpath sym->id] :as state} require-from require]
  ;; check if already registered by add-source
  (or (when-let [rc-id (get-in state [:sym->id require])]
        [(get-in state [:sources rc-id]) state])

      ;; otherwise check if the classpath provides a symbol
      (when-let [rc (cp/find-resource-for-provide classpath require)]
        [rc state])

      ;; special cases where clojure.core.async gets aliased to cljs.core.async
      (when (str/starts-with? (str require) "clojure.")
        (let [cljs-sym
              (-> (str require)
                  (str/replace #"^clojure\." "cljs.")
                  (symbol))]

          ;; auto alias clojure.core.async -> cljs.core.async if it exists
          (when-let [rc (cp/find-resource-for-provide classpath cljs-sym)]
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
      (when-let [{:keys [resource-id ns] :as rc}
                 (find-resource-for-string state require-from (str require))]
        [rc
         (-> state
             (update :magic-syms conj require)
             (update :ns-aliases assoc require ns))])

      ;; the ns was not found, handled elsewhere
      [nil state]
      ))

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

(defn resolve-symbol-require [state require-from require]
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
            (reinspect-cljc-rc state rc reader-features))

          rc
          (if (not= ns 'cljs.test)
            rc
            ;; temp fix for the cljs.test deftest macro hack
            ;; it calls shadow.test.env/register-test but cljs.test doesn't properly
            ;; require shadow.test.env since its a hack
            (update rc :deps #(into '[shadow.test.env] %)))]

      ;; react symbol may have resolved to a JS dependency
      ;; CLJS/goog do not allow circular dependencies, JS does
      (when (contains? #{:cljs :goog} type)
        (ensure-non-circular! state resource-id))

      (-> state
          ;; in case of clojure->cljs aliases the rc may already be present
          ;; if clojure.x and cljs.x are both used in sources, the resource is
          ;; resolved twice but added once
          (data/maybe-add-source rc)
          (resolve-deps rc)
          ))))

(defn resolve-require [state require-from require]
  {:pre [(data/build-state? state)]}
  (cond
    (symbol? require)
    (resolve-symbol-require state require-from require)

    (string? require)
    (resolve-string-require state require-from require)

    :else
    (throw (ex-info "invalid require" {:entry require}))
    ))

(defn resolve-entry [state entry]
  (resolve-require state nil entry))

(defn resolve-entries
  "returns [resolved-ids updated-state] where each resolved-id can be found in :sources of the updated state"
  [state entries]
  (let [{:keys [resolved-order] :as state}
        (-> state
            (assoc
              :resolved-set #{}
              :resolved-order []
              :resolved-stack [])
            (util/reduce-> resolve-entry entries))]

    [resolved-order
     (dissoc state :resolved-order :resolved-set :resolved-stack)]))

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