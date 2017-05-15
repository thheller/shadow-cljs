(ns shadow.cljs.devtools.server.web.api
  (:require [shadow.cljs.devtools.server.web.common :as common]
            [shadow.cljs.devtools.server.config-watch :as config]))



(defn root [{:keys [build-config] :as req}]
  (common/transit
    req
    []))
