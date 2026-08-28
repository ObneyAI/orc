(ns ai.obney.orc.orc-service.core.iteration-evidence
  "Shared limits and pure normalization for durable researcher evidence.")

(def reasoning-max-chars
  "Measured maximum reasoning length in the checked-in live RR-5 evidence bank."
  438)

(def error-excerpt-max-chars
  "Measured maximum error length in the checked-in live RR-5 evidence bank."
  175)

(defn bound-text
  "Keep at most max-chars from the exact prefix of one durable evidence string."
  [text max-chars]
  (when (some? text)
    (let [text (str text)]
      (subs text 0 (min (count text) max-chars)))))
