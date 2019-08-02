(ns shadow.build.targets.react-native
  (:refer-clojure :exclude (flush))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs.compiler :as cljs-comp]
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

(defn configure [state mode {:keys [build-id init-fn output-dir] :as config}]
  (let [output-dir
        (io/file output-dir)

        output-file
        (io/file output-dir "index.js")

        dev?
        (= :dev mode)]

    (io/make-parents output-file)

    (-> state
        (build-api/with-build-options {:output-dir output-dir})
        (cond-> dev? (assoc-in [:js-options :require-fn] "$CLJS.shadow$jsRequire"))

        (assoc ::output-file output-file
               ::init-fn init-fn)

        (build-api/configure-modules
          {:index {:entries [(output/ns-only init-fn)]
                   :append-js (output/fn-call init-fn)}})

        (update :js-options merge {:js-provider :require})
        (assoc-in [:compiler-options :closure-defines 'cljs.core/*target*] "react-native")

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

(defn eval-load-sources [state sources]
  (->> sources
       (remove #{output/goog-base-id})
       (map #(data/get-source-by-id state %))
       (map (fn [{:keys [output-name] :as rc}]
              (let [{:keys [js] :as output} (data/get-output! state rc)

                    source-map? (output/has-source-map? output)

                    code
                    (cond-> js
                      source-map?
                      ;; FIXME: the url here isn't really used, wonder if there is a way to do something useful here
                      (str "\n//# sourceURL=http://localhost:8081/app/cljs-runtime/" output-name "\n"
                           ;; "\n//# sourceMappingURL=http://localhost:8081/app/cljs-runtime/" output-name ".map\n"
                           ;; FIXME: inline map is more expensive to generate but saves having to know the actual URL
                           (output/generate-source-map-inline state rc output nil)
                           ))]

                (str "SHADOW_ENV.evalLoad(\"" output-name "\", \"" (.escape js-escaper ^String code) "\");")
                )))
       (str/join "\n")))

(defn flush-unoptimized!
  [{:keys [unoptimizable build-options] :as state}
   {:keys [goog-base output-name prepend append sources web-worker] :as mod}]

  (let [target
        (data/output-file state output-name)

        source-loads
        (eval-load-sources state sources)

        out
        (str prepend
             (->> (for [src-id sources
                        :let [{:keys [js-require] :as src} (data/get-source-by-id state src-id)]
                        :when (:shadow.build.js-support/require-shim src)]
                    ;; emit actual require(...) calls so metro can process those and make them available
                    (str "$CLJS.shadow$js[\"" js-require "\"] = require(\"" js-require "\");"))
                  (str/join "\n"))
             "\n"
             source-loads
             append)

        out
        (if (or goog-base web-worker)
          (str unoptimizable
               ;; always include this in dev builds
               ;; a build may not include any shadow-js initially
               ;; but load some from the REPL later
               "var shadow$provide = {};\n"
               "var $CLJS = global;\n"

               "$CLJS.shadow$js = {};\n"
               "$CLJS.shadow$jsRequire = function(name) { return $CLJS.shadow$js[name]; };"

               (let [{:keys [polyfill-js]} state]
                 (when (and (or goog-base web-worker) (seq polyfill-js))
                   (str "\n" polyfill-js)))

               "global.CLOSURE_DEFINES = " (output/closure-defines-json state) ";\n"

               (let [goog-rc (get-in state [:sources output/goog-base-id])
                     goog-base (get-in state [:output output/goog-base-id :js])]

                 (str goog-base "\n"))

               "global.goog = goog;\n"

               (slurp (io/resource "shadow/boot/react-native.js"))
               "\n\n"
               ;; create the $CLJS var so devtools can always use it
               ;; always exists for :module-format :js
               "goog.global[\"$CLJS\"] = goog.global;\n"
               "\n\n"
               out)
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
    (do (output/flush-sources state)
        (doseq [mod (:build-modules state)]
          (util/with-logged-time
            [state {:type ::flush-dev :module-id (:module-id mod)}]
            (flush-unoptimized! state mod))))
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