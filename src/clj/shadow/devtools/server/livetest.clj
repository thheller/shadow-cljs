(ns shadow.devtools.server.livetest
  (:require [shadow.devtools.util :as util]
            [shadow.cljs.build :as cljs]
            [shadow.devtools.server :as server]
            [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as str]
            [cljs.compiler :as comp]
            [cljs.analyzer :as ana]
            [clojure.core.async :as async]
            [aleph.http :as http]
            [hiccup.page :refer (html5)]
            [ring.middleware.file :as ring-file]
            [ring.middleware.resource :as ring-resource]
            [ring.middleware.content-type :as ring-content-type]
            ))

(def not-found
  {:status 404
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body "Not found."})

(defn has-tests? [{:keys [ns requires] :as rc}]
  (and (not= ns 'shadow.devtools.livetest.runner)
       (not= ns 'shadow.devtools.test)
       (or (contains? requires 'cljs.test)
           (contains? requires 'shadow.devtools.test)
           )))

(defn find-all-test-namespaces [state]
  (->> (get-in state [:sources])
       (vals)
       (remove :jar)
       (filter has-tests?)
       (map :ns)
       (into [])))

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

        _ (prn [:namespaces namespaces])
        namespaces
        (cond
          (= :all namespaces)
          (find-all-test-namespaces state)

          (nil? namespaces)
          []

          :else
          namespaces)

        _ (prn [:namespaces namespaces])

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
                     (js/console.warn "nothing to test"))

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




(defn request-configure [req namespaces]
  (let [chan
        (async/chan)

        server-control
        (get-in req [:devtools :server :server-control])]

    (async/>!! server-control [:reconfigure #(setup-test-runner % namespaces) chan])

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
            (->> (find-all-test-namespaces compiler-state)
                 (remove #(= % 'shadow.devtools.test))
                 (remove #(= % 'shadow.devtools.livetest.runner))
                 (into []))]
        {:status 200
         :body
         (html5
           {}
           [:head
            [:title "LIVETEST"]
            [:link {:rel "stylesheet" :href "/support/css/livetest.css"}]]
           [:body
            [:h1 "Test-Namespaces"]
            [:div [:a {:href "/test-all"} "test all"]]
            [:ul
             (for [ns (sort test-namespaces)]
               [:li [:a {:href (str "/ns/" ns)} (str ns)]])]]
           )}))))

(defn test-all-page [req]
  (let [result

        (request-configure req :all)]
    (cond
      (= :timeout result)
      {:status 500
       :body "Timeout while waiting for reconfigure"}

      (cljs/compiler-state? result)
      (let []

        {:status 200
         :body
         (html5
           {}
           [:head
            [:title "LIVETEST: all"]
            [:link {:rel "stylesheet" :href "/support/css/livetest.css"}]]
           [:body
            [:div.shadow-test-nav
             [:span [:a {:href "/"} "Index"]]]
            [:div.shadow-test-toolbar]
            [:div#shadow-test-root]
            [:div.scripts
             [:script {:src "/js/test.js"}]
             [:script "shadow.devtools.test.livetest();"]]])})

      :else
      {:status 500
       :body
       (html5
         {}
         [:head [:title "LIVETEST: error"]]
         [:body
          [:h1 "server re-configuration failed"]
          [:pre (with-out-str (pprint result))]
          ])}
      )))

(defn user-asset-path [path]
  (if (or (str/starts-with? path "http")
          (str/starts-with? path "//"))
    path
    (str "/user" path)))

(defn test-page [req test-sym]
  (let [result
        (request-configure req [test-sym])

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
      (let [{:keys [meta defs] :as ana-ns}
            (get-in result [:compiler-env ::ana/namespaces ns])

            ;; FIXME: probably not a good idea to do this via meta
            {:keys [include-css include-js] :as livetest-meta}
            (:livetest meta)]

        {:status 200
         :body
         (html5
           {}
           [:head
            [:title "LIVETEST: " (str ns)]
            [:link {:rel "stylesheet" :href "/support/css/livetest.css"}]
            (for [path include-css]
              [:link {:rel "stylesheet" :href (user-asset-path path)}])]

           [:body
            [:div.shadow-test-nav
             [:div
              [:span [:a {:href "/"} "Index"]]
              " "
              [:span [:a {:href (str "/ns/" ns)} (str ns)]]]
             [:div
              [:ul
               (for [[def-name def]
                     (->> defs
                          (filter #(-> % (second) :test)))]
                 [:li [:a {:href (str "/ns/" ns "/" def-name)} (str def-name)]])]]]
            [:div.shadow-test-toolbar]
            [:div#shadow-test-root]
            [:div.scripts
             (for [path include-js]
               [:script {:src (user-asset-path path)}])

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

(defn serve-support-asset [{:keys [request config] :as req}]
  ;; trim the /support
  (let [req (update request :uri subs 8)
        res (ring-resource/resource-request req "shadow/devtools/livetest")]
    (if (nil? res)
      not-found
      (ring-content-type/content-type-response res req))
    ))

(defn serve-user-asset [{:keys [request config]}]
  ;; trim the /user
  (let [req (update request :uri subs 5)
        res (ring-file/file-request req (:user-dir config "public"))]
    (if (nil? res)
      not-found
      (ring-content-type/content-type-response res req))
    ))

(defn serve-file-asset [{:keys [request config] :as req}]
  (let [res (ring-file/file-request request "target/shadow-livetest")]
    (if (nil? res)
      not-found
      (ring-content-type/content-type-response res request))
    ))

(defn http-root [{:keys [devtools request] :as req}]
  (let [{:keys [uri]} request]

    (cond
      (= "/" uri)
      (index-page req)

      (str/starts-with? uri "/user")
      (serve-user-asset req)

      (str/starts-with? uri "/support")
      (serve-support-asset req)

      (= "/test-all" uri)
      (test-all-page req)

      (str/starts-with? uri "/ns/")
      (test-page req (symbol (subs uri 4)))

      :else
      (serve-file-asset req))))

(defn start-http [devtools config]
  (let [my-handler
        (fn [req]
          (http-root
            {:request req
             :config config
             :devtools devtools}))

        server
        (http/start-server my-handler {:port 4005})]

    {:devtools devtools
     :server server}))

(defn stop-http [{:keys [server] :as svc}]
  (.close server))

(defn stop-devtools [{:keys [repl-input] :as devtools}]
  (async/close! repl-input))

(defn start
  ([]
    (start {:user-dir "public"}))
  ([config]
   (let [devtools-config
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

         devtools
         (server/start state devtools-config)

         http
         (start-http devtools config)]

     {:devtools devtools
      :config config
      :http http}
     )))


(defn stop [{:keys [devtools http]}]
  (stop-http http)
  (stop-devtools devtools))

(defn run []
  (let [{:keys [devtools] :as svc}
        (start)]
    (println "==========================================")
    (println "LIVETEST running at: http://localhost:4005")
    (println "==========================================")

    (server/repl-output-proc devtools)
    (server/repl-input-loop! devtools)

    (stop svc)
    ))


(defn -main [& args]
  (run))
