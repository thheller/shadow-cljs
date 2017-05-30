(ns shadow.cljs.node
  (:refer-clojure :exclude [flush compile])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [cljs.compiler :as comp]
            [shadow.cljs.log :as log]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.output :as output]
            [shadow.cljs.closure :as closure]
            [shadow.cljs.util :as util])
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

        module-name
        (-> output-name (str/replace #".js$" "") (keyword))

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
            (update :prepend #(str "#!/usr/bin/env node\n" %))
            (update :append-js str "\n" main-call))]

    (-> state
        (assoc :node-config node-config)
        (cljs/reset-modules)
        (cljs/configure-module module-name [(symbol main-ns)] #{} module-opts)
        )))

(defn compile [state]
  (cljs/compile-modules state))

(defn optimize [state]
  (cljs/closure-optimize state (get-in state [:node-opts :optimization] :simple)))

(defn closure-defines
  [state]
  (str "\nSHADOW_ENV.CLOSURE_NO_DEPS = true;\n"
       "\nSHADOW_ENV.CLOSURE_DEFINES = " (output/closure-defines-json state) ";\n"))

(defn flush-unoptimized
  [{:keys [build-modules cljs-runtime-path source-map output-dir node-config] :as state}]
  {:pre [(output/directory? output-dir)]}
  (when (not= 1 (count build-modules))
    (throw (ex-info "node builds can only have one module!" {:tag ::output :build-modules build-modules})))

  (output/flush-sources-by-name state)

  ;; FIXME: this is a bit annoying
  ;; goog/base.js is never compiled and never appears in module :sources
  ;; manually created the :ouput and flush it
  (-> state
      (update-in [:sources "goog/base.js"]
        (fn [{:keys [input] :as rc}]
          (assoc rc :output @input)))
      (output/flush-sources-by-name ["goog/base.js"]))

  (let [{:keys [output-to]}
        node-config]

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
                    (-> (io/file output-dir cljs-runtime-path)
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

               ;; manually import goog/base.js so we can patch it before others get imported
               ;; cannot inline the goog/base.js as the goog.global = this; should match
               ;; the this of every other imported file (which is SHADOW_ENV)
               "SHADOW_IMPORT(\"goog.base.js\");"
               "goog.provide = SHADOW_PROVIDE;"
               "goog.require = SHADOW_REQUIRE;"

               ;; import all other sources
               (->> sources
                    (map #(get-in state [:sources %]))
                    (map :js-name)
                    (map (fn [src]
                           (str "SHADOW_IMPORT(" (pr-str src) ");")))
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

      (let [{:keys [output js-name]} (first modules)]
        (io/make-parents output-to)
        (spit output-to output)
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
  (let [require-order
        (into ['cljs.core 'cljs.test] test-namespaces)

        test-runner-ns
        'shadow.test-runner

        test-runner-src
        {:name "shadow/test_runner.cljs"
         :js-name "shadow.test_runner.js"
         :type :cljs
         :ns test-runner-ns
         :provides #{test-runner-ns}
         :requires (into #{} require-order)
         :require-order require-order
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
        (cljs/merge-resource test-runner-src)
        (cljs/reset-modules)
        (configure {:main test-runner-ns :output-to "target/shadow-test-runner.js"}))))

(defn find-all-test-namespaces [state]
  (->> (get-in state [:sources])
       (vals)
       (remove :jar)
       (filter cljs/has-tests?)
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
        (->> (concat source-names (cljs/find-dependents-for-names state source-names))
             (filter #(cljs/has-tests? (get-in state [:sources %])))
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
    (System/exit (::exit-code state))))

