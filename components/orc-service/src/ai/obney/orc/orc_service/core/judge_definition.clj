(ns ai.obney.orc.orc-service.core.judge-definition
  "Pure rules of a judge DEFINITION: the rubric it grades by, the purposes its
   results serve, and the model it runs on. A judge is declared once under a
   name and then REVISED when its definition changes (never re-declared).

   Purposes: `:monitoring` results are for performance reporting (feedback may
   be absent); `:learning` results also feed Living Descriptions, harvest and
   GEPA, which need real feedback, so a learning judge must require feedback.

   No effects: the declare/revise commands and the read model call into this."
  (:require [ai.obney.orc.orc-service.core.decision :as decision]
            [clojure.string :as str]))

(def all-purposes #{:monitoring :learning})

(def max-alert-window
  "The largest trailing window an alert may watch: the number of recent outcomes
   a node version's performance keeps in order."
  500)

(defn alert-error
  "nil when `alert` is a valid opt-in performance alert, else a message. An alert
   watches the last `:window` outcomes of a node version and signals when their
   mean score falls below `:below`, provided at least `:min-coverage` of them were
   scored."
  [alert]
  (let [fraction? #(and (number? %) (< 0.0 % 1.000000001))]
    (cond
      (not (map? alert))
      (str "judge :alert must be a map {:below :window :min-coverage}, got " (pr-str alert))

      (not (fraction? (:below alert)))
      (str "judge alert :below must be a number above 0 and at most 1, got " (pr-str (:below alert)))

      (not (and (pos-int? (:window alert)) (<= (:window alert) max-alert-window)))
      (str "judge alert :window must be a positive integer of at most " max-alert-window
           ", got " (pr-str (:window alert)))

      (not (fraction? (:min-coverage alert)))
      (str "judge alert :min-coverage must be a number above 0 and at most 1, got "
           (pr-str (:min-coverage alert))))))

(defn rubric-error
  "nil when `rubric` is a valid judge rubric, else a message. Band rules are the
   banded decision's own (`decision/rubric`); a judge rubric additionally needs
   a non-blank criterion and a feedback mode."
  [rubric]
  (cond
    (not (map? rubric))
    (str "judge rubric must be a map, got " (pr-str rubric))

    (or (not (string? (:criterion rubric))) (str/blank? (:criterion rubric)))
    "judge rubric :criterion must be a non-blank string"

    (not (contains? #{:required :none} (:feedback rubric)))
    (str "judge rubric :feedback must be :required or :none, got " (pr-str (:feedback rubric)))

    :else (:error (decision/rubric rubric))))

(defn effective-purposes
  "The purposes a declared config serves. Absent, they default for compatibility
   (every declared judge has fed the learning loops) to monitoring + learning,
   except a rubric whose feedback is :none, which can only monitor."
  [{:keys [purposes rubric]}]
  (cond
    (some? purposes) (set purposes)
    (= :none (:feedback rubric)) #{:monitoring}
    :else all-purposes))

(defn config-error
  "nil when the judge config is a valid definition, else a message."
  [{:keys [purposes rubric timeout-ms alert] :as config}]
  (cond
    (and (contains? config :assess-failures?) (not (boolean? (:assess-failures? config))))
    (str "judge :assess-failures? must be a boolean, got " (pr-str (:assess-failures? config)))

    (and (= :custom (:type config)) (not (:sheet-id config)))
    "Custom judge type requires :sheet-id"

    (and (some? purposes)
         (not (and (or (set? purposes) (sequential? purposes))
                   (seq purposes)
                   (every? all-purposes purposes))))
    (str "judge :purposes must be a non-empty subset of " (pr-str all-purposes)
         ", got " (pr-str purposes))

    (and (some? rubric) (rubric-error rubric))
    (rubric-error rubric)

    (and (contains? config :alert) (alert-error alert))
    (alert-error alert)

    (and (some? timeout-ms) (not (pos-int? timeout-ms)))
    (str "judge :timeout-ms must be a positive integer, got " (pr-str timeout-ms))

    (and (contains? (effective-purposes config) :learning)
         (some? rubric)
         (not= :required (:feedback rubric)))
    "a learning judge must require feedback: :learning purpose is incompatible with a rubric :feedback :none (Living Descriptions, harvest and GEPA need real feedback)"))
