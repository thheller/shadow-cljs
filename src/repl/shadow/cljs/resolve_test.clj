(ns shadow.cljs.resolve-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
            [shadow.build.npm :as npm]
            [shadow.build.classpath :as cp]
            [shadow.cljs.util :as util]
            [shadow.build.api :as api]
            [shadow.build.resolve :as res]
            [shadow.build.data :as data]
            [shadow.build.macros :as macros]
            [shadow.build.compiler :as impl]
            [shadow.build.log :as build-log]))


(deftest test-resolve

  (let [npm
        (npm/start {})

        cache-root
        (io/file "target" "foo")

        cp
        (-> (cp/start cache-root)
            (cp/index-classpath))

        output-dir
        (io/file "target" "foo-output")

        log
        (util/log-collector)

        build-state
        (api/init cache-root cp npm log)

        [resolved resolved-state]
        (res/resolve-entries build-state '[demo.browser demo.browser-extra])

        rc-id
        (last resolved)

        rc
        (get-in resolved-state [:sources rc-id])

        deps
        (data/get-deps-for-id resolved-state #{} rc-id)

        macros
        (macros/macros-used-by-ids resolved-state deps)

        cache-map
        (impl/make-cache-key-map resolved-state rc)]

    #_(wide-pprint @log)

    (pprint deps)
    ;; (wide-pprint macros)
    ;; (wide-pprint cache-map)

    ;; (wide-pprint resolved)

    #_(-> resolved-state
          (dissoc :npm :classpath)
          ;; (select-keys [:str->sym :sym->source :cljs-aliases])
          ;; (select-keys [:immediate-deps])
          (wide-pprint))


    (npm/stop npm)
    (cp/stop cp)
    ))

(deftest test-resolve-x

  (let [cache-root
        (io/file "target" "foo")

        cp
        (-> (cp/start cache-root)
            (cp/index-classpath))

        output-dir
        (io/file "target" "foo-output")

        log
        (util/log-collector)

        build-state
        (-> (api/init)
            (api/with-classpath cp)
            (api/with-cache-dir cache-root)
            (assoc :logger log))

        [resolved resolved-state]
        (res/resolve-entries build-state '[demo.browser demo.browser-extra])]

    (doseq [{:keys [resource-id provides]}
            (map #(get-in resolved-state [:sources %]) resolved)]
      (prn [:x resource-id provides]))

    ))

(defn test-build []
  (let [npm
        (npm/start {})

        cache-root
        (io/file "target" "test-build")

        cp
        (-> (cp/start cache-root)
            (cp/index-classpath))

        output-dir
        (io/file "target" "test-build" "out")

        log
        (util/log-collector)]

    (-> (api/init)
        (api/with-cache-dir (io/file cache-root "cache"))
        (api/with-classpath cp)
        (api/with-npm npm)
        (api/with-logger log))
    ))

(deftest test-resolve-as-require

  (let [build-state
        (-> (test-build)
            (api/with-js-options
              {:js-provider :require}))

        [resolved resolved-state]
        (api/resolve-entries build-state '["react"])]

    (pprint resolved)
    #_(-> resolved-state :npm :index-ref deref :package-json-cache (pprint))
    ))


(deftest test-resolve-config

  (let [build-state
        (-> (test-build)
            (api/with-js-options
              {:js-provider :require
               :resolve
               {"something"
                {:target :npm
                 :require "react"}
                "foo"
                {:target :resource
                 :resource "demo/foo.js"}
                "bar" false}}))

        [resolved resolved-state]
        (api/resolve-entries build-state '["something" "foo" "bar"])]

    (pprint resolved)
    (pprint (:ns-aliases resolved-state))
    #_(-> resolved-state :npm :index-ref deref :package-json-cache (pprint))
    ))

(deftest test-resolve-as-closure

  (let [build-state
        (-> (test-build)
            (api/with-js-options
              {:js-provider :closure}))

        [resolved resolved-state]
        (api/resolve-entries build-state '["react"])

        {:keys [str->sym]}
        resolved-state]

    (pprint (map second resolved))
    (pprint str->sym)
    #_(-> resolved-state :npm :index-ref deref :package-json-cache (pprint))
    ))

(deftest test-resolve-as-shadow
  (try

    (let [build-state
          (-> (test-build)
              (api/with-js-options
                {:js-provider :shadow}))

          [resolved resolved-state]
          (api/resolve-entries build-state '["pdfjs-dist"])

          {:keys [str->sym]}
          resolved-state]

      (doseq [x resolved]
        (prn x))
      #_(pprint (map second resolved))
      #_(pprint str->sym)
      #_(-> resolved-state :npm :index-ref deref :package-json-cache (pprint)))
    (catch Exception e
      (prn (ex-data e)))))



(deftest test-resolve-perf
  (try

    (let [build-state
          (-> (test-build)
              (api/with-js-options
                {:js-provider :shadow}))

          [resolved resolved-state]
          (time
            (api/resolve-entries build-state ["semantic-ui-react"]))

          [resolve2 resolved2]
          (time
            (api/resolve-entries resolved-state ["semantic-ui-react"]))
          ]
      )
    (catch Exception e
      (prn (ex-data e))))
  )

(defn resolve-entries [state entries]
  (let [[resolved resolved-state]
        (api/resolve-entries state entries)]

    resolved-state
    ))


(comment
  (def build-state
    (-> (test-build)
        (api/with-js-options
          {:js-provider :shadow})))


  (def resolve1 (time (resolve-entries build-state ["semantic-ui-react"])))
  (def resolve2 (time (resolve-entries resolve1 ["semantic-ui-react"])))

  (dotimes [x 100]
    (time (resolve-entries resolve1 ["semantic-ui-react"])))

  )
