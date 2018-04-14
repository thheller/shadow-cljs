(ns shadow.build.targets.expo
  (:refer-clojure :exclude (flush))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs.compiler :as cljs-comp]
            [clojure.spec.alpha :as s]
            [shadow.cljs.repl :as repl]
            [shadow.build.node :as node]
            [shadow.build :as comp]
            [shadow.build.targets.shared :as shared]
            [shadow.build.config :as config]
            [shadow.build.api :as build-api]
            [shadow.build.modules :as modules]
            [shadow.build.output :as output]
            [clojure.java.io :as io]
            [shadow.build.data :as data]
            [shadow.cljs.util :as util]
            [clojure.edn :as edn]
            [shadow.cljs.devtools.server.util :refer (pipe)]))

(s/def ::init-fn shared/unquoted-qualified-symbol?)
(s/def ::platform #{"ios" "android"})

(s/def ::target
  (s/keys
    :req-un
    [::init-fn
     ::platform
     ::shared/output-dir]
    ))

(defmethod config/target-spec :expo [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(defn configure [state mode {:keys [expo-root init-fn output-dir] :as config}]
  (let [output-dir
        (-> (io/file output-dir)
            (.getAbsoluteFile))

        expo-root
        (-> (io/file (or expo-root "."))
            (.getAbsoluteFile))

        entry
        (-> init-fn namespace symbol)]

    ;; safeguard since rn requires that the generate file is in the project somewhere
    (loop [current output-dir]
      (cond
        (nil? current)
        (throw (ex-info ":output-dir not inside :expo-root" {}))

        (= current expo-root)
        true

        :else
        (recur (.getParentFile current))))

    (-> state
        (build-api/with-build-options
          {:output-dir output-dir})

        (build-api/configure-modules
          {:index {:entries [entry]
                   :append-js (str (cljs-comp/munge init-fn) "();")}})

        (update :js-options merge {:js-provider :require})
        (assoc ::expo-root expo-root)
        (assoc-in [:compiler-options :closure-defines 'cljs.core/*target*] "react-native")

        (cond->
          ;; release mode just uses require as-is and lets the rn packager fill those in
          ;; dev mode require are filled by rn but the code it produces generates a global require fn
          ;; which uses numbers instead of names so we can't use that
          (= :dev mode)
          (assoc-in [:js-options :require-fn] "shadow$require")

          (:worker-info state)
          (-> (repl/setup)
              (shared/merge-repl-defines (assoc-in config [:devtools :autoload] true))
              (update-in [::modules/config :index :entries] shared/prepend
                '[cljs.user
                  shadow.expo.keep-awake
                  shadow.cljs.devtools.client.react-native]))))))

(defn flush-expo-bundle
  [{:keys [build-sources] ::keys [expo-root] :as state} mode {:keys [platform] :as config}]

  (let [bundle-file
        (data/output-file state (str "bundle." platform ".js"))

        rn-output-file
        (data/output-file state (str "rn." platform ".js"))

        js-requires
        (->> build-sources
             (map #(data/get-source-by-id state %))
             (filter :shadow.build.js-support/require-shim)
             (map :js-require)
             (into #{}))

        js-require-cache-file
        (data/cache-file state "js-require-cache.edn")

        js-require-cache
        (when (and (.exists rn-output-file)
                   (.exists js-require-cache-file))
          (-> (slurp js-require-cache-file)
              (edn/read-string)))]

    (io/make-parents bundle-file)

    ;; only need to run the rn packager if the js requires change
    ;; otherwise we can do everything else without it
    (when (not= js-requires js-require-cache)
      (let [rn-require-file
            (data/output-file state (str "rn-input." platform ".is"))

            rn-require-content
            (->> js-requires
                 (map #(str "SHADOW_REQUIRES[" (pr-str %) "] = require(" (pr-str %) ");"))
                 (str/join "\n"))

            cmd-args
            ["node"
             (-> (io/file expo-root "node_modules" "react-native" "local-cli" "cli.js")
                 (.getAbsolutePath))
             "bundle"
             "--entry-file" (.getAbsolutePath rn-require-file)
             "--bundle-output" (.getAbsolutePath rn-output-file)
             "--dev" "true"
             "--platform" platform
             "--sourcemap-output" (str (.getAbsolutePath rn-output-file) ".map")
             ;; FIXME: should use output-dir, should this be split for platform?
             "--assets-dest" (-> bundle-file
                                 (.getParentFile)
                                 (.getAbsolutePath))]]

        (spit rn-require-file rn-require-content)

        (util/with-logged-time [state {:type ::rn-bundle
                                       :cmd cmd-args}]

          (let [proc
                (-> (ProcessBuilder. cmd-args)
                    (.directory expo-root)
                    (.start))]

            (-> (.getOutputStream proc)
                (.close))

            (.start (Thread. (bound-fn [] (pipe proc (.getInputStream proc) *out*))))
            (.start (Thread. (bound-fn [] (pipe proc (.getErrorStream proc) *err*))))

            (let [exit (.waitFor proc)]

              (when (zero? exit)
                (spit js-require-cache-file (pr-str js-requires)))
              )))))

    (let [final-output
          (str "var SHADOW_REQUIRES = {};\n"
               "var shadow$require = function(name) { return SHADOW_REQUIRES[name]; };\n"
               (slurp rn-output-file)
               "\n\n"
               (output/closure-defines-and-base state)
               ;; used by shadow.cljs.devtools.client.env
               "\nvar $CLJS = goog.global;\n"
               (->> (for [resource-id build-sources
                          :let [{:keys [output-name] :as rc}
                                (data/get-source-by-id state resource-id)

                                {:keys [js] :as output}
                                (data/get-output! state rc)]]

                      ;; required for live-reloading but shouldn't be, this is not a browser
                      ;; we know exactly what was loaded
                      (str "goog.dependencies_.written[\"" output-name "\"] = true;\n"
                           js))
                    (str/join "\n")))]

      (io/make-parents bundle-file)

      (spit bundle-file final-output)))

  state)

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :flush
    (flush-expo-bundle state mode config)

    state
    ))