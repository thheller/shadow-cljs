(ns shadow.expo
  (:require
    [clojure.data.json :as json]
    [clojure.tools.logging :as log]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.core.async :as async :refer (go <!)]
    [shadow.cljs.devtools.api :as api])
  (:import [java.util UUID]))

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

(defn serve [{:keys [headers build-id uri] :as req}]
  (let [{:keys [platform expo-root] :as config}
        (api/get-build-config build-id)]

    (when (not= "/onchange" uri)
      (log/debug "serve" build-id uri))

    (case uri
      ;; called when exp://some-host:some-port is opened in the app
      "/index.exp"
      (let [{:strs [host exponent-platform]}
            headers

            manifest
            (-> dummy-manifest
                (assoc "debuggerHost" host ;; doesn't seem to do anything?
                       "id" (str "@anonymous/shadow-" (UUID/randomUUID))
                       "bundleUrl" (str "http://" host "/bundle." platform ".js")
                       "logUrl" (str "http://" host "/logs"))
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
                :let [{:strs [body]} log-entry]]
          (log/warn "expo-log" (str/join " " body)))
        {:status 200 :body ""})

      ;; FIXME: shouldn't sleep, switch to undertow async mode
      ;; max 5000 delay, not sure what this is for but 205 appears to do stuff
      "/onchange"
      (do (Thread/sleep 4000)
          {:status 200 :body ""})

      ;; thought the remote debug stuff would call this but it doesn't
      "/status"
      {:status 200
       :body "running"}

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
      (let [body (-> (:body req)
                     (slurp)
                     (json/read-str))]

        {:status 200
         :body (-> {:result []}
                   (json/write-str))})

      {:status 404
       :body "Not found."})))


(comment
  ;; /symbolicate
  {"stack"
   [{"file" "http://192.168.1.13:19000/bundle.js", "methodName" "tryCallOne", "lineNumber" 14048, "column" 16}
    {"file" "http://192.168.1.13:19000/bundle.js", "methodName" "<unknown>", "lineNumber" 14149, "column" 27}
    {"file" "http://192.168.1.13:19000/bundle.js", "methodName" "_callTimer", "lineNumber" 15358, "column" 17}
    {"file" "http://192.168.1.13:19000/bundle.js", "methodName" "_callImmediatesPass", "lineNumber" 15394, "column" 19}
    {"file" "http://192.168.1.13:19000/bundle.js", "methodName" "callImmediates", "lineNumber" 15613, "column" 33}
    {"file" "http://192.168.1.13:19000/bundle.js", "methodName" "__callImmediates", "lineNumber" 2342, "column" 32}
    {"file" "http://192.168.1.13:19000/bundle.js", "methodName" "<unknown>", "lineNumber" 2169, "column" 34}
    {"file" "http://192.168.1.13:19000/bundle.js", "methodName" "__guardSafe", "lineNumber" 2326, "column" 13}
    {"file" "http://192.168.1.13:19000/bundle.js", "methodName" "flushedQueue", "lineNumber" 2168, "column" 21}]})