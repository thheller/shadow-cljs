(ns shadow.repl.protocols)

(defprotocol ILevelCallback
  (will-enter-level [x level])
  (did-exit-level [x level]))
