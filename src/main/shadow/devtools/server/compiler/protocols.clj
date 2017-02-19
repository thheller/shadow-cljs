(ns shadow.devtools.server.compiler.protocols)

(defprotocol ICompile
  ;; called to configure compiler-state
  (compile-init [this state])
  ;; called before beginning module compilation
  (compile-pre [this state])
  ;; called after module compilation
  (compile-post [this state])
  ;; called to flush output to disk
  (compile-flush [this state]))

(defmulti make-compiler
  (fn [config mode]
    (:target config))
  :default ::default)

(defmethod make-compiler ::default [config mode]
  (throw (ex-info "unsupport build config" config)))

