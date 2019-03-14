(ns shadow.build.targets.browser-test
  (:refer-clojure :exclude (compile flush resolve))
  (:require
    [clojure.string :as str]
    [shadow.build :as build]
    [shadow.build.modules :as modules]
    [shadow.build.classpath :as cp]
    [shadow.build.targets.browser :as browser]
    [shadow.cljs.util :as util]
    [hiccup.page :refer (html5)]
    [clojure.java.io :as io]
    [cljs.compiler :as cljs-comp]
    [shadow.jvm-log :as log]
    [shadow.build.data :as data]
    [shadow.build.test-util :as tu]))

;; FIXME: automate all of this better ...

(defn modify-config [{::build/keys [config] :as state}]
  (let [{:keys [runner-ns test-dir] :or {runner-ns 'shadow.test.browser}}
        config

        index-html
        (io/file test-dir "index.html")]

    ;; meh, should not be writing this file. use a handler instead.
    (when-not (.exists index-html)
      (io/make-parents index-html)
      (spit index-html
        (html5
          {}
          [:head
           [:title (str runner-ns)]
           [:meta {:charset "utf-8"}]]
          [:body
           [:script {:src "/js/test.js"}]
           [:script (str (cljs-comp/munge runner-ns) ".init();")]])))

    (-> state
        (tu/configure-common)
        (update ::build/config assoc :output-dir (str test-dir "/js"))
        (assoc-in [::build/config :modules :test] {:entries []})
        (assoc-in [::build/config :compiler-options :source-map] true) ;; always
        (update :build-options merge {:greedy true
                                      :dynamic-resolve true})
        (assoc ::tu/runner-ns runner-ns)
        (update-in [::build/config :devtools] merge
          ;; FIXME: can't yet dynamically inject the http-root config
          ;; need a proper way to use worker lifecycle for "extended" services
          ;; so a worker can start its own services
          ;; :http-root test-dir
          {:after-load (symbol (str runner-ns) "start")
           :before-load-async (symbol (str runner-ns) "stop")}))))


;; since :configure is only called once in :dev
;; we delay setting the :entries until compile-prepare which is called every cycle
;; need to come up with a cleaner API for this
(defn test-resolve
  [{::build/keys [mode config] :as state}]
  (let [{:keys [ns-regexp] :or {ns-regexp "-test$"}}
        config

        test-namespaces
        (tu/find-namespaces-by-regexp state ns-regexp)]

    (log/debug ::test-resolve {:ns-regexp ns-regexp
                               :test-namespaces test-namespaces})

    (-> state
        (assoc ::tu/test-namespaces test-namespaces)
        (assoc-in [::modules/config :test :entries]
          (-> '[shadow.test.env] ;; must be included before any deftest because of the cljs.test mod
              (into test-namespaces)
              (conj (::tu/runner-ns state))))
        (cond->
          (and (= :dev mode) (:worker-info state))
          (update-in [::modules/config :test] browser/inject-repl-client state config)

          (= :dev mode)
          (-> (update-in [::modules/config :test] browser/inject-preloads state config)
              (update-in [::modules/config :test] browser/inject-devtools-console state config)))
        (modules/analyze)

        ;; must do this after all sources are resolved
        (tu/inject-extra-requires)
        )))

(defn process
  [{::build/keys [stage] :as state}]
  (-> state
      (cond->
        (= :configure stage)
        (modify-config)

        (= :resolve stage)
        (test-resolve))

      (browser/process)))