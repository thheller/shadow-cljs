(ns shadow.cljs.devtools.server.web.release-snapshots
  (:require
    [clojure.string :as str]
    [hiccup.core :refer (html)]
    [shadow.http.router :as http]
    [shadow.server.assets :as assets]
    [shadow.cljs.devtools.server.web.common :as common]
    [clojure.java.io :as io]
    [clojure.edn :as edn]))

(defn index-page [{:keys [config] :as req}]
  (let [root-dir (io/file (:cache-root config) "release-snapshots")]
    (common/page-boilerplate req
      (html
        [:h1 "shadow-cljs - release snapshots"]

        (if-not (.exists root-dir)
          [:p "No release snapshots found."]
          (for [build-dir (.listFiles root-dir)]
            (let [build-id (.getName build-dir)]
              [:div
               [:h2 build-id]
               [:ul
                (for [tag-dir (.listFiles build-dir)]
                  (let [tag
                        (.getName tag-dir)

                        info-file
                        (io/file tag-dir "bundle-info.edn")]
                    [:li [:a {:href (str "/release-snapshots/" build-id "/" tag)} tag]]))]])))
        ))))

(defn info-page [{:keys [config] :as req} build-id tag]
  (let [snapshot-dir
        (io/file (:cache-root config) "release-snapshots" build-id tag)

        info-file
        (io/file snapshot-dir "bundle-info.edn")]

    {:status 200
     :body info-file}
    ))

(defn root [req]
  (http/route req
    (:GET "" index-page)
    (:GET "/{build-id}/{tag}" info-page build-id tag)
    common/not-found))
