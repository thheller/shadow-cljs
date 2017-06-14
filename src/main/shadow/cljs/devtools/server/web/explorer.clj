(ns shadow.cljs.devtools.server.web.explorer
  (:require [shadow.cljs.devtools.server.explorer :as explorer]
            [shadow.cljs.devtools.server.web.common :as common]
            [hiccup.core :refer (html)]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as str]
            [shadow.http.router :as http]))

(defn index-page
  [{:keys [explorer] :as req}]
  (common/page-boilerplate
    req
    (html
      (common/nav)
      [:h1 "CLJS Sources"]
      [:ul
       (for [src
             (->> (explorer/get-project-sources explorer)
                  (sort))]
         [:li
          [:a {:href (str "/explorer/inspect/" src)} (str src)]])]
      )))

(defn inspect-page
  [{::http/keys [path-tokens] :keys [explorer] :as req}]
  (let [src
        (str/join "/" path-tokens)

        {:keys [info error] :as result}
        (explorer/get-source-info explorer src)

        {:keys [ns warnings defs deps]}
        info]

    (common/page-boilerplate
      req
      (html
        (common/nav)

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
  (http/route req
    (:GET "" index-page)
    (:GET "^/inspect" inspect-page)
    common/not-found))