(ns shadow.build.node
  (:refer-clojure :exclude [flush compile])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [cljs.compiler :as comp]
            [shadow.build.log :as log]
            [shadow.build.api :as build-api]
            [shadow.build.output :as output]
            [shadow.build.closure :as closure]
            [shadow.cljs.util :as util]
            [shadow.build.data :as data])
  (:import (java.lang ProcessBuilder$Redirect)))

(defmethod log/event->str ::flush-unoptimized
  [{:keys [output-file] :as ev}]
  (str "Flush node script: " output-file))

(defmethod log/event->str ::flush-optimized
  [{:keys [output-file] :as ev}]
  (str "Flush optimized node script: " output-file))

(defn make-main-call-js [main-fn]
  {:pre [(symbol? main-fn)]}
  (str "\ncljs.core.apply.cljs$core$IFn$_invoke$arity$2(" (comp/munge main-fn) ", process.argv.slice(2));"))

(defn configure
  [state {:keys [main output-to] :as opts}]
  (let [main-ns
        (namespace main)

        [main-ns main-fn]
        (if (nil? main-ns)
          [(name main) "main"]
          [main-ns (name main)])

        output-to
        (io/file output-to)

        output-name
        (.getName output-to)

        main
        (symbol main-ns main-fn)

        node-config
        (assoc opts :main-ns main-ns
          :main-fn main-fn
          :main main
          :output-to output-to)

        main-call
        (-> node-config :main (make-main-call-js))

        module-opts
        (-> opts
            (select-keys [:prepend :append :prepend-js :append-js])
            (update :prepend #(str "(function(){\n" %))
            (cond->
              (not (false? (:hashbang opts)))
              (update :prepend #(str "#!/usr/bin/env node\n" %)))
            (update :append-js str "\n" main-call)
            (update :append str "\n})();\n"))]

    (-> state
        (assoc :node-config node-config)
        (build-api/configure-modules
          {:main
           (assoc module-opts
             :entries [(symbol main-ns)]
             :depends-on #{})})
        )))

(defn compile [state]
  (-> state
      (build-api/analyze-modules)
      (build-api/compile-sources)))

(defn optimize [state]
  (build-api/optimize state))

(defn closure-defines
  [state]
  (str "\nSHADOW_ENV.CLOSURE_NO_DEPS = true;\n"
       "\nSHADOW_ENV.CLOSURE_DEFINES = " (output/closure-defines-json state) ";\n"))

(defn flush-unoptimized
  [{:keys [build-modules build-sources build-options compiler-options node-config] :as state}]
  (when (not= 1 (count build-modules))
    (throw (ex-info "node builds can only have one module!" {:tag ::output :build-modules build-modules})))

  (let [{:keys [cljs-runtime-path]}
        build-options

        {:keys [source-map]}
        compiler-options

        {:keys [output-to]}
        node-config]

    (output/flush-sources state)

    (util/with-logged-time
      [state {:type ::flush-unoptimized
              :output-file (.getAbsolutePath output-to)}]

      (let [{:keys [prepend append sources]}
            (first build-modules)

            out
            (str/join "\n"
              [prepend

               ;; this is here and not in boostrap since defines already accesses them
               (str "var SHADOW_IMPORT_PATH = \""
                    (-> (data/output-file state cljs-runtime-path)
                        (.getAbsolutePath))
                    "\";")
               (str "var SHADOW_ENV = {};")

               (when source-map
                 (str "try {"
                      "require('source-map-support').install();"
                      "} catch (e) {"
                      "console.warn('no \"source-map-support\" (run \"npm install source-map-support --save-dev\" to get it)');"
                      "}"))

               ;; FIXME: these operate on SHADOW_ENV
               ;; this means they rely on goog.global = this AND fn.call(SHADOW_ENV, ...)
               ;; I eventually want to turn the "this" of shadow imports into the module
               ;; to match what node does.
               (closure-defines state)

               ;; provides SHADOW_IMPORT and other things
               (slurp (io/resource "shadow/cljs/node_bootstrap.txt"))


               ;; import all other sources
               (->> sources
                    (map #(get-in state [:sources %]))
                    (map (fn [{:keys [provides output-name] :as src}]
                           (str "SHADOW_IMPORT(" (pr-str output-name) ");"
                                (when (contains? provides 'goog)
                                  (str "\ngoog.provide = SHADOW_PROVIDE;"
                                       "\ngoog.require = SHADOW_REQUIRE;")))
                           ))
                    (str/join "\n"))

               ;; make these local always
               ;; these are needed by node/configure :main and the umd exports
               "var shadow = SHADOW_ENV.shadow || {};"
               "var cljs = SHADOW_ENV.cljs || {};"

               (when-some [main (:main node-config)]
                 (let [root
                       (-> (str main)
                           (comp/munge))
                       root
                       (subs root 0 (str/index-of root "."))]
                   (str "var " root " = SHADOW_ENV." root ";")))

               append])]

        (io/make-parents output-to)
        (spit output-to out))))

  ;; return unmodified state
  state)


(defn flush-optimized
  [{::closure/keys [modules] :keys [node-config] :as state}]
  (let [{:keys [output-to]} node-config]
    (util/with-logged-time
      [state {:type ::flush-optimized
              :output-file (.getAbsolutePath output-to)}]

      (when (not= 1 (count modules))
        (throw (ex-info "node builds can only have one module!" {:tag ::output :modules modules})))

      (when-not (seq modules)
        (throw (ex-info "flush before optimize?" {})))

      (let [{:keys [prepend output append]} (first modules)]
        (io/make-parents output-to)
        (spit output-to (str prepend output append))
        )))


  state)

(defmethod log/event->str ::execute!
  [{:keys [args]}]
  (format "Execute: %s" (pr-str args)))

(defn execute! [{:keys [node-config] :as state}]
  (when (not= 1 (-> state :build-modules count))
    (throw (ex-info "can only execute non modular builds" {})))

  (let [{:keys [output-to]}
        node-config

        script-args
        ["node"]

        pb
        (doto (ProcessBuilder. script-args)
          (.directory nil)
          (.redirectOutput ProcessBuilder$Redirect/INHERIT)
          (.redirectError ProcessBuilder$Redirect/INHERIT))]

    ;; not using this because we only get output once it is done
    ;; I prefer to see progress
    ;; (prn (apply shell/sh script-args))

    (util/with-logged-time
      [state {:type ::execute!
              :args script-args}]
      (let [proc
            (.start pb)]

        (let [out (.getOutputStream proc)]
          (io/copy (io/file output-to) out)
          (.close out))

        ;; FIXME: what if this doesn't terminate?
        (let [exit-code (.waitFor proc)]
          (assoc state ::exit-code exit-code))))))

(defn setup-test-runner [state test-namespaces]
  (let [deps
        (into ['cljs.core 'cljs.test] test-namespaces)

        test-runner-ns
        'shadow.test-runner

        test-runner-src
        {:resource-name "shadow/test_runner.cljs"
         :output-name "shadow.test_runner.js"
         :type :cljs
         :ns test-runner-ns
         :provides #{test-runner-ns}
         :requires (into #{} deps)
         :deps deps
         :input (atom [`(~'ns ~test-runner-ns
                          (:require [cljs.test]
                            ~@(mapv vector test-namespaces)))

                       `(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m#]
                          (if (cljs.test/successful? m#)
                            (js/process.exit 0)
                            (js/process.exit 1)
                            ))

                       `(defn ~'main []
                          (cljs.test/run-tests
                            (cljs.test/empty-env)
                            ~@(for [it test-namespaces]
                                `(quote ~it))))])
         :last-modified (System/currentTimeMillis)}]

    (-> state
        (build-api/add-virtual-resource test-runner-src)
        (configure {:main test-runner-ns
                    :output-to "target/shadow-test-runner.js"
                    :hashbang false}))))

(comment
  ;; FIXME: turn this into generic helper functions so they work for browser tests as well

  (defn find-all-test-namespaces [state]
    (->> (get-in state [:sources])
         (vals)
         (remove :jar)
         (filter build-api/has-tests?)
         (map :ns)
         (remove #{'shadow.test-runner})
         (into [])))

  (defn make-test-runner
    ([state]
     (make-test-runner state (find-all-test-namespaces state)))
    ([state test-namespaces]
     (-> state
         (setup-test-runner test-namespaces)
         (compile)
         (flush-unoptimized))))

  (defn to-source-name [state source-name]
    (cond
      (string? source-name)
      source-name
      (symbol? source-name)
      (get-in state [:provide->source source-name])
      :else
      (throw (ex-info (format "no source for %s" source-name) {:source-name source-name}))
      ))

  (defn execute-affected-tests!
    [state source-names]
    (let [source-names
          (->> source-names
               (map #(to-source-name state %))
               (into []))

          test-namespaces
          (->> (concat source-names (build-api/find-dependents-for-names state source-names))
               (filter #(build-api/has-tests? (get-in state [:sources %])))
               (map #(get-in state [:sources % :ns]))
               (distinct)
               (into []))]

      (if (empty? test-namespaces)
        (do (util/log state {:type :info
                             :msg (format "No tests to run for: %s" (pr-str source-names))})
            state)
        (do (-> state
                (make-test-runner test-namespaces)
                (execute!))
            ;; return unmodified state, otherwise previous module information and config is lost
            state))))

  (defn execute-all-tests! [state]
    (-> state
        (make-test-runner)
        (execute!))

    ;; return unmodified state!
    state
    )

  (defn execute-all-tests-and-exit! [state]
    (let [state (-> state
                    (make-test-runner)
                    (execute!))]
      (System/exit (::exit-code state)))))

