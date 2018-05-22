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

(defn extract-content-scripts
  [modules manifest]
  (reduce-kv
    (fn [modules idx {entry :shadow/entry :as content-script}]
      (if-not entry
        modules
        (let [mod-id (keyword (str "content-script-" idx))]
          (assoc modules mod-id {:entries [entry]
                                 :depends-on #{:shared}
                                 ::module-type :content-script
                                 ::idx idx}))))
    modules
    (get manifest :content-scripts)))

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
        (get-in manifest [:background :shadow/entry])

        modules
        (-> {:shared {:entries ['cljs.core]
                      :default true}}
            (cond->
              background
              (assoc :background {:entries [background]
                                  :depends-on #{:shared}
                                  ::module-type :background})

              (get manifest :content-scripts)
              (extract-content-scripts manifest)))

        config
        (update config :devtools merge
          {:use-document-host false
           :autoload true})]

    (-> state
        (assoc ::b/config config)
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

(defn flush-base [state {:keys [output-name] :as mod}]
  (spit (data/output-file state output-name)

    (str "var shadow$provide = {};\n"

         (let [{:keys [polyfill-js]} state]
           (when (seq polyfill-js)
             (str "\n" polyfill-js)))

         (output/closure-defines-and-base state)

         "var $CLJS = {};\n"
         "goog.global[\"$CLJS\"] = $CLJS;\n"

         ;; not using a debug loader since chrome loads the files for us
         (slurp (io/resource "shadow/boot/static.js"))
         "\n\n")))

(defn mod-files [{::b/keys [mode] :as state} {:keys [output-name sources] :as mod}]
  (let [mods (browser/get-all-module-deps state mod)]

    (case mode
      :release
      (-> []
          (into (map #(str "out/" (:output-name %))) mods)
          (conj (str "out/" output-name)))

      :dev
      (->> (-> []
               (into (mapcat :sources) mods)
               (into sources))
           (remove #{output/goog-base-id})
           (map #(data/get-source-by-id state %))
           (map :output-name)
           (map #(str "out/cljs-runtime/" %))
           (into [(str "out/" output-name)])))))

(defn flush-manifest
  [{::keys [manifest-file manifest] :keys [build-modules] :as state}]

  (let [manifest
        (-> manifest
            (update :content-security-policy (fn [x] (if (string? x) x (str/join " " x))))
            (util/reduce->
              (fn [manifest {::keys [module-type idx] :as mod}]
                (case module-type
                  :background
                  (-> manifest
                      (update :background dissoc :shadow/entry)
                      (assoc-in [:background :scripts] (mod-files state mod)))

                  :content-script
                  (-> manifest
                      (update-in [:content-scripts idx] dissoc :shadow/entry)
                      (assoc-in [:content-scripts idx :js] (mod-files state mod)))

                  (throw (ex-info "invalid module config" {:mod mod}))))
              ;; skip shared which is always first
              (rest build-modules)))]

    (spit manifest-file
      (with-out-str
        (json/pprint manifest
          :escape-slash false
          :key-fn (fn [key]
                    (-> key name (str/replace #"-" "_"))))))

    #_(pprint manifest)

    state))

(defn flush-dev
  [{:keys [build-modules] :as state}]
  (output/flush-sources state)
  (doseq [mod (rest build-modules)]
    (flush-base state mod))
  (flush-manifest state))

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
        (flush-manifest))

    :else
    state
    ))

(comment
  (shadow.cljs.devtools.api/compile :chrome-ext))

(comment
  (shadow.cljs.devtools.api/release :chrome-ext))

(comment
  (shadow.cljs.devtools.api/watch :chrome-ext {:verbose true}))



