(ns shadow.resource
  (:require-macros [shadow.resource]))

(defn inline [& args]
  (throw (ex-info "shadow.resource/inline cannot be called dynamically." {})))