(ns shadow.cljs.ui.model
  (:require [shadow.vault.store :as store :refer (defkey)]
            [cljs.spec.alpha :as s]))

(defkey Builds
  :spec
  any?)
