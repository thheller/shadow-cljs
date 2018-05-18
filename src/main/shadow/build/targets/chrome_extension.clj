(ns shadow.build.targets.chrome-extension
  (:require
    [clojure.java.io :as io]
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.pprint :refer (pprint)]
    [shadow.build :as b]
    [shadow.build.targets.browser :as browser]
    [shadow.build.targets.shared :as shared]
    [shadow.cljs.repl :as repl]
    [shadow.build.api :as build-api]
    [shadow.cljs.devtools.api :as api]
    [shadow.build.modules :as modules]
    [shadow.build.output :as output]
    [shadow.build.data :as data]
    [shadow.cljs.util :as util]
    [clojure.edn :as edn]
    [clojure.walk :as walk]))

(defn configure
  [state mode {:keys [extension-dir] :as config}]
  (let [extension-dir
        (io/file extension-dir)

        manifest
        (-> (io/file extension-dir "manifest.edn")
            (slurp)
            (edn/read-string))

        output-dir
        (io/file extension-dir "out")

        manifest-file
        (io/file extension-dir "manifest.json")

        background
        (get-in manifest [:background :entry])

        content-script
        (some-> manifest
          (get :content-scripts)
          (get 0)
          (get :entry))

        modules
        (-> {:shared {:entries ['cljs.core]
                      :default true}}
            (cond->
              background
              (assoc :background {:entries [background] :depends-on #{:shared}})

              content-script
              (assoc :content-script {:entries [content-script] :depends-on #{:shared}})))]

    (-> state
        (build-api/merge-build-options
          {:output-dir output-dir
           :asset-path "out"})

        (build-api/with-js-options
          {:js-provider :shadow})

        (assoc ::manifest-file manifest-file
               ::manifest manifest
               ::extension-dir extension-dir)

        (cond->
          (and (= :dev mode) (:worker-info state))
          (-> (repl/setup)
              (shared/merge-repl-defines config)))

        (browser/configure-modules mode (assoc config :modules modules)))))

(defn mod-files [state {:keys [module-id sources] :as mod}]
  (->> (let [mods (browser/get-all-module-deps state mod)]
         (-> []
             (into (mapcat :sources) mods)
             (into sources)))
       (remove #{output/goog-base-id})
       (map #(data/get-source-by-id state %))
       (map :output-name)
       (map #(str "out/cljs-runtime/" %))))

(defn flush-base [state mod filename]
  (spit (data/output-file state filename)

    (str "var shadow$provide = {};\n"

         (let [{:keys [polyfill-js]} state]
           (when (seq polyfill-js)
             (str "\n" polyfill-js)))

         (output/closure-defines-and-base state)

         "var $CLJS = {};\n"
         "goog.global[\"$CLJS\"] = $CLJS;\n"

         (slurp (io/resource "shadow/boot/static.js"))
         "\n\n"
         ;; create the $CLJS var so devtools can always use it
         ;; always exists for :module-format :js
         "\n\n")))

(defn flush-dev
  [{::keys [manifest-file manifest] :keys [build-modules] :as state}]

  (output/flush-sources state)

  (let [{:keys [background content-script] :as mods-by-id}
        (reduce
          (fn [m {:keys [module-id] :as mod}]
            (assoc m module-id mod))
          {}
          build-modules)

        manifest
        (-> manifest
            (update :content-security-policy (fn [x] (if (string? x) x (str/join " " x))))
            (cond->
              background
              (-> (update :background dissoc :entry)
                  (assoc-in [:background :scripts]
                    (->> (mod-files state background)
                         (into ["out/background.js"]))))

              content-script
              (-> (update-in [:content-scripts 0] dissoc :entry)
                  (assoc-in [:content-scripts 0 :js]
                    (->> (mod-files state content-script)
                         (into ["out/content_script.js"]))))
              ))]

    (when background
      (flush-base state background "background.js"))

    (when content-script
      (flush-base state content-script "content_script.js"))

    (spit manifest-file
      (with-out-str
        (json/pprint manifest
          :escape-slash false
          :key-fn (fn [key]
                    (-> key name (str/replace #"-" "_"))))))

    #_ (pprint manifest)

    state))

(defn update-manifest-release [state]
  (throw (ex-info "not implemented yet" {}))
  state)

(defn process
  [{::b/keys [stage mode config] :as state}]
  state
  (cond
    (= :configure stage)
    (configure state mode config)

    (and (= :dev mode) (= :flush stage))
    (-> state
        (flush-dev))

    (and (= :release mode) (= :flush stage))
    (-> state
        (output/flush-optimized)
        (update-manifest-release))

    :else
    state
    ))

(comment
  (shadow.cljs.devtools.api/compile :chrome-ext))

(comment
  (shadow.cljs.devtools.api/watch :chrome-ext {:verbose true}))



