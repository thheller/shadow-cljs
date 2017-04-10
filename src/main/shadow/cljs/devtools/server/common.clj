(ns shadow.cljs.devtools.server.common
  (:require [shadow.cljs.devtools.server.fs-watch :as fs-watch]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [shadow.cljs.devtools.server.sass-worker :as sass-worker]
            [cognitect.transit :as transit]
            [clojure.edn :as edn]
            [shadow.cljs.devtools.compiler]
            [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]
            [clojure.java.classpath :as cp])
  (:import (java.io ByteArrayOutputStream InputStream)))

;; FIXME: make config option
(def classpath-excludes
  [#"resources(/?)$"
   #"classes(/?)$"
   #"java(/?)$"])

(defn get-classpath-directories []
  (->> (cp/classpath)
       (remove #(cljs/should-exclude-classpath classpath-excludes %))
       (filter #(.isDirectory %))
       (map #(.getCanonicalFile %))
       (distinct)
       (into [])))


(defn app
  [{:keys [css-packages] :as config}]
  (-> {:edn-reader
       {:depends-on []
        :start
        (fn []
          (fn [input]
            (cond
              (instance? String input)
              (edn/read-string input)
              (instance? InputStream input)
              (edn/read input)
              :else
              (throw (ex-info "dunno how to read" {:input input})))))
        :stop (fn [reader])}

       :transit-str
       {:depends-on []
        :start
        (fn []
          (fn [data]
            (let [out (ByteArrayOutputStream. 4096)
                  w (transit/writer out :json)]
              (transit/write w data)
              (.toString out)
              )))

        :stop (fn [x])}

       :system-bus
       {:depends-on []
        :start sys-bus/start
        :stop sys-bus/stop}}

      (cond->
        (get config :watch true)
        (merge {:cljs-watch
                {:depends-on [:system-bus]
                 :start (fn [system-bus]
                          (fs-watch/start system-bus ::sys-msg/cljs-watch
                            (get-classpath-directories)
                            ["cljs" "cljc" "clj" "js"]))
                 :stop fs-watch/stop}})

        css-packages
        (merge {:sass-watch
                {:depends-on [:system-bus]
                 :start
                 (fn [system-bus]
                   (let [dirs (->> (mapcat :modules css-packages)
                                   (map #(-> % (io/file) (.getParentFile) (.getCanonicalFile)))
                                   (distinct)
                                   (into []))]
                     (fs-watch/start system-bus
                       ::sys-msg/sass-watch
                       dirs
                       ["scss" "sass"])))
                 :stop fs-watch/stop}

                :sass-worker
                {:depends-on [:system-bus]
                 ;; FIXME: should maybe be using :config instead?
                 :start #(sass-worker/start %1 css-packages)
                 :stop sass-worker/stop}
                })

        )))
