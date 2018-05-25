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
    [clojure.edn :as edn]))


(defn extract-outputs
  [modules {:shadow/keys [outputs] :as manifest}]
  (reduce-kv
    (fn [modules mod-id {:keys [output-type] :as mod}]
      (assoc modules mod-id
                     (-> mod
                         (update :depends-on util/set-conj :shared)
                         (cond->
                           (and (not= :chrome/content-script output-type)
                                (not= :chrome/content-inject output-type))
                           (update :depends-on util/set-conj :bg-shared)

                           (and (nil? output-type) (= :background mod-id))
                           (assoc :output-type :chrome/background)

                           (and (nil? output-type) (= :content-script mod-id))
                           (assoc :output-type :chrome/content-script)

                           (and (nil? output-type) (= :browser-action mod-id))
                           (assoc :output-type :chrome/browser-action)

                           (and (nil? output-type) (= :page-action mod-id))
                           (assoc :output-type :chrome/page-action)
                           ))))
    modules
    outputs))

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

        modules
        (-> {:shared
             {:entries ['cljs.core]
              :default true}
             :bg-shared
             {:entries []
              :depends-on #{:shared}}}
            (extract-outputs manifest))

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

(defn flush-base [state {:keys [output-type output-name] :as mod}]
  (spit (data/output-file state output-name)
    (str "var shadow$provide = {};\n"

         (let [{:keys [polyfill-js]} state]
           (when (seq polyfill-js)
             (str "\n" polyfill-js)))

         (output/closure-defines-and-base state)

         "var $CLJS = this;\n"
         "goog.global[\"$CLJS\"] = $CLJS;\n"

         (case output-type
           ;; these are controlled via the scripts/js properties and
           ;; support loading multiple files so no loader support is required
           (:chrome/background :chrome/content-script)
           (slurp (io/resource "shadow/boot/static.js"))

           ;; special case for thing that require on isolated file
           ;; specifically chrome.tabs.executeScript(id, {file: "something.js"})
           :chrome/single-file
           (str (slurp (io/resource "shadow/boot/static.js"))
                (let [mods (-> (browser/get-all-module-deps state mod)
                               (conj mod))]
                  (->> mods
                       (mapcat :sources)
                       (remove #{output/goog-base-id})
                       (map #(data/get-source-by-id state %))
                       (map #(data/get-output! state %))
                       (map :js)
                       (str/join "\n"))
                  ))

           ;; anything else assumes that can load files via normal browser methods
           (str (slurp (io/resource "shadow/boot/browser.js"))
                (let [mods (-> (browser/get-all-module-deps state mod)
                               (conj mod))

                      require-files
                      (->> mods
                           (mapcat :sources)
                           (remove #{output/goog-base-id})
                           (map #(data/get-source-by-id state %))
                           (map :output-name))]
                  (str "SHADOW_ENV.load({}, " (json/write-str require-files) ");\n")))

           ))))

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
            (dissoc :shadow/outputs)
            (update :content-security-policy (fn [x] (if (string? x) x (str/join " " x))))
            (util/reduce->
              (fn [manifest {:keys [output-type] :as mod}]
                (case output-type
                  :chrome/background
                  (update manifest :background
                    #(merge
                       {:scripts (mod-files state mod)
                        :persistent false}
                       %))

                  :chrome/content-script
                  (-> manifest
                      (update :content-scripts util/vec-conj
                        (merge
                          {:js (mod-files state mod)}
                          (:chrome/options mod))))

                  ;; nothing to do for other output-types
                  manifest))
              ;; skip shared which is always first
              (rest build-modules)))]

    #_(pprint manifest)

    (spit manifest-file
      (with-out-str
        (binding [*print-length* nil] ;; grr cider for setting *print-length* in nrepl
          (json/pprint manifest
            :escape-slash false
            :key-fn (fn [key]
                      (-> key name (str/replace #"-" "_")))))))

    state))

(defn flush-single-file-for-module
  [state {:keys [output-name] :as mod}]

  (let [mods (-> (browser/get-all-module-deps state mod)
                 (conj mod))

        ;; FIXME: create index source map properly
        js
        (str "var shadow$provide = {};\n"
             (->> mods
                  (mapcat :sources)
                  (map #(data/get-source-by-id state %))
                  (filter #(= :shadow-js (:type %)))
                  (map #(data/get-output! state %))
                  (map :js)
                  (map #(str % ";"))
                  (str/join "\n")))

        {:keys [js source-map]}
        (reduce
          (fn [m {:keys [prepend output append] :as mod}]
            (update m :js str prepend output append))
          {:js js
           :source-map {}}
          mods)

        mod-file
        (data/output-file state output-name)]

    (spit mod-file js)
    ))

(defmethod output/flush-optimized-module :chrome/single-file
  [state mod]
  (flush-single-file-for-module state mod))

(defmethod output/flush-optimized-module :chrome/content-inject
  [state mod]
  (flush-single-file-for-module state mod))

(defmethod output/flush-optimized-module :chrome/background
  [state mod]
  (output/flush-optimized-module state (assoc mod :output-type ::output/default)))

(defmethod output/flush-optimized-module :chrome/content-script
  [state mod]
  (output/flush-optimized-module state (assoc mod :output-type ::output/default)))


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



