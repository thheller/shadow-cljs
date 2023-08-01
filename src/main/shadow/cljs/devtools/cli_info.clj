(ns shadow.cljs.devtools.cli-info)

(defn find-version-from-pom [pom-xml]
  (let [[_ version :as m]
        (->> (slurp pom-xml)
             (re-find #"<version>([^<]+)</version>"))]
    (when m
      version)))

(defn find-version []
  ;; basically io/resource, just trying to keep this ns lean and fast starting
  (let [pom-xml (-> (Thread/currentThread)
                    (.getContextClassLoader)
                    (.getResource "META-INF/maven/thheller/shadow-cljs/pom.xml"))]

    (if (nil? pom-xml)
      "<snapshot>"
      (find-version-from-pom pom-xml))))

(defn -main [& args]
  (println "shadow-cljs version: " (find-version)))
