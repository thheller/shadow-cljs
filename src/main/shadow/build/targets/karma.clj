(ns shadow.build.targets.karma
  (:refer-clojure :exclude (compile flush resolve))
  (:require [clojure.string :as str]
            [shadow.build :as build]
            [shadow.build.modules :as modules]
            [shadow.build.classpath :as cp]
            [shadow.build.targets.browser :as browser]
            [shadow.cljs.util :as util]
            [hiccup.page :refer (html5)]
            [clojure.java.io :as io]
            [cljs.compiler :as cljs-comp]
            [shadow.build.api :as build-api]
            [shadow.build.output :as output]
            [shadow.build.data :as data]))

(defn configure [state mode {:keys [runner-ns output-to js-options] :or {runner-ns 'shadow.test.karma} :as config}]
  (let [output-to
        (io/file output-to)

        output-dir
        (.getParentFile output-to)]

    (io/make-parents output-to)

    (-> state
        (assoc
          ::runner-ns runner-ns
          ::output-to output-to)
        (build-api/with-compiler-options
          {:source-map true})
        (build-api/with-build-options
          {:output-dir output-dir})
        (cond->
          (not js-options)
          (build-api/with-js-options
            {:js-provider :shadow}))
        (build-api/configure-modules {:test {:entries []
                                             :output-name (.getName output-to)}})

        ;; FIXME: maybe add devtools but given how odd karma loads js that might not be reliable
        )))

;; since :configure is only called once in :dev
;; we delay setting the :entries until compile-prepare which is called every cycle
;; need to come up with a cleaner API for this
(defn compile-prepare
  [{:keys [classpath] ::keys [runner-ns] :as state} mode config]
  (let [{:keys [ns-regexp] :or {ns-regexp "-test$"}}
        config

        test-namespaces
        (->> (cp/get-all-resources classpath)
             (filter :file) ;; only test with files, ie. not tests in jars.
             (filter #(= :cljs (:type %)))
             (map :ns)
             (filter (fn [ns]
                       (re-find (re-pattern ns-regexp) (str ns))))
             (into []))

        entries
        (-> '[shadow.test.env] ;; must be included before any deftest because of the cljs.test mod
            (into test-namespaces)
            (conj runner-ns))]

    (build/log state {:type ::test-namespaces
                      :test-namespaces test-namespaces
                      :entries entries})

    (-> state
        (assoc-in [::modules/config :test :entries] entries)
        ;; re-analyze modules since we modified the entries
        (modules/analyze))))

(defn flush-karma-test-file
  [{::keys [output-to] :keys [polyfill-js unoptimizable build-options build-sources] :as state} config]

  (let [prepend
        (str unoptimizable
             (output/closure-defines-and-base state)
             "var shadow$provide = {};\n"
             "goog.global[\"$CLJS\"] = goog.global;\n")

        out
        (->> build-sources
             (map #(data/get-source-by-id state %))
             (remove #(= "goog/base.js" (:resource-name %)))
             (map #(data/get-output! state %))
             (map :js)
             (str/join "\n"))]

    ;; FIXME: generate index source map
    (spit output-to
      (str prepend
           out)))

  state)

(defn flush [state mode config]
  (case mode
    :dev
    (flush-karma-test-file state config)

    :release
    (output/flush-optimized state)))

(defn process
  [{::build/keys [stage mode config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :compile-prepare
    (compile-prepare state mode config)

    :flush
    (flush state mode config)

    state
    ))