(ns shadow.expo
  (:require
    [clojure.data.json :as json]
    [clojure.tools.logging :as log]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.core.async :as async :refer (go <! >!!)]
    [shadow.cljs.devtools.api :as api]
    [clojure.edn :as edn]
    [shadow.build.data :as data])
  (:import [java.util UUID]
           [com.google.debugging.sourcemap SourceMapConsumerV3]))

(def dummy-manifest
  {"packagerOpts"
   {"hostType" "tunnel"
    "lanType" "ip"
    "dev" true
    "minify" false
    ;; what is this for?
    "urlRandomness" "nn-3r3"},
   "name" "TestCRNA",
   "slug" "testcrna",
   "env" {},
   "version" "0.1.0",
   "developer"
   {"tool" "shadow-cljs"},
   "xde" true,
   "sdkVersion" "26.0.0",
   "mainModuleName" "out/index"})

;; http://192.168.1.13:19001/out/index.bundle?platform=ios&dev=true&minify=false&hot=false&assetPlugin=C%3A%5CUsers%5Cthheller%5Ccode%5Cshadow-cljs%5Cout%5CTestCRNA%5Cnode_modules%5Cexpo%5Ctools%5ChashAssetFiles

(defn find-entry [idx line]
  (let [entry
        (reduce
          (fn [offset {:keys [lines] :as entry}]
            (let [next-offset (+ offset lines)]
              (if (< line next-offset)
                (reduced entry)
                next-offset)))
          0
          idx)]
    (if (number? entry)
      nil
      entry)))

(defonce sm-cache-ref (atom {}))

(defn sm-load [file]
  (let [{:keys [last-modified-cache sm] :as cache-entry}
        (get @sm-cache-ref file)

        last-modified-file
        (.lastModified file)]

    (if (and cache-entry (= last-modified-file last-modified-cache))
      sm
      (let [sm
            (doto (SourceMapConsumerV3.)
              (.parse (slurp file)))]

        (swap! sm-cache-ref assoc file {:last-modified-cache last-modified-file
                                        :sm sm})

        sm
        ))))

(defn get-mapped-stack [output-dir idx stack]
  (->> stack
       (map (fn [{:strs [lineNumber column] :as frame}]
              (let [{:keys [file] :as entry}
                    (find-entry idx lineNumber)

                    sm-consumer
                    (when file
                      (sm-load (io/file output-dir file)))

                    mapping
                    (when sm-consumer
                      (.getMappingForLine sm-consumer lineNumber column))]

                (if-not mapping
                  frame
                  (cond-> frame
                    (.hasOriginalFile mapping)
                    (assoc "file" (.getOriginalFile mapping))
                    (.hasLineNumber mapping)
                    (assoc "lineNumber" (.getLineNumber mapping))
                    (.hasColumnPosition mapping)
                    (assoc "column" (.getColumnPosition mapping))
                    (.hasIdentifier mapping)
                    (assoc "methodName" (.getIdentifier mapping)))))))

       (into [])))


(defn serve [{:keys [http-root headers build-id uri] :as req}]
  (let [worker
        (api/get-worker build-id)

        {:keys [platform expo-root] :as config}
        (api/get-build-config build-id)]

    (when (not= "/onchange" uri)
      (log/debug "serve" build-id uri))

    (case uri
      ;; called when exp://some-host:some-port is opened in the app
      "/index.exp"
      (let [{:strs [host exponent-platform exponent-sdk-version]}
            headers

            manifest
            (-> dummy-manifest
                (assoc "debuggerHost" host ;; doesn't seem to do anything?
                       "id" (str "@anonymous/shadow-" (UUID/randomUUID))
                       "bundleUrl" (str "http://" host "/bundle." platform ".js")
                       "logUrl" (str "http://" host "/logs")
                       "sdkVersion" (-> exponent-sdk-version (str/split #",") (sort) (last)))
                (assoc-in ["developer" "projectRoot"]
                  (-> (io/file (or expo-root "."))
                      (.getCanonicalPath))))]

        (when (not= exponent-platform (:platform config))
          ;; FIXME: serve dummy bundle with warning instead.
          (log/warnf "platform mismatch, build is %s but request platform is %s" (:platform config) exponent-platform))

        {:status 200
         :headers {"content-type" "application/json"}
         :body
         (-> {:manifestString (json/write-str manifest)
              :signature "UNSIGNED"}
             (json/write-str))})

      ;; called for every console.log
      "/logs"
      (let [log (-> (:body req)
                    (slurp)
                    (json/read-str))]
        (doseq [log-entry log
                :let [{:strs [body]} log-entry
                      msg (str/join " " body)]]
          (log/warn "expo-log" msg)
          ;; this is overkill until everything is formatted properly
          #_ (>!! (:output worker) {:type :println :msg msg}))
        {:status 200 :body ""})

      ;; FIXME: shouldn't sleep, switch to undertow async mode
      ;; max 5000 delay, not sure what this is for but 205 appears to do stuff
      "/onchange"
      (do (Thread/sleep 4000)
          {:status 200 :body ""})

      ;; thought the remote debug stuff would call this but it doesn't
      "/status"
      {:status 200
       :body "packager-status:running"}

      ;; android only?
      "/inspector/device"
      (let [{:keys [query-string ws-in ws-out]} req]
        (log/debug "/inspector/device" build-id query-string)
        (go (loop []
              (when-some [msg (<! ws-in)]
                (log/warn "expo-inspect-device" (pr-str msg))
                (recur)))))

      ;; only android seems to connect to this?
      ;; not sure which messages it expects
      "/message"
      (let [{:keys [query-string ws-in ws-out]} req]
        (log/debug "/message" build-id query-string)
        (go (loop []
              (when-some [msg (<! ws-in)]
                (log/warn "expo-message" (pr-str msg))
                (recur)))))


      ;; FIXME: needed for source mapping
      "/symbolicate"
      (let [stack
            (-> (:body req)
                (slurp)
                (json/read-str)
                (get "stack"))

            idx
            (-> (io/file http-root (str "bundle." platform ".js.idx"))
                (slurp)
                (edn/read-string))

            mapped-stack
            (get-mapped-stack http-root idx stack)]

        {:status 200
         :body (-> {:stack mapped-stack}
                   (json/write-str))})

      {:status 404
       :body "Not found."})))


