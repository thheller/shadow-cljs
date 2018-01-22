(ns shadow.build.targets.browser-test
  (:refer-clojure :exclude (compile flush resolve))
  (:require [clojure.string :as str]
            [shadow.build :as build]
            [shadow.build.modules :as modules]
            [shadow.build.classpath :as cp]
            [shadow.build.targets.browser :as browser]
            [shadow.cljs.util :as util]
            [hiccup.page :refer (html5)]
            [clojure.java.io :as io]
            [cljs.compiler :as cljs-comp]))

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
           [:body
            [:pre#log]
            [:script {:src "/js/test.js"}]
            [:script (str (cljs-comp/munge runner-ns) ".init();")]]])))

    (-> state
        (update ::build/config assoc :output-dir (str test-dir "/js"))
        (assoc-in [::build/config :modules :test] {:entries []})
        (assoc-in [::build/config :compiler-options :source-map] true) ;; always
        (assoc ::runner-ns runner-ns)
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
(defn compile-prepare
  [{::build/keys [config]
    :keys [classpath]
    ::keys [runner-ns] :as state}]
  (let [{:keys [ns-regexp] :or {ns-regexp "-test$"}}
        config

        test-namespaces
        (->> (cp/get-all-resources classpath)
             (filter :file) ;; only test with files, ie. not tests in jars.
             (filter #(= :cljs (:type %)))
             (map :ns)
             (filter (fn [ns]
                       (re-find (re-pattern ns-regexp) (str ns))))
             (into []))]

    #_(build/log state {:type ::test-namespaces
                        :test-namespaces test-namespaces})

    (-> state
        (update-in [::modules/config :test :entries]
          #(-> '[shadow.test.env] ;; must be included before any deftest because of the cljs.test mod
               (into %)
               (into test-namespaces)
               (conj runner-ns)))
        (modules/analyze)
        )))

(defn process
  [{::build/keys [stage] :as state}]
  (-> state
      (cond->
        (= :configure stage)
        (modify-config)

        (= :compile-prepare stage)
        (compile-prepare))

      (browser/process)))