(ns shadow.insight.runtime)

(defprotocol IPlanAware
  (-proceed-with-plan [this runtime-ext exec-ctx]))

;; trying to keep this file as generic as possible
;; these represent commands and their implementation is host-specific
;; remote-ext depends on this, so can't have things here
;; trying to keep this separate, so just the API for insight files is here

(deftype SwitchToLocal [])

(defn in-local []
  (->SwitchToLocal))

(deftype SwitchToRemote [opts])

(defn in-remote [opts]
  (->SwitchToRemote opts))
