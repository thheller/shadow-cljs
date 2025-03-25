(ns shadow.build.targets.esm-files
  (:refer-clojure :exclude (flush require resolve))
  (:require
    [cljs.compiler :as comp]
    [clojure.spec.alpha :as s]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [cljs.compiler :as cljs-comp]
    [shadow.build :as build]
    [shadow.build.classpath :as classpath]
    [shadow.build.modules :as modules]
    [shadow.build.api :as build-api]
    [shadow.build.output :as output]
    [shadow.build.targets.shared :as shared]
    [shadow.build.targets.browser :as browser]
    [shadow.build.targets.esm :as esm]
    [shadow.build.config :as config]
    [shadow.build.node :as node]
    [shadow.build.js-support :as js-support]
    [shadow.build.closure :as closure]
    [shadow.build.test-util :as tu]
    [shadow.cljs.util :as util]
    [shadow.build.data :as data]
    [shadow.build.async :as async]
    [clojure.set :as set]
    [shadow.core-ext :as core-ext]))

(defn configure
  [{::build/keys [config mode] :as state}]
  (let [{:keys [entries runtime output-dir]} config]

    (-> state
        (cond->
          (not (get-in config [:js-options :js-provider]))
          (build-api/with-js-options {:js-provider :import}))

        (assoc ::closure/esm true)

        ;; FIXME: hardcoded to disable these since there is a bit of a loading problem
        ;; need to ensure the devtools namespaces are loaded properly
        ;; but we might not have clearly defined :entries to prepend them too
        ;; this means :preloads also do not work properly
        ;; leaving this for later, first need to figure other stuff out
        (assoc-in [::build/config :devtools :enabled] false)

        (build-api/with-build-options
          {:greedy (nil? entries)
           ;; will end up calling :resolve step
           :dynamic-resolve true
           ;; we don't want ^:export meta to end up as
           ;; goog.exportSymbol since we are using this for ESM exports
           :no-goog-exports true})

        ;; will be replaced after compilation
        (build-api/configure-modules
          {:main
           {:entries []
            :depends-on #{}}})

        (assoc-in [:compiler-options :emit-use-strict] false)

        (cond->
          (not (get-in config [:compiler-options :output-feature-set]))
          (build-api/with-compiler-options {:output-feature-set :es2020})

          output-dir
          (build-api/with-build-options {:output-dir (io/file output-dir)})

          (= :node runtime)
          (node/set-defaults)

          (= :release mode)
          (assoc-in [:compiler-options :rename-prefix-namespace] "$APP")

          (and (= :dev mode) (:worker-info state))
          (shared/merge-repl-defines config)
          ))))

(defn resolve [{:keys [classpath] ::build/keys [mode config] :as state}]
  (let [{:keys [entries runtime preloads]} config]
    (-> state
        (update-in [::modules/config :main]
          (fn [module-config]
            (let [repl?
                  (some? (:worker-info state))

                  entries
                  (cond
                    (seq entries)
                    (vec entries)

                    ;; FIXME: this isn't really about testing but the options may fit
                    (contains? config :ns-regexp)
                    (tu/find-test-namespaces state config)

                    :all
                    (classpath/find-cljs-namespaces-in-files classpath nil))

                  entries
                  (cond-> entries
                    (and (= :dev mode) (seq preloads))
                    (shared/prepend preloads)

                    (and (= :dev mode) (seq (get-in config [:devtools :preloads])))
                    (shared/prepend (get-in config [:devtools :preloads])))]

              (-> module-config
                  (assoc :entries entries)
                  (cond->
                    (and repl? (or (= :browser runtime) (nil? runtime)))
                    (-> (browser/inject-repl-client state config)
                        (browser/inject-devtools-console state config))

                    (and repl? (= :node runtime))
                    (update :entries shared/prepend '[shadow.cljs.devtools.client.npm-module])

                    (and repl? (= :react-native runtime))
                    (-> (update :entries shared/prepend '[shadow.cljs.devtools.client.react-native])
                        ;; rn itself doesn't support this but the remote debug chrome thing does
                        (browser/inject-devtools-console state config)))

                  ))))
        (modules/analyze))))

(defn flush-source
  [state src-id]
  (let [{:keys [resource-name output-name last-modified] :as src}
        (data/get-source-by-id state src-id)

        {:keys [js compiled-at] :as output}
        (data/get-output! state src)

        js-file
        (if-some [sub-path (get-in state [:build-options :cljs-runtime-path])]
          (data/output-file state sub-path output-name)
          (data/output-file state output-name))]

    ;; skip files we already have
    (when (or (not (.exists js-file))
              (zero? last-modified)
              ;; js is not compiled but maybe modified
              (> (or compiled-at last-modified)
                 (.lastModified js-file)))

      (io/make-parents js-file)

      (util/with-logged-time
        [state {:type :flush-source
                :resource-name resource-name}]

        (let [prepend
              (str "import \"./cljs_env.js\";\n"
                   (->> (data/deps->syms state src)
                        (remove #{'goog})
                        (remove (:dead-js-deps state))
                        (map #(data/get-source-id-by-provide state %))
                        (distinct)
                        (map #(data/get-source-by-id state %))
                        (map (fn [{:keys [output-name] :as x}]
                               (str "import \"./" output-name "\";")))
                        (str/join "\n"))
                   "\n")

              output
              (str prepend
                   js
                   (output/generate-source-map state src output js-file prepend))]
          (spit js-file output))))))

(defn flush-unoptimized-module
  [{:keys [worker-info build-modules] :as state}
   {:keys [module-id output-name prepend append sources depends-on] :as mod}]

  (doseq [src-id sources]
    (async/queue-task state #(flush-source state src-id)))

  (let [module-imports
        (when (seq depends-on)
          (->> (reverse build-modules)
               (filter #(contains? depends-on (:module-id %)))
               (map :output-name)
               (map #(str "import \"./" % "\";"))
               (str/join "\n")))

        imports
        (->> sources
             (remove #{output/goog-base-id})
             (map #(data/get-source-by-id state %))
             (map (fn [{:keys [output-name] :as rc}]

                    (str "import \"./cljs-runtime/" output-name "\";\n"
                         "SHADOW_ENV.setLoaded(" (pr-str output-name) ");"
                         )))
             (str/join "\n")
             (str "import \"./cljs-runtime/cljs_env.js\";\n"))

        exports
        (when-some [ns (::mod-ns mod)]
          (->> (get-in state [:compiler-env :cljs.analyzer/namespaces ns :defs])
               (vals)
               (filter #(or (get-in % [:meta :export])
                            (get-in % [:meta :export-as])))
               (sort-by #(:line % 0))
               (map (fn [{def :name :as ns-def}]
                      (let [export-as (get-in ns-def [:meta :export-as])
                            export-name
                            (if (string? export-as)
                              ;; no munging for user specified export names
                              ;; still needs to be a string
                              export-as
                              (let [export-name (-> def name str)]
                                (if (= "default" export-name)
                                  export-name
                                  (-> export-name (comp/munge)))))]

                        ;; shadow$export indirection which replaced by ShadowESMExports after optimizations
                        ;; have to do this since there is currently no way to tell the closure compiler to
                        ;; generate these for us, and just adding export directly makes closure compiler unhappy
                        (if (= export-name "default")
                          (str "export default " (cljs-comp/munge def) ";")
                          (str "export let " export-name " = " (cljs-comp/munge def) ";")))))
               (str/join "\n")))

        out
        (str prepend "\n"
             module-imports "\n"
             imports "\n"
             exports "\n"
             append "\n"
             (when (and worker-info (not (false? (get-in state [::build/config :devtools :enabled]))))
               (str "shadow.cljs.devtools.client.env.module_loaded(\"" (name module-id) "\");")))]


    ;; only write if output changed, avoids confusing other watchers
    (if (= out (get-in state [::dev-modules module-id]))
      state
      (let [target (data/output-file state output-name)]
        (io/make-parents target)
        (spit target out)
        (assoc-in state [::dev-modules module-id] out)
        ))))

(defn js-module-env
  [{:keys [polyfill-js]
    ::build/keys [config]
    :as state}]

  (->> ["globalThis.CLOSURE_DEFINES = " (output/closure-defines-json state) ";"
        "globalThis.CLOSURE_NO_DEPS = true;"
        (get-in state [:output output/goog-base-id :js])
        "globalThis.goog = goog;"
        "globalThis.shadow$provide = {};"
        ;; only include helper fn if shadow.esm namespace is actually required
        ;; otherwise confuses vite
        (when (get-in state [:sym->id 'shadow.esm])
          "globalThis.shadow_esm_import = function(x) { return import(x.startsWith(\"./\") ? \".\" + x : x); }")
        "let $CLJS = globalThis.$CLJS = globalThis;"
        (slurp (io/resource "shadow/boot/esm.js"))

        (when (seq polyfill-js)
          (str polyfill-js "\n"
               "globalThis.$jscomp = $jscomp;\n"))]
       (remove nil?)
       (str/join "\n")))

(defn flush-dev-module-env [state]
  (let [env-content (js-module-env state)]
    ;; only actually touch file if needed, avoids confusing other watchers
    (if (= env-content (::env-content state))
      state
      (let [env-file (data/output-file state "cljs-runtime" "cljs_env.js")]
        (io/make-parents env-file)
        (spit env-file env-content)
        (assoc state ::env-content env-content)))))

(defn flush-dev [{::build/keys [config] :keys [build-modules] :as state}]
  (when-not (seq build-modules)
    (throw (ex-info "flush before compile?" {})))

  (util/with-logged-time
    [state {:type :flush-unoptimized}]
    (-> state
        (flush-dev-module-env)
        (util/reduce->
          (fn [state mod]
            (flush-unoptimized-module state mod))
          build-modules))))

(defn inject-polyfill-js [{:keys [polyfill-js] :as state}]
  (update-in state [::closure/modules 0 :prepend] str
    (if (seq polyfill-js)
      polyfill-js
      "export const $jscomp = {};\n")))

(defn setup-imports [state]
  (let [js-import-sources
        (->> (:build-sources state)
             (map #(data/get-source-by-id state %))
             (mapcat #(data/deps->syms state %))
             (set)
             (map #(data/get-source-by-provide state %))
             (filter ::js-support/import-shim))

        imports
        (reduce
          (fn [imports {:keys [js-import import-alias]}]
            ;; import-alias is a symbol
            (assoc imports (name import-alias) js-import))
          {}
          js-import-sources)]

    (-> state
        (assoc ::closure/esm-imports imports))))

(defn add-first [a b]
  (into [b] a))

(defn set-build-modules [{::modules/keys [module-order modules] :as state}]
  (assoc state :build-modules (mapv #(get modules %) module-order)))

(defn build-module-per-ns [{:keys [build-sources] :as state}]
  (-> state
      (assoc
        ::modules/module-order []
        ::modules/modules
        {:cljs_env
         {:module-id :cljs_env
          :depends-on #{}
          :goog-base true
          :module-name "cljs_env"
          :output-name "cljs_env.js"
          :prepend "export const $APP = {};\nexport const shadow$provide = {};\n"
          :sources []}})
      (util/reduce->
        (fn [state src-id]
          (let [{:keys [type ns]} (get-in state [:sources src-id])]
            (case type
              :cljs
              (let [output (get-in state [:output src-id])
                    ns-module-id (keyword (str ns))]
                (-> state
                    (update-in [::modules/module-order] add-first ns-module-id)
                    (assoc-in [::modules/modules ns-module-id]
                      {:module-id ns-module-id
                       :module-name (str ns)
                       :output-name (str ns ".js")
                       ::mod-ns ns
                       ;; boilerplate per ns that closure isn't supposed to see
                       :prepend
                       (str
                         ;; only create shadow_esm_import if shadow.esm was required anywhere
                         ;; needs to be created in all modules since it must be module local
                         (when (get-in state [:sym->id 'shadow.esm])
                           "const shadow_esm_import = function(x) { return import(x) };\n")

                         ;; in dev the individual files do this
                         (when (= :release (:shadow.build/mode state))
                           (str "import { $APP, shadow$provide, $jscomp } from \"./cljs_env.js\";\n"

                                ;; need to import all the other namespaces to ensure they are loaded
                                ;; otherwise will be accessing properties of $APP that aren't yet defined
                                (->> (cond-> (:used-var-namespaces output)
                                       ;; cljs.core is not contained in used-var-namespaces if no actual core functions
                                       ;; were used, but we will need it for keywords and the collections
                                       (not= ns 'cljs.core)
                                       (conj 'cljs.core))
                                     (remove #{'js ns})
                                     (map (fn [sym]
                                            (let [other-id (get-in state [:sym->id sym])
                                                  other-src (get-in state [:sources other-id])]
                                              (when (= :cljs (:type other-src))
                                                (str "import \"./" sym ".js\";")))))
                                     (remove nil?)
                                     (str/join "\n")))))

                       ;; using actual used vars since require may not contain everything
                       ;; in case of macros emitting direct access to vars the macros required
                       ;; but the source ns didn't
                       :depends-on (->> (:used-var-namespaces output)
                                        (remove #{'js ns})
                                        (map (fn [sym]
                                               (let [other-id (get-in state [:sym->id sym])
                                                     other-src (get-in state [:sources other-id])]
                                                 (when (= :cljs (:type other-src))
                                                   (keyword sym)))))
                                        (remove nil?)
                                        (into (cond-> #{:cljs_env}
                                                (not= ns 'cljs.core)
                                                (conj :cljs.core))))
                       :sources [src-id]})))

              ;; FIXME: should :shadow-js go into separate files?
              ;; all in base is not great if this is getting post-processed in any way, but for those cases
              ;; people should be using :js-provider :import anyway?

              ;; everything else ends up in base module, mostly goog sources
              (update-in state [::modules/modules :cljs_env :sources] add-first src-id))))

        ;; sources are already in ideal order
        ;; so walking backwards to maintain order and not having to look forward
        (reverse build-sources))

      (update ::modules/module-order add-first :cljs_env)
      (set-build-modules)))

(defn add-esm-exports [{:keys [build-sources] :as state}]
  (reduce
    (fn [state src-id]
      (let [{:keys [type ns]} (get-in state [:sources src-id])]
        (cond
          (not= type :cljs)
          state

          ;; safeguard to only do this once
          (get-in state [:output src-id ::patched])
          state

          :else
          (let [exported-defs
                (->> (get-in state [:compiler-env :cljs.analyzer/namespaces ns :defs])
                     (vals)
                     (filter #(or (get-in % [:meta :export])
                                  (get-in % [:meta :export-as])))
                     (sort-by #(:line % 0))
                     (map (fn [{def :name :as ns-def}]
                            (let [export-as (get-in ns-def [:meta :export-as])
                                  export-name
                                  (if (string? export-as)
                                    ;; no munging for user specified export names
                                    ;; still needs to be a string
                                    export-as
                                    (let [export-name (-> def name str)]
                                      (if (= "default" export-name)
                                        export-name
                                        (-> export-name (comp/munge)))))]

                              ;; shadow$export indirection which replaced by ShadowESMExports after optimizations
                              ;; have to do this since there is currently no way to tell the closure compiler to
                              ;; generate these for us, and just adding export directly makes closure compiler unhappy
                              (str "shadow$export(" (core-ext/safe-pr-str export-name) ", " (comp/munge def) ");"))))
                     (str/join "\n"))]

            (-> state
                (assoc-in [:output src-id ::patched] true)
                (update-in [:output src-id :js] str "\n\n" exported-defs))))))
    state
    build-sources))

(defn process
  [{::build/keys [mode stage] :as state}]
  (cond
    (= stage :configure)
    (configure state)

    (= stage :resolve)
    (resolve state)

    (= stage :compile-prepare)
    (-> state
        (esm/replace-goog-global)
        (cond->
          (= :release mode)
          (setup-imports)))

    (= :compile-finish stage)
    (-> state
        (cond->
          (= :release mode)
          (add-esm-exports))
        (build-module-per-ns))

    (= stage :flush)
    (case mode
      :dev
      (flush-dev state)
      :release
      (-> state
          (inject-polyfill-js)
          (output/flush-optimized)))

    :else
    state))
