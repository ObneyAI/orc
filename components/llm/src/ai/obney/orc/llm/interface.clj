(ns ai.obney.orc.llm.interface
  "ORC's provider-facing structured prediction boundary."
  (:require [ai.obney.orc.llm.core :as core]))

(def predict core/predict)
(def predict-stream-v2 core/predict-stream-v2)
(def decode-provider-value core/decode-provider-value)
(def register-provider! core/register-provider!)
(def quick-setup! core/quick-setup!)
(def list-providers core/list-providers)
