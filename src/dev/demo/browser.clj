(ns demo.browser)

(defmacro test-macro [a b c]
  ;;(throw (ex-info "macro bad" {}))
  :foo)

(defmacro bad-macro [& args]
  (throw (ex-info "bad-macro is bad" {})))
