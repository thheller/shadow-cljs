(ns demo.chrome-ext-v3.action)

(defn init []
  (-> "page_content"
      js/document.getElementById
      .-innerText
      (set!  "This is an demo action page.")))
