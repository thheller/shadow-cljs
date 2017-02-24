(ns demo.app)

(defn hello []
  (prn "hello world")

  (let [x (js/document.createElement "h1")]
    (set! (.-innerHTML x) "hello world")
    (js/document.body.appendChild x)
    ))

(defn stop []
  (js/console.log "STOP!"))

(defn ^:export start []
  (hello))