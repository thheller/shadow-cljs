(ns shadow.cli-util-test
  (:require
    [clojure.test :refer (deftest is)]
    [clojure.pprint :refer (pprint)]
    [clojure.string :as str]
    [shadow.cli-util :refer (parse-args)]
    [shadow.cljs.devtools.cli-opts :as cli-opts]
    ))

(deftest parse-dummy
  (let [config
        {:aliases
         {"v" :verbose
          "h" :help}

         :commands
         {:compile
          {:args-mode :at-least-one
           :default-options {}
           :default-flags #{}
           :flags #{:yo}
           :options {}}

          :clj-repl
          {:args-mode :none
           :default-options {}
           :default-flags #{}
           :options
           {:dummy
            {:parse str/upper-case
             :multiple true}}}

          :run
          {:args-mode :eat-all
           :default-options {}
           :default-flags #{}
           :options {}}

          :clj-run
          {:alias-of :run}}}]

    (let [{:keys [error flags command arguments options] :as x}
          (parse-args config ["--help"])]

      (is (nil? error))
      (is (= [] arguments))
      (is (= {:help true} options))
      (is (contains? flags :help))
      (is (nil? command)))

    (let [{:keys [error flags command arguments options] :as x}
          (parse-args config ["unknown"])]
      (is error))

    (let [{:keys [error flags command arguments options] :as x}
          (parse-args config ["compile"])]
      (is error))

    (let [{:keys [error flags command arguments options] :as x}
          (parse-args config ["compile" "foo" "bar" "-v" "--yo"])]

      (is (nil? error))
      (is (= ["foo" "bar"] arguments))
      (is (= {:yo true :verbose true} options))
      (is (= #{:verbose :yo} flags))
      (is (= :compile command)))

    (let [{:keys [error flags command arguments options] :as x}
          (parse-args config ["clj-repl" "--dummy=bar" "--dummy=foo" "-v"])]

      (is (nil? error))
      (is (= [] arguments))
      (is (= ["BAR" "FOO"] (:dummy options)))
      (is (= #{:verbose} flags))
      (is (= :clj-repl command)))

    (let [{:keys [error flags command arguments options] :as x}
          (parse-args config ["run" "shadow.blubb" "--verbose" "args" "1" "2" "3" "-x"])]

      (is (nil? error))
      (is (= ["shadow.blubb" "--verbose" "args" "1" "2" "3" "-x"] arguments))
      (is (= {} options))
      (is (= #{} flags))
      (is (= :run command)))))

(deftest parse-with-init-command
  (let [config
        {:aliases
         {"v" :verbose}

         :init-command :create

         :commands
         {:create
          {:args-mode :single}}}]

    (let [{:keys [error flags command arguments options] :as x}
          (parse-args config ["foo-bar" "-v"])]

      (is (nil? error))
      (is (= ["foo-bar"] arguments))
      (is (= {:verbose true} options))
      (is (= #{:verbose} flags))
      (is (= :create command)))

    (let [{:keys [error flags command arguments options] :as x}
          (parse-args config ["-v"])]
      (is (some? error))
      )))

(deftest upgrade-old-args-style
  (let [old
        ["-d" "cider-nrepl:1.2.3"
         "--hello=world" "-v"
         "--hello" "world"
         "--dependency" "yoo"]]

    (is (= ["--dependency=cider-nrepl:1.2.3"
            "--hello=world"
            "-v"
            "--hello" "world"
            "--dependency=yoo"]
          (cli-opts/upgrade-args old)
          ))))

