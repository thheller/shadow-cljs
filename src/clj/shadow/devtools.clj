(ns shadow.devtools)

(defmacro dump [label obj]
  `(when ~(with-meta 'shadow.devtools/enabled {:tag 'boolean})
     (dump* ~label ~obj)))

(defmacro register! [type handler]
  `(when ~(with-meta 'shadow.devtools/enabled {:tag 'boolean})
     (register* ~type ~handler)))


