(ns shadow.devtools.frontend.components.actions
  (:require [clojure.spec :as s]
            [shadow.vault.store :as store :refer (defaction)]
            [shadow.devtools.spec.build :as s-build]))

(defaction app-start)

(defaction import-builds
  (s/spec
    (s/* ::s-build/build)))
