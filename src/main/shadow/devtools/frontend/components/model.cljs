(ns shadow.devtools.frontend.components.model
  (:require [shadow.vault.store :as store :refer (defkey)]
            [clojure.spec :as s]
            [shadow.devtools.spec.build :as s-build]))

(defkey Build
  :spec
  ::s-build/build)

(defkey Builds
  "list of keys for each build"
  :spec
  (s/spec
    (s/and
      vector?
      (s/* #(store/key? Build %)))))
