(ns shadow.build.targets.browser-test
  (:refer-clojure :exclude (compile flush resolve))
  (:require
    [shadow.build :as build]
    [shadow.build.modules :as modules]
    [shadow.build.targets.esm :as esm]
    [shadow.css.build :as cb]
    [hiccup.page :refer (html5)]
    [clojure.java.io :as io]
    [shadow.jvm-log :as log]
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
           [:meta {:charset "utf-8"}]
           [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
           [:link {:rel "stylesheet" :href "css/test.css"}]
           [:title (str runner-ns)]]
          [:body
           [:div#test-root]
           [:script {:type "module" :src "js/test.js"}]])))

    (-> state
        (tu/configure-common)
        (update ::build/config assoc :output-dir (str test-dir "/js"))
        (assoc-in [::build/config :modules :test]
          {:entries []
           :init-fn (symbol (str runner-ns) "init")})
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
;; we delay setting the :entries until :resolve which is called every cycle
;; need to come up with a cleaner API for this
(defn test-resolve
  [{::build/keys [config] :as state}]
  (let [test-namespaces (tu/find-test-namespaces state config)]

    (log/debug ::test-resolve {:config config
                               :test-namespaces test-namespaces})

    (-> state
        (assoc ::tu/test-namespaces test-namespaces)
        (assoc-in [::build/config :modules :test :entries]
          (-> '[shadow.test.env] ;; must be included before any deftest because of the cljs.test mod
              (into test-namespaces)
              (conj (::tu/runner-ns state))))
        ;; rebuild from config so ESM startup code, preloads and REPL entries
        ;; are retained without accumulating them between watch builds.
        (esm/configure-modules)
        (modules/analyze)

        ;; must do this after all sources are resolved
        (tu/inject-extra-requires)

        ;; lets the runtime know which tests are affected by changes, so only those re-run on hot-reload
        (tu/update-test-fingerprints)
        )))

(defn flush-css [{::build/keys [config] :as state}]
  (let [css-state
        (-> (cb/init)
            (cb/load-default-aliases-from-classpath)
            (cb/load-colors-from-classpath)
            (cb/generate-color-aliases)
            (cb/generate-spacing-aliases)
            (cb/index-source (slurp (io/resource "shadow/test/browser/ui.cljs")))
            (cb/generate '{:test {:entries [shadow.test.browser.ui]}})
            (cb/minify))

        {:keys [warnings]}
        (get-in css-state [:chunks :test])]

    (doseq [warning warnings]
      (log/warn ::css-warning warning))

    (cb/write-outputs-to css-state (io/file (:test-dir config) "css"))
    state))

(defn process
  [{::build/keys [stage] :as state}]
  (-> state
      (cond->
        (= :configure stage)
        (modify-config)

        (= :resolve stage)
        (test-resolve))

      (esm/process)
      (cond->
        (= :flush stage)
        (flush-css))))
