(ns shadow.cljs.devtools.graph
  (:require
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.profile :as pp]
    [com.wsscode.pathom.core :as p]
    [shadow.cljs.devtools.graph.env :as genv :refer (add-resolver add-mutation)]
    [shadow.cljs.devtools.graph.builds]
    [shadow.cljs.devtools.graph.resources]))

(defn parser [env query]
  (let [instance
        (p/parser {:mutate pc/mutate
                   ::p/plugins
                   [(p/env-plugin
                      {::p/reader [p/map-reader
                                   pc/all-readers
                                   (p/placeholder-reader ">")]
                       ::pc/resolver-dispatch genv/resolver-fn
                       ::pc/mutate-dispatch genv/mutation-fn
                       ::pc/indexes @genv/index-ref})
                    pp/profile-plugin]})]
    (instance env query)
    ))

