(ns ai.obney.orc.gepa.metric-identity-persistence-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.core.metric-runtime :as metric]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.grain.event-store-v3.interface :as es]))

(deftest optimization-persists-the-metric-before-evaluation-can-start
  (tu/with-test-context [ctx]
    (let [started (gepa/optimize! ctx
                    {:sheet-id (random-uuid) :trainset [{"question" "q"}]
                     :valset [{"question" "q"}]
                     :metric-fn (fn [_ _] {:score 0.8 :feedback "Original metric"})
                     :metric-id "domain/evidence" :metric-revision "2"
                     :metric-retry-safe? true :config {}})
          opt (:optimization-id started)
          event (first (into [] (es/read (:event-store ctx)
                                {:tenant-id (:tenant-id ctx)
                                 :types #{:gepa/optimization-started}
                                 :tags #{[:optimization opt]}})))
          descriptor (:metric-descriptor event)]
      (is (uuid? opt) (pr-str started))
      (is (= {:kind :custom :metric-id "domain/evidence" :revision "2"
              :retry-safe? true}
             (select-keys descriptor [:kind :metric-id :revision :retry-safe?])))
      (is (= descriptor (:metric-descriptor (gepa/get-optimization-summary ctx opt))))
      (when descriptor
        (is (= {:score 0.8 :feedback "Original metric"}
               ((:metric-fn (metric/resolve-metric ctx descriptor)) {} {})))
        (metric/unregister! ctx descriptor)
        (is (= :metric-registration-missing
               (:reason (metric/resolve-metric ctx descriptor))))))))

(deftest omitted-configuration-uses-the-public-defaults
  (tu/with-test-context [ctx]
    (let [started (gepa/optimize! ctx {:sheet-id (random-uuid)
                                      :trainset [{"question" "q"}]
                                      :valset [{"question" "q"}]})]
      (is (uuid? (:optimization-id started)) (pr-str started))
      (is (= 50 (get-in started [:config :max-metric-calls]))))))
