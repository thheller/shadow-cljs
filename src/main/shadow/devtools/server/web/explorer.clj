(ns shadow.devtools.server.web.explorer
  (:require [shadow.devtools.server.explorer :as explorer]
            [shadow.devtools.server.web.common :as common]
            [hiccup.core :refer (html)]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as str]))

(defn index-page
  [{:keys [explorer] :as req}]
  (common/page-boilerplate
    req
    (html
      [:h1 "CLJS Sources"]
      [:ul
       (for [src
             (->> (explorer/get-project-sources explorer)
                  (sort))]
         [:li
          [:a {:href (str "/explorer/inspect/" src)} (str src)]])]
      )))

(defn inspect-page
  [{:keys [explorer] :as req} src]
  (let [{:keys [info error] :as result}
        (explorer/get-source-info explorer src)

        {:keys [ns warnings defs deps]}
        info]

    (common/page-boilerplate
      req
      (html
        [:h1 (str "Source: " src)]
        (when ns
          [:h2 (str "Namespace: " ns)])

        (when error
          [:div
           [:h1 "Compilation failed"]
           [:pre
            (with-out-str
              (pprint error))]])

        (when (seq warnings)
          [:div.warnings
           [:h3 "Warnings"]
           (for [warning warnings]
             [:pre (with-out-str (pprint warning))])])

        [:h3 "Defs"]
        (for [def defs]
          [:pre (pr-str def)])

        [:h3 "Dependencies"]
        [:ul
         (for [dep deps]
           [:li [:a {:href (str "/explorer/inspect/" dep)} dep]])]
        ))))

(defn root [req]
  (let [uri
        (-> (get-in req [:ring-request :uri])
            (subs (count "/explorer")))]

    (cond
      (= uri "/")
      (index-page req)

      (str/starts-with? uri "/inspect/")
      (let [src-name
            (-> uri
                (subs (count "/inspect/")))]
        (inspect-page req src-name))

      :else
      common/not-found
      )))