(ns shadow.build.targets.react-native
  (:refer-clojure :exclude (flush))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs.compiler :as cljs-comp]
            [cljs.analyzer :as cljs-ana]
            [clojure.spec.alpha :as s]
            [shadow.cljs.repl :as repl]
            [shadow.build.node :as node]
            [shadow.build :as build]
            [shadow.build.targets.shared :as shared]
            [shadow.build.config :as config]
            [shadow.build.api :as build-api]
            [shadow.build.modules :as modules]
            [shadow.build.output :as output]
            [clojure.java.io :as io]
            [shadow.build.data :as data]
            [shadow.cljs.util :as util]
            [shadow.cljs.devtools.api :as api]
            [shadow.build.log :as build-log]
            [shadow.build.targets.browser :as browser])
  (:import [com.google.javascript.jscomp.deps SourceCodeEscapers]
           [com.google.common.escape Escaper]))

(s/def ::init-fn qualified-symbol?)

(s/def ::target
  (s/keys
    :req-un
    [::init-fn
     ::shared/output-dir]
    ))

(defmethod config/target-spec :react-native [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(defmethod build-log/event->str ::server-addr
  [{:keys [addr]}]
  (format "Using IP: %s" addr))

(defn set-server-host [state {:keys [local-ip] :as config}]
  (let [server-addr (or local-ip (api/get-server-addr))]

    (util/log state {:type ::server-addr :addr server-addr})

    (assoc-in state
      [:compiler-options :closure-defines 'shadow.cljs.devtools.client.env/server-host]
      (str server-addr))))

(defn normalize-chunk-def [mode x]
  (let [append-key (if (= :dev mode) :append :append-js)]
    (cond
      (qualified-symbol? x)
      {:depends-on #{:index}
       :entries [(output/ns-only x)]
       append-key (str "\nmodule.exports = " (cljs-comp/munge x) ";\n")}

      (and (map? x)
           (qualified-symbol? (:exports x)))
      (-> x
          (update :depends-on util/set-conj :index)
          (assoc :entries [(output/ns-only x)])
          (update append-key str "\nmodule.exports = " (cljs-comp/munge x) ";\n"))

      :else
      (throw (ex-info "invalid :chunks config" {:x x})))))

(defn add-chunk-modules [modules mode chunks]
  (reduce-kv
    (fn [modules chunk-id chunk-def]
      (let [chunk-def (normalize-chunk-def mode chunk-def)]
        (assoc modules chunk-id chunk-def)))
    modules
    chunks))

(defn configure [state mode {:keys [chunks init-fn output-dir] :as config}]
  (let [output-dir
        (io/file output-dir)

        output-file
        (io/file output-dir "index.js")

        dev?
        (= :dev mode)

        modules
        (-> {:index {:entries [(output/ns-only init-fn)]
                     :append-js (output/fn-call init-fn)}}
            (cond->
              (seq chunks)
              (-> (update :index assoc :prepend "var $APP = global.$APP = {};\n")
                  (add-chunk-modules mode chunks))))]

    (io/make-parents output-file)

    (-> state
        (build-api/with-build-options {:output-dir output-dir})

        (assoc ::output-file output-file
               ::init-fn init-fn)

        (build-api/configure-modules modules)

        (cond->
          (seq chunks)
          (-> (assoc-in [:compiler-options :rename-prefix-namespace] "$APP")))

        (update :js-options merge {:js-provider :require})
        (update-in [:compiler-options :externs] util/vec-conj "shadow/cljs/externs/npm.js")
        (assoc-in [:compiler-options :closure-defines 'cljs.core/*target*] "react-native")

        ;; need to output sources directly to the :output-dir, not nested in cljs-runtime
        (update :build-options dissoc :cljs-runtime-path)

        (cond->
          (:worker-info state)
          (-> (shared/merge-repl-defines config)
              (set-server-host config)
              (update-in [::modules/config :index :entries] shared/prepend
                '[cljs.user
                  shadow.cljs.devtools.client.react-native]))

          dev?
          (shared/inject-preloads :index config)))))

(def ^Escaper js-escaper
  (SourceCodeEscapers/javascriptEscaper))

(defn generate-eval-js [{:keys [build-sources] :as state}]
  (reduce
    (fn [state src-id]
      (cond
        (= src-id output/goog-base-id)
        state

        ;; already generated
        (get-in state [:output src-id :eval-js])
        state

        :else
        (let [{:keys [output-name] :as rc} (data/get-source-by-id state src-id)
              {:keys [js] :as output} (data/get-output! state rc)
              source-map? (output/has-source-map? output)

              code
              (cond-> js
                source-map?
                ;; FIXME: the url here isn't really used, wonder if there is a way to do something useful here
                (str "\n//# sourceURL=http://localhost:8081/app/" output-name "\n"
                     ;; "\n//# sourceMappingURL=http://localhost:8081/app/cljs-runtime/" output-name ".map\n"
                     ;; FIXME: inline map saves having to know the actual URL
                     (output/generate-source-map-inline state rc output nil)
                     ))]

          ;; pre-cache for later so it doesn't get regenerated on hot-compiles
          (assoc-in state [:output src-id :eval-js]
            (str "SHADOW_ENV.evalLoad(\"" output-name "\", \"" (.escape js-escaper ^String code) "\");")))))
    state
    build-sources))

(defn eval-load-sources [state sources]
  (->> sources
       ;; generated earlier to avoid regenerating all the time
       (map #(get-in state [:output % :eval-js]))
       (str/join "\n")))

(defn flush-unoptimized!
  [state {:keys [goog-base output-name prepend append sources web-worker] :as mod}]

  (let [target
        (data/output-file state output-name)

        source-loads
        (eval-load-sources state sources)

        js-requires
        (into #{}
          (for [src-id sources
                :let [{:keys [type] :as src} (data/get-source-by-id state src-id)]
                :when (= :cljs type)
                :let [js-requires (get-in state [:compiler-env ::cljs-ana/namespaces (:ns src) :shadow/js-requires])]
                js-require js-requires]
            js-require))

        out
        (str prepend
             (->> (for [src-id sources
                        :let [{:keys [js-require] :as src} (data/get-source-by-id state src-id)]
                        :when (:shadow.build.js-support/require-shim src)]
                    ;; emit actual require(...) calls so metro can process those and make them available
                    (str "$CLJS.shadow$js[\"" js-require "\"] = function() { return require(\"" js-require "\"); };"))
                  (str/join "\n"))
             "\n\n"

             (->> js-requires
                  (map (fn [require]
                         (str "$CLJS.shadow$js[\"" require "\"] = function() { return require(\"" require "\"); };")))
                  (str/join "\n"))

             "\n\n"
             source-loads
             append)

        out
        (if (or goog-base web-worker)
          (str "var $CLJS = global;\n"
               "var shadow$start = new Date().getTime();\n"
               "var shadow$provide = {};\n"

               ;; needed since otherwise goog/base.js code will goog.define incorrectly
               "var goog = global.goog = {};\n"
               "global.CLOSURE_DEFINES = " (output/closure-defines-json state) ";\n"

               (let [goog-base (get-in state [:output output/goog-base-id :js])]
                 (str goog-base "\n"))

               (let [{:keys [polyfill-js]} state]
                 (when (and (or goog-base web-worker) (seq polyfill-js))
                   (str "\n" polyfill-js)))

               (slurp (io/resource "shadow/boot/react-native.js"))
               "\n\n"
               ;; create the $CLJS var so devtools can always use it
               ;; always exists for :module-format :js
               "goog.global[\"$CLJS\"] = goog.global;\n"
               "\n\n"
               out

               "\n\n"
               "console.log(\"dev init time\", new Date().getTime() - shadow$start);\n")
          ;; else
          out)]

    (io/make-parents target)
    (spit target out)))

(defmethod build-log/event->str ::flush-dev
  [{:keys [module-id] :as event}]
  (format "Flush module: %s" (name module-id)))

(defn flush [state mode config]
  (case mode
    :dev
    (let [state (generate-eval-js state)]
      (do (output/flush-sources state)
          (doseq [mod (:build-modules state)]
            (util/with-logged-time
              [state {:type ::flush-dev :module-id (:module-id mod)}]
              (flush-unoptimized! state mod)))))
    :release
    (output/flush-optimized state))

  state)

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :compile-prepare
    (node/replace-goog-global state)

    :flush
    (flush state mode config)

    state
    ))

(comment
  (shadow.cljs.devtools.api/compile :expo-ios))

(comment
  (shadow.cljs.devtools.api/watch :expo-ios {:verbose true}))