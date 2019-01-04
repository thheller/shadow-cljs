(ns demo.browser)

(defmacro test-macro [a b c]
  ;;(throw (ex-info "macro bad" {}))
  (meta *ns*))

(defmacro bad-macro [& args]
  (throw (ex-info "bad-macro is bad" {})))
