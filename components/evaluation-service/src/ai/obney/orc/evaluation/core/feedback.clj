(ns ai.obney.orc.evaluation.core.feedback)

(defrecord ScoreWithFeedback [score feedback dimensions]
  Object
  (toString [_]
    (format "ScoreWithFeedback(score=%.3f, feedback='%s...')"
            score
            (subs feedback 0 (min 50 (count feedback))))))

(defrecord MetricDimension [name weight score feedback]
  Object
  (toString [_]
    (format "MetricDimension(%s: %.2f @ weight=%.2f)"
            name score weight)))
