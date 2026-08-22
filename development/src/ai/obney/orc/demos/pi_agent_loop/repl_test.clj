(ns ai.obney.orc.demos.pi-agent-loop.repl-test
  (:require [ai.obney.orc.demos.pi-agent-loop.repl :as demo]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [nrepl.core :as nrepl]))

(use-fixtures
  :each
  (fn [test-fn]
    (when (demo/running?) (demo/shutdown!))
    (try (test-fn)
         (finally
           (when (demo/running?) (demo/shutdown!))))))

(defn- assistant [content]
  {:role :assistant :content content :tool-calls [] :stop-reason :stop})

(deftest loopback-nrepl-evaluates-the-public-harness-api
  (let [{:keys [bind port]} (demo/start! 0)]
    (is (= "127.0.0.1" bind))
    (is (pos? port))
    (is (= [] (:sessions (demo/server-info))))
    (with-open [connection (nrepl/connect :host bind :port port)]
      (let [client (nrepl/client connection 3000)
            responses (doall
                       (client {:op "eval"
                                :code (str "(do (require '[ai.obney.orc.demos."
                                           "pi-agent-loop.repl :as pi]) "
                                           "(:bind (pi/server-info)))")}))]
        (is (= ["127.0.0.1"] (nrepl/response-values responses)))))))

(deftest named-sessions-retain-history-and-reject-implicit-state
  (demo/start! 0)
  (let [contexts (atom [])
        model-turn (fn [messages _]
                     (swap! contexts conj (mapv :content messages))
                     (assistant (str "answer-" (count @contexts))))]
    (is (= :alpha (demo/create-session! :alpha {:model-turn model-turn})))
    (is (= "answer-1" (-> (demo/prompt! :alpha "first") :new-messages last :content)))
    (is (= "answer-2" (-> (demo/prompt! :alpha "second") :new-messages last :content)))
    (is (= [["first"] ["first" "answer-1" "second"]] @contexts))
    (is (= [:user :assistant :user :assistant]
           (mapv :role (demo/history :alpha))))
    (is (= 2 (count (demo/results :alpha))))
    (is (seq (demo/last-events :alpha)))
    (is (> (count (demo/event-history :alpha))
           (count (demo/last-events :alpha))))
    (testing "duplicate names are explicit errors"
      (is (= :duplicate-session
             (:kind (ex-data
                     (try (demo/create-session! :alpha {:model-turn model-turn})
                          (catch Exception error error)))))))
    (testing "unknown names never create sessions"
      (is (= :unknown-session
             (:kind (ex-data
                     (try (demo/prompt! :missing "no")
                          (catch Exception error error)))))))
    (is (= :alpha (demo/close-session! :alpha)))
    (is (= [] (demo/sessions)))))

(deftest shutdown-releases-server-system-and-session-state
  (demo/start! 0)
  (demo/create-session! :alpha {:model-turn (fn [_ _] (assistant "done"))})
  (is (= :stopped (demo/shutdown!)))
  (is (= {:running? false :bind "127.0.0.1" :port nil :sessions []}
         (demo/server-info)))
  (is (= :not-running
         (:kind (ex-data
                 (try (demo/context)
                      (catch Exception error error)))))))

(deftest repl-controls-forward-to-the-named-stateful-harness
  (demo/start! 0)
  (let [seen (atom [])
        turns (atom 0)
        model-turn (fn [messages _]
                     (swap! seen conj (mapv :content messages))
                     (assistant (str "turn-" (swap! turns inc))))]
    (demo/create-session! :queued {:model-turn model-turn})
    (is (= :queued (demo/steer! :queued "steered")))
    (is (= :queued (demo/follow-up! :queued "followed")))
    (is (= :completed (:status (demo/prompt! :queued "prompt"))))
    (is (= [["prompt" "steered"]
            ["prompt" "steered" "turn-1" "followed"]]
           @seen))

    (demo/create-session! :stopped {:model-turn model-turn})
    (is (= :stopped (demo/stop-after-turn! :stopped)))
    (is (= 1 (count (filter #(= :turn-start (:type %))
                            (:events (demo/prompt! :stopped "once"))))))

    (let [calls-before @turns]
      (demo/create-session! :aborted {:model-turn model-turn})
      (is (= :aborted (demo/abort! :aborted)))
      (is (= :aborted (:status (demo/prompt! :aborted "never call model"))))
      (is (= calls-before @turns)))))
