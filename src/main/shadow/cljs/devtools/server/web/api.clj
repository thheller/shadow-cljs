(ns shadow.cljs.devtools.server.web.api
  (:require [shadow.cljs.devtools.server.web.common :as common]
            [shadow.http.router :as http]
            [shadow.repl :as repl]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [shadow.cljs.devtools.server.util :as util]
            [clojure.tools.logging :as log]))

(defn index-page [req]
  {:status 200
   :body "foo"})

(defn transform-level
  [{::repl/keys [root-id level-id lang] :as level}]
  (-> level
      (select-keys [::repl/root-id ::repl/level-id ::repl/lang])
      (assoc ::repl/ops (->> level
                             (keys)
                             (into #{})))))

(defn transform-root
  [{::repl/keys [type levels] :as root}]
  (-> root
      (select-keys [::repl/root-id ::repl/type])
      (assoc ::repl/levels
        (->> levels
             (map transform-level)
             (into [])))))

(defn repl-roots [req]
  (common/edn req
    (->> (repl/roots)
         (vals)
         (map transform-root)
         (into []))))

(defn repl-root [req root-id]
  (common/edn req
    (-> (repl/root root-id)
        (transform-root))))

(defn repl-level [req root-id level-id]
  (common/edn req
    (-> (repl/level root-id level-id)
        (transform-level))))

(defn open-file [{:keys [config ring-request] :as req}]
  (let [data
        (-> req
            (get-in [:ring-request :body])
            (slurp)
            (edn/read-string))

        {:keys [open-file-command]}
        config

        result
        (if-not open-file-command
          {:exit 1
           :err "no :open-file-command"}
          (let [launch-args
                (util/make-open-args data open-file-command)

                result
                (util/launch launch-args {})]
            (log/debug ::open-file launch-args result)
            result))]

    {:status 200
     :headers {"content-type" "application/edn; charset=utf-8"}
     :body (pr-str result)}))

(defn root* [req]
  (http/route req
    (:GET "" index-page)
    (:GET "/repl" repl-roots)
    (:GET "/repl/{root-id:long}" repl-root root-id)
    (:GET "/repl/{root-id:long}/{level-id:long}" repl-level root-id level-id)
    (:POST "/open-file" open-file)
    common/not-found))

(defn root [{:keys [ring-request] :as req}]
  ;; not the cleanest CORS implementation but I want to call API methods from the client
  (let [headers
        {"Access-Control-Allow-Origin" "*"
         "Access-Control-Allow-Headers"
         (or (get-in ring-request [:headers "access-control-request-headers"])
             "content-type")
         "content-type" "application/edn; charset=utf-8"}]

    (if-not (= :options (:request-method ring-request))
      (-> req
          (root*)
          (update :headers merge headers))
      {:status 200
       :headers headers
       :body ""}
      )))

