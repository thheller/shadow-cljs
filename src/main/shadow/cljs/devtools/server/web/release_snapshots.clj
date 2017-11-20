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

    (common/page-boilerplate req
      (html
        [:h1 "shadow-cljs - release snapshot"]

        ;; FIXME: setup proper css
        [:style "body {\n  font-family: 'Open Sans', sans-serif;\n  font-size: 12px;\n  font-weight: 400;\n  background-color: #fff;\n  width: 960px;\n  height: 700px;\n  margin-top: 10px;\n}\n\n#main {\n  float: left;\n  width: 750px;\n}\n\n#sidebar {\n  float: right;\n  width: 100px;\n}\n\n#sequence {\n  width: 600px;\n  height: 70px;\n}\n\n#legend {\n  padding: 10px 0 0 3px;\n}\n\n#sequence text, #legend text {\n  font-weight: 600;\n  fill: #fff;\n}\n\n#chart {\n  position: relative;\n}\n\n#chart path {\n  stroke: #fff;\n}\n\n#explanation {\n  position: absolute;\n  top: 260px;\n  left: 305px;\n  width: 140px;\n  text-align: center;\n  color: #666;\n  z-index: -1;\n}\n\n#percentage {\n  font-size: 2.5em;\n}"]

        [:div#sequence]
        [:div#chart
         [:div#explanation
          {:style "visibility: hidden;"}
          [:span#percentage]]]

        (assets/js-queue :none 'shadow.cljs.ui.bundle-info/init
          (-> (slurp info-file)
              (edn/read-string)))))
    ))

(defn root [req]
  (http/route req
    (:GET "" index-page)
    (:GET "/{build-id}/{tag}" info-page build-id tag)
    common/not-found))
