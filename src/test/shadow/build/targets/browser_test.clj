(ns shadow.build.targets.browser-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [shadow.build.targets.browser :as browser]))

(deftest browser-rewrite-modules
  (let [x (browser/rewrite-modules
            {:worker-info {:host "localhost" :port 1234}
             :js-options {:js-provider :require}}
            :dev
            '{:modules {:main {:entries [my.app]}}})]
    (pprint x)
    ))
