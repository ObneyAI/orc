(ns ai.obney.orc.orc-service.core.trace-time
  "Canonical trace timestamp conversion and chronological comparison."
  (:import [java.time Instant OffsetDateTime ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(defn ->instant [value]
  (cond
    (nil? value) nil
    (instance? Instant value) value
    (instance? OffsetDateTime value) (.toInstant ^OffsetDateTime value)
    (instance? ZonedDateTime value) (.toInstant ^ZonedDateTime value)
    (string? value) (try
                      (Instant/from (.parse DateTimeFormatter/ISO_DATE_TIME ^String value))
                      (catch Exception _ nil))
    :else nil))

(defn canonical-string [value]
  (when value
    (if-let [instant (->instant value)] (str instant) (str value))))

(defn compare-timestamps [left right]
  (let [left-instant (->instant left)
        right-instant (->instant right)]
    (if (and left-instant right-instant)
      (compare left-instant right-instant)
      (compare (str left) (str right)))))

(defn elapsed-ms [started-at completed-at]
  (when-let [started (->instant started-at)]
    (when-let [completed (->instant completed-at)]
      (max 0 (- (.toEpochMilli completed) (.toEpochMilli started))))))
