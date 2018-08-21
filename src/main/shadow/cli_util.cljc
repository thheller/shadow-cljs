(ns shadow.cli-util
  (:require
    [clojure.string :as str]
    [clojure.set :as set]))

(defn validate-flags [{:keys [flags] :as state}]
  (reduce
    (fn [state check-flag]
      (let [kw-flag (or (get-in state [:command-config :flags check-flag])
                        (get-in state [:command-config :aliases check-flag])
                        (get-in state [:config :flags check-flag])
                        (get-in state [:config :aliases check-flag]))]
        (if-not kw-flag
          (reduced (assoc state :error {:code :invalid-flag :msg (str "Unknown argument '" check-flag "'.")}))
          (-> state
              (assoc-in [:options kw-flag] true)
              (update :flags conj kw-flag)))))
    (assoc state :flags #{})
    flags))

(defn validate-options [{:keys [options] :as state}]
  (reduce-kv
    (fn [state opt-key opt-vals]
      (let [{:keys [multiple parse] :as opt-config}
            (or (get-in state [:command-config :options opt-key])
                (get-in state [:config :options opt-key]))]
        (cond
          (not opt-config)
          (reduced (assoc state :error {:code :invalid-option :msg (str "Unknown argument '" (name opt-key) "'.")}))

          (and (not multiple) (> (count opt-vals) 1))
          (reduced (assoc state :error {:code :duplicated-option :msg (str "Argument '" (name opt-key) "' provided multiple times.")}))

          (and multiple parse)
          (assoc-in state [:options opt-key] (into [] (map parse) opt-vals))

          multiple
          (assoc-in state [:options opt-key] opt-vals)

          :else
          (assoc-in state [:options opt-key] (first opt-vals))
          )))
    (assoc state :options {})
    options))

(defn validate-arguments [{:keys [args-mode command arguments] :as state}]
  (cond
    (not (contains? state :args-mode))
    state

    ;; :none will have failed earlier

    (and (= :single args-mode) (empty? arguments))
    (assoc state :error {:code :missing-argument :msg (str "Command '" (name command) "' requires an argument.")})

    (and (= :at-least-one args-mode) (empty? arguments))
    (assoc state :error {:code :missing-arguments :msg (str "Command '" (name command) "' requires at least one argument.")})

    :else
    (dissoc state :args-mode)))

(defn validate-command [state]
  (-> (reduce
        (fn [state task]
          (if (:error state)
            (reduced state)
            (task state)))
        state
        [validate-arguments
         validate-options
         validate-flags])
      (dissoc :command-config :config :remaining)))

(defn conj-vec [x y]
  (if (nil? x)
    [y]
    (conj x y)))

(defn start-command [state command]
  (let [command-config (get-in state [:config :commands command])]
    (if-not command-config
      (assoc state :error {:code :unknown-command
                           :command command
                           :msg (str "Unknown command '" (name command) "'.")})
      (let [[command command-config]
            (if-let [alias (:alias-of command-config)]
              [alias (get-in state [:config :commands alias])]
              [command command-config])]
        (assoc state
          :command command
          :command-config command-config
          :arguments []
          :args-mode (:args-mode command-config))))))

(defn parse-next [{:keys [command arguments remaining] :as state}]
  (let [next
        (first remaining)

        {:keys [args-mode] :as next-state}
        (update state :remaining rest)]

    (cond
      ;; no further args parsing
      (= :eat-all args-mode)
      (update next-state :arguments conj next)

      ;; long opts
      ;; --foo flag
      ;; --foo=bar with value
      (str/starts-with? next "--")
      (let [val-idx (str/index-of next "=")]
        (if-not val-idx
          (let [key (keyword (subs next 2))]
            (update next-state :flags conj key))
          (let [key (keyword (subs next 2 val-idx))
                value (subs next (inc val-idx))]
            (update-in next-state [:options key] conj-vec value))))

      ;; short -oAl flag aliases
      (str/starts-with? next "-")
      (reduce
        (fn [state char]
          (let [s (str char)]
            (update state :flags conj s)))
        next-state
        (subs next 1))

      (and (= :single args-mode)
           (= 1 (count arguments)))
      (dissoc state :args-mode)

      (= :none args-mode)
      (-> state
          (dissoc :args-mode)
          (recur))

      (or (= :at-least-one args-mode)
          (= :single args-mode))
      (update next-state :arguments conj next)

      (and (nil? args-mode)
           (nil? command))
      (let [command (keyword next)]
        (start-command next-state command))

      (nil? args-mode)
      (assoc next-state :error {:code :invalid-argument :msg (str "Invalid argument '" next "'.")})

      :else
      (throw (ex-info "parser in unknown state" state))
      )))

(defn merge-flags [{:keys [flags aliases] :as x}]
  (assoc x :flags (set/union (set flags) (set (vals aliases)))))

(defn normalize-config [{:keys [commands] :as config}]
  (-> config
      (merge-flags)
      (assoc :commands (reduce-kv (fn [m k v]
                                    (assoc m k (merge-flags v)))
                         commands
                         commands))))

(defn parse-args [{:keys [init-command] :as config} args]
  (let [norm-config (normalize-config config)]

    (loop [{:keys [remaining] :as state}
           (-> {:config norm-config
                :input args
                :remaining args}
               (cond->
                 init-command
                 (start-command init-command)

                 (not init-command)
                 (merge {:command nil
                         :arguments []
                         :options {}
                         :flags #{}})))]
      (cond
        (contains? state :error)
        (dissoc state :config :args-mode :command-config :remaining)

        (not (seq remaining))
        (validate-command state)

        :else
        (-> state
            (parse-next)
            (recur))))))


