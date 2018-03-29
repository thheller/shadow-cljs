(ns shadow.core-ext)

(defn safe-pr-str
  "cider globally sets *print-length* for the nrepl-session which messes with pr-str when used to print cache or other files"
  [x]
  (binding [*print-length* nil
            *print-level* nil
            *print-namespace-maps* nil
            *print-meta* nil]
    (pr-str x)
    ))
