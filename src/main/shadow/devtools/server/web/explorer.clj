(ns shadow.devtools.server.web.explorer
  (:require [shadow.devtools.server.services.explorer :as explorer]
            [shadow.devtools.server.web.common :as common]
            [hiccup.core :refer (html)]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as str]))

(defn index-page
  [{:keys [explorer] :as req}]
  (common/page-boilerplate
    req
    (html
      [:h1 "Explorer"]
      [:ul
       (for [provide
             (->> (explorer/get-project-provides explorer)
                  (sort))]
         [:li
          [:a {:href (str "/explorer/inspect/" provide)} (str provide)]])]
      )))

(defn inspect-page
  [{:keys [explorer] :as req} ns]
  (let [ns-info
        (explorer/get-ns-info explorer ns)]

    (common/page-boilerplate
      req
      (html
        [:h1 (str "Inspect: " ns)]
        [:pre
         (with-out-str
           (pprint ns-info))]))))

(defn root [req]
  (let [uri
        (-> (get-in req [:ring-request :uri])
            (subs (count "/explorer")))]

    (cond
      (= uri "/")
      (index-page req)

      (str/starts-with? uri "/inspect/")
      (let [ns
            (-> uri
                (subs (count "/inspect/"))
                (symbol))]
        (inspect-page req ns))

      :else
      common/not-found
      )))