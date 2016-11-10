(ns shadow.devtools.server.livetest
  (:require [shadow.devtools.util :as util]
            [shadow.cljs.build :as cljs]
            [shadow.devtools.server :as server]
            [shadow.cljs.node :as node]
            [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as str]
            [cljs.compiler :as comp]
            [cljs.analyzer :as ana]
            [clojure.core.async :as async]
            [aleph.http :as http]
            [hiccup.page :refer (html5)]
            [ring.middleware.file :as ring-file]
            ))

(def not-found
  {:status 404
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body "Not found."})

(defn ns-from-symbol [sym]
  (if-let [ns (namespace sym)]
    (symbol ns)
    sym))

(defn setup-test-runner [state namespaces]
  (let [runner-ns
        'shadow.devtools.livetest.runner

        requires
        '[shadow.devtools.test
          shadow.devtools.browser
          shadow.devtools.console]

        test-namespaces
        (into [] (map ns-from-symbol) namespaces)

        requires
        (into requires test-namespaces)

        test-runner-src
        {:name "shadow/devtools/livetest/runner.cljs"
         :js-name "shadow/devtools/livetest/runner.js"
         :type :cljs
         :provides #{runner-ns}
         :requires (into #{} requires)
         :require-order (into '[cljs.core runtime-setup] requires)
         :ns runner-ns
         :last-modified (System/currentTimeMillis)
         :input
         (atom [`(~'ns ~runner-ns
                   (:require ~@(mapv vector requires)))

                (cond
                  ;; nothing to test
                  (not (seq test-namespaces))
                  `(defn ~'livetest []
                     (js/console.warn "DID NOT CONFIGURE TEST-NAMESPACES"))

                  ;; test a single-var
                  (and (= 1 (count namespaces))
                       (qualified-symbol? (first namespaces)))
                  `(defn ~'livetest []
                     (cljs.test/set-env! (shadow.devtools.test/empty-env))
                     (cljs.test/test-vars [(~'var ~(first namespaces))])
                     (cljs.test/clear-env!))

                  :one-or-more-namespaces
                  `(defn ~'livetest []
                     (cljs.test/run-tests
                       (shadow.devtools.test/empty-env)
                       ~@(for [it test-namespaces]
                           `(quote ~it))))

                  )])}]

    (-> state
        (cljs/merge-resource test-runner-src)
        (cljs/reset-modules)
        (cljs/configure-module :test [runner-ns] #{}))))




(defn request-configure [req ns]
  (let [chan
        (async/chan)

        server-control
        (get-in req [:devtools :server :server-control])]

    (async/>!! server-control [:reconfigure #(setup-test-runner % [ns]) chan])

    (let [result
          (async/alt!!
            chan
            ([v]
              v)

            (async/timeout 30000)
            ([_]
              :timeout))]
      result
      )))

(defn request-state [req]
  (let [chan
        (async/chan)

        server-control
        (get-in req [:devtools :server :server-control])]

    (async/>!! server-control [:compiler-state chan])

    (let [result
          (async/alt!!
            chan
            ([v]
              v)

            (async/timeout 1000)
            ([_]
              :timeout))]
      result
      )))

(defn index-page [req]
  (let [compiler-state (request-state req)]
    (if (= :timeout compiler-state)
      {:status 500
       :body "Timeout waiting for server state."}
      (let [test-namespaces
            (->> (node/find-all-test-namespaces compiler-state)
                 (remove #(= % 'shadow.devtools.test))
                 (into []))]
        {:status 200
         :body
         (html5
           {}
           [:head [:title "LIVETEST"]]
           [:body
            [:h1 "Available Test-Namespaces"]
            [:ul
             (for [ns (sort test-namespaces)]
               [:li [:a {:href (str "/ns/" ns)} (str ns)]])]]
           )}))))

(defn test-page [req test-sym]
  (prn [:test-page test-sym])

  (let [result
        (request-configure req test-sym)

        ns
        (if (qualified-symbol? test-sym)
          (symbol (namespace test-sym))
          test-sym)

        focus-test
        (when (qualified-symbol? test-sym)
          (symbol (name test-sym)))]
    (cond
      (= :timeout result)
      {:status 500
       :body "Timeout while waiting for reconfigure"}

      (cljs/compiler-state? result)
      (let [defs
            (get-in result [:compiler-env ::ana/namespaces ns :defs])]

        {:status 200
         :body
         (html5
           {}
           [:head [:title "LIVETEST: " (str ns)]]
           [:body
            [:div [:a {:href "/"} "Index"]]
            [:div [:a {:href (str "/ns/" ns)} (str ns)]]
            [:div
             [:ul
              (for [[def-name def]
                    (->> defs
                         (filter #(-> % (second) :test)))]
                [:li [:a {:href (str "/ns/" ns "/" def-name)} (str def-name)]])]]
            [:div#app]
            [:div.scripts
             [:script {:src "/js/test.js"}]
             [:script "shadow.devtools.test.livetest();"]]])})

      :else
      {:status 500
       :body
       (html5
         {}
         [:head [:title "LIVETEST: " (str ns)]]
         [:body
          [:h1 "server re-configuration failed"]
          [:pre (with-out-str (pprint result))]
          ])}
      )))

(defn http-root [{:keys [devtools request] :as req}]
  (let [{:keys [uri]} request]

    (cond
      (= "/" uri)
      (index-page req)

      (str/starts-with? uri "/ns/")
      (test-page req (symbol (subs uri 4)))

      :else
      not-found)))

(defn start [devtools]
  (let [my-handler
        (fn [req]
          (http-root
            {:request req
             :devtools devtools}))

        http-handler
        (-> my-handler
            (ring-file/wrap-file (io/file "target/shadow-livetest")))

        server
        (http/start-server http-handler {:port 4005})]

    {:devtools devtools
     :server server}))

(defn stop [{:keys [server] :as svc}]
  (.close server))

(defn run []
  (let [config
        {:before-load 'shadow.devtools.test/livetest-stop
         :after-load 'shadow.devtools.test/livetest-start
         :console-support true}

        state
        (-> (cljs/init-state)
            (cljs/enable-source-maps)
            (cljs/set-build-options
              {:public-dir (io/file "target/shadow-livetest/js")
               :public-path "/js"})
            (cljs/find-resources-in-classpath)
            (setup-test-runner []))

        {:keys [repl-output repl-input] :as devtools}
        (server/start state config)

        svc
        (start devtools)]

    (println "==========================================")
    (println "LIVETEST running at: http://localhost:4005")
    (println "==========================================")

    (server/repl-output-proc devtools)
    (server/repl-input-loop! devtools)

    (stop svc)
    (server/stop devtools)
    ))


(defn -main [& args]
  (run))
