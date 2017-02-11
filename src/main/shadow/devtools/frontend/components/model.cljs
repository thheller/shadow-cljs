(ns shadow.devtools.frontend.components.model
  (:require [shadow.vault.store :as store :refer (defkey)]
            [clojure.spec :as s]
            [shadow.devtools.spec.build :as s-build]))

(defkey Build
  :spec
  ::s-build/build)

(defkey Builds
  :spec
  (s/spec
    (s/+ #(store/key? Build %))))


