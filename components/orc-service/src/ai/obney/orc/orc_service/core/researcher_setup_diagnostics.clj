(ns ai.obney.orc.orc-service.core.researcher-setup-diagnostics
  "Allowlisted schema-refusal evidence, independent of command values and handlers.")

(defn- humanized? [value]
  (cond
    (or (nil? value) (string? value)) true
    (vector? value) (every? humanized? value)
    (and (map? value) (not (record? value)))
    (every? (fn [[field detail]]
              (and (or (keyword? field) (string? field) (int? field))
                   (humanized? detail))) value)
    :else false))

(defn from-refusal
  "Select only protocol identity and Grain's humanized field explanation.
   The intended public refusal message is retained only when it is a string.
   Raw commands and unrelated handler data are not diagnostics."
  [command-name result]
  (when (and (keyword? command-name)
             (keyword? (:cognitect.anomalies/category result)))
    (cond-> {:command-name command-name
             :category (:cognitect.anomalies/category result)
             :reason :generated-child-setup-rejected}
      (string? (:cognitect.anomalies/message result))
      (assoc :message (:cognitect.anomalies/message result))
      (and (some? (:error/explain result)) (humanized? (:error/explain result)))
      (assoc :error/explain (:error/explain result)))))

(defn from-exception
  "Revalidate the same allowlist when crossing an exception boundary."
  [error]
  (let [diagnostic (:setup-diagnostic (ex-data error))]
    (when (= :generated-child-setup-rejected (:reason diagnostic))
      (from-refusal (:command-name diagnostic)
                    (cond-> {:cognitect.anomalies/category (:category diagnostic)}
                      (string? (:message diagnostic))
                      (assoc :cognitect.anomalies/message (:message diagnostic))
                      (contains? diagnostic :error/explain)
                      (assoc :error/explain (:error/explain diagnostic)))))))

(defn- field-paths [detail path]
  (cond
    (map? detail)
    (mapcat (fn [[field value]] (field-paths value (conj path field)))
            (sort-by (comp pr-str key) detail))
    (vector? detail)
    (if (every? string? detail)
      (when (seq detail) [path])
      (mapcat (fn [[index value]] (field-paths value (conj path index)))
              (map-indexed vector detail)))
    (string? detail) [path]
    :else []))

(defn render
  "Put the affected field before optional details for bounded iteration excerpts.
   Full humanized detail remains separately available as structured evidence."
  [diagnostic]
  (str (when-let [field (first (field-paths (:error/explain diagnostic) []))]
         (str "Setup field " (pr-str field) "; "))
       "command " (:command-name diagnostic)
       "; category " (:category diagnostic)
       "; reason " (:reason diagnostic)
       (when-let [message (:message diagnostic)] (str "\nRefusal: " message))
       (when (contains? diagnostic :error/explain)
         (str "\nSchema explanation: " (pr-str (:error/explain diagnostic))))))
