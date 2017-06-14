(ns shadow.cljs.devtools.server.web.api
  (:require [shadow.cljs.devtools.server.web.common :as common]
            [shadow.http.router :as http]
            [shadow.repl :as repl]))

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

(defn root [req]
  (http/route req
    (:GET "" index-page)
    (:GET "/repl" repl-roots)
    (:GET "/repl/{root-id:long}" repl-root root-id)
    (:GET "/repl/{root-id:long}/{level-id:long}" repl-level root-id level-id)
    common/not-found))
