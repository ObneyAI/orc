(ns ai.obney.orc.demos.pi-agent-loop.core
  "Synchronous dependency-injected transcription of Pi's core agent loop.

   Source: badlogic/pi-mono c49906ec, packages/agent/src/agent-loop.ts.
   Scripted model turns prove orchestration, never model capability."
  (:refer-clojure :exclude [run]))

(declare run)

(defn- emit [state type & [body]]
  (-> state
      (update :events conj (cond-> {:type type :sequence-number (:next-sequence state)}
                             body (merge body)))
      (update :next-sequence inc)))

(defn- append-message [state message]
  (-> state (emit :message-start {:message message})
      (emit :message-end {:message message})
      (update :context conj message) (update :new-messages conj message)))

(defn- append-assistant [state response]
  (let [{:keys [message updates initial-message]}
        (if (:message response) response {:message response :updates []})]
    [(-> (reduce (fn [s update]
                   (emit s :message-update {:message (:message update message)
                                            :assistant-message-event update}))
                 (emit state :message-start
                       {:message (or initial-message
                                     (if (seq updates)
                                       (assoc message :content "") message))})
                 updates)
         (emit :message-end {:message message})
         (update :context conj message) (update :new-messages conj message))
     message]))

(defn- error-result
  ([call message] (error-result call message false))
  ([call message terminate?]
   {:role :tool-result :tool-call-id (:id call) :tool-name (:name call)
    :content message :details {} :error true :terminate (boolean terminate?)}))

(defn- prepare-call [context assistant tools config call aborted?]
  (if-let [tool (get tools (:name call))]
    (try
      (let [call (if-let [f (:prepare-arguments tool)]
                   (assoc call :arguments (f (:arguments call))) call)
            args (if-let [f (:validate-arguments tool)] (f (:arguments call)) (:arguments call))
            before (when-let [f (:before-tool-call config)]
                     (f {:assistant-message assistant :tool-call call
                         :arguments args :context context}))]
        (cond
          (and aborted? (aborted?))
          {:immediate (error-result call "Operation aborted")}

          (:block before)
          {:immediate (error-result call (or (:reason before) "Tool execution was blocked")
                                    (:terminate before))}

          :else
          ;; Pi exposes the validated args object to beforeToolCall and permits
          ;; in-place mutation without a second validation pass. Clojure maps
          ;; are immutable, so :arguments is the explicit equivalent.
          {:call call :tool tool :arguments (if (contains? before :arguments)
                                              (:arguments before) args)}))
      (catch Exception e {:immediate (error-result call (.getMessage e))}))
    {:immediate (error-result call (str "Tool " (:name call) " not found"))}))

(defn- invoke [prepared update! aborted?]
  (if (and aborted? (aborted?))
    (error-result (:call prepared) "Operation aborted")
    (try
      (let [f (get-in prepared [:tool :execute])
            value (if (get-in prepared [:tool :execute-with-updates?])
                    (f (:arguments prepared) update!)
                    (f (:arguments prepared)))]
        {:role :tool-result :tool-call-id (get-in prepared [:call :id])
         :tool-name (get-in prepared [:call :name]) :content (:content value value)
         :details (or (:details value) {}) :error (boolean (:error value))
         :terminate (boolean (:terminate value))})
      (catch Exception e (error-result (:call prepared) (.getMessage e))))))

(defn- finalize [context assistant prepared result config]
  (if-let [f (:after-tool-call config)]
    (try (if-let [replacement (f {:assistant-message assistant :tool-call (:call prepared)
                                  :arguments (:arguments prepared) :result result
                                  :context context})]
           (merge result replacement) result)
         (catch Exception e (error-result (:call prepared) (.getMessage e))))
    result))

(defn- completion [result]
  {:type :tool-execution-end :tool-call-id (:tool-call-id result)
   :tool-name (:tool-name result) :result result :error (:error result)})

(defn- timeline! [timeline counter type body]
  (swap! timeline conj (assoc body :type type :timeline-order (swap! counter inc))))

(defn- sequential-outcome [context assistant tools calls config aborted?]
  (loop [remaining calls results [] timeline []]
    (if (empty? remaining)
      {:results results :timeline timeline}
      (let [call (first remaining)
            item (prepare-call context assistant tools config call aborted?)
            updates (atom [])
            result (if-let [immediate (:immediate item)] immediate
                       (finalize context assistant item
                                 (invoke item #(swap! updates conj %) aborted?) config))
            update-events (mapv #(hash-map :type :tool-execution-update
                                           :tool-call-id (:tool-call-id result)
                                           :tool-name (:tool-name result)
                                           :arguments (get-in item [:call :arguments])
                                           :partial-result %) @updates)
            timeline (into timeline
                           (concat [{:type :tool-execution-start
                                     :tool-call-id (:id call) :tool-name (:name call)
                                     :arguments (:arguments call)}]
                                   update-events [(completion result)
                                                  {:type :tool-result-message
                                                   :message result}]))
            results (conj results result)]
        (if (and aborted? (aborted?))
          {:results results :timeline timeline}
          (recur (rest remaining) results timeline))))))

(defn- parallel-outcome [context assistant prepared config aborted?]
  (let [counter (atom 0)
        timeline (atom [])
        entries
        (mapv (fn [source-index item]
                (if-let [immediate (:immediate item)]
                  (do (timeline! timeline counter (:type (completion immediate))
                                 (dissoc (completion immediate) :type))
                      {:source-index source-index :result immediate})
                  {:source-index source-index
                   :task (fn []
                           (let [update! (fn [partial]
                                           (timeline! timeline counter :tool-execution-update
                                                      {:tool-call-id (get-in item [:call :id])
                                                       :tool-name (get-in item [:call :name])
                                                       :arguments (get-in item [:call :arguments])
                                                       :partial-result partial}))
                                 result (finalize context assistant item
                                                  (invoke item update! aborted?) config)]
                             (timeline! timeline counter :tool-execution-end
                                        (dissoc (completion result) :type))
                             result))}))
              (range) prepared)
        futures (mapv (fn [entry]
                        (if-let [task (:task entry)]
                          (assoc entry :future (future (task))) entry)) entries)
        done (mapv #(assoc % :result (or (:result %) (deref (:future %)))) futures)]
    {:results (mapv :result (sort-by :source-index done))
     :timeline (sort-by :timeline-order @timeline)}))

(defn- prepare-parallel [context assistant tools calls config aborted?]
  (loop [remaining calls prepared [] starts []]
    (if (empty? remaining)
      {:prepared prepared :starts starts}
      (let [call (first remaining)
            starts (conj starts {:tool-call-id (:id call) :tool-name (:name call)
                                 :arguments (:arguments call)})
            prepared (conj prepared (prepare-call context assistant tools config call aborted?))]
        (if (and aborted? (aborted?))
          {:prepared prepared :starts starts}
          (recur (rest remaining) prepared starts))))))

(defn- execute-batch [state tools assistant config aborted?]
  (let [calls (vec (:tool-calls assistant))
        sequential? (or (= :sequential (:tool-execution config))
                        (some #(= :sequential (get-in tools [(:name %) :execution-mode])) calls))
        length? (= :length (:stop-reason assistant))
        [state outcome]
        (cond
          length?
          (let [results (mapv #(error-result % (str "Tool call " (:name %)
                                                      " was not executed because the response was truncated")) calls)
                timeline (mapcat (fn [call result]
                                   [{:type :tool-execution-start
                                     :tool-call-id (:id call) :tool-name (:name call)
                                     :arguments (:arguments call)}
                                    (completion result)
                                    {:type :tool-result-message :message result}])
                                 calls results)]
            [state {:results results :timeline timeline}])

          sequential?
          [state (sequential-outcome (:context state) assistant tools calls config aborted?)]

          :else
          (let [{:keys [prepared starts]}
                (prepare-parallel (:context state) assistant tools calls config aborted?)
                state (reduce #(emit %1 :tool-execution-start %2) state starts)]
            [state (parallel-outcome (:context state) assistant prepared config aborted?)]))
        state (reduce (fn [s entry]
                        (if (= :tool-result-message (:type entry))
                          (append-message s (:message entry))
                          (emit s (:type entry) (dissoc entry :type :timeline-order))))
                      state (:timeline outcome))
        state (if (or length? sequential?) state
                  (reduce append-message state (:results outcome)))]
    [state (:results outcome)
     (and (seq (:results outcome)) (every? :terminate (:results outcome)))]))

(defn continue-run [options]
  (let [messages (:messages options)]
    (when (empty? messages) (throw (ex-info "Cannot continue: no messages in context" {:kind :invalid-context})))
    (when (= :assistant (:role (last messages)))
      (throw (ex-info "Cannot continue from message role: assistant" {:kind :invalid-context})))
    (run (assoc options :prompts []))))

(defn run
  "Run the Pi-shaped loop. Required: :model-turn [messages config]."
  [{:keys [messages prompts model-turn tools take-steering! take-follow-ups!
           prepare-next-turn should-stop-after-turn? aborted? config
           transform-context convert-to-model-messages]
    :or {messages [] prompts [] tools {} config {}}}]
  (let [initial (-> {:context (vec messages) :new-messages [] :events [] :next-sequence 1}
                    (emit :agent-start) (emit :turn-start))
        initial (reduce append-message initial prompts)]
    (loop [state initial current-config config first-turn? true
           pending (vec (or (when take-steering! (take-steering!)) []))]
      (let [state (if first-turn? state (emit state :turn-start))
            state (reduce append-message state pending)
            model-context (cond-> (:context state)
                            transform-context transform-context
                            convert-to-model-messages convert-to-model-messages)
            response (if (and aborted? (aborted?))
                       {:role :assistant :content "" :tool-calls [] :stop-reason :aborted}
                       (model-turn model-context current-config))
            [state assistant] (append-assistant state response)
            terminal? (#{:error :aborted} (:stop-reason assistant))
            calls (vec (:tool-calls assistant))
            [state results terminate?] (if (and (seq calls) (not terminal?))
                                         (execute-batch state tools assistant current-config aborted?)
                                         [state [] false])
            state (emit state :turn-end {:message assistant :tool-results results})
            snapshot (when (and (not terminal?) prepare-next-turn)
                       (prepare-next-turn {:message assistant :tool-results results
                                           :context (:context state) :new-messages (:new-messages state)}))
            state (if-let [context (:context snapshot)] (assoc state :context context) state)
            next-config (merge current-config (:config snapshot) (dissoc snapshot :context :config))
            stop? (or terminal? terminate?
                      (boolean (when (and (not terminal?) should-stop-after-turn?)
                                 (should-stop-after-turn? {:message assistant :tool-results results
                                                           :context (:context state)
                                                           :new-messages (:new-messages state)}))))
            steering (if stop? [] (vec (or (when take-steering! (take-steering!)) [])))
            continue-tools? (and (seq calls) (not terminate?) (not terminal?))]
        (cond
          stop? (-> (emit state :agent-end {:messages (:new-messages state)})
                    (assoc :status (if terminal? (:stop-reason assistant) :completed))
                    (dissoc :next-sequence))
          (or continue-tools? (seq steering)) (recur state next-config false steering)
          :else (let [follow-ups (vec (or (when take-follow-ups! (take-follow-ups!)) []))]
                  (if (seq follow-ups) (recur state next-config false follow-ups)
                      (-> (emit state :agent-end {:messages (:new-messages state)})
                          (assoc :status :completed) (dissoc :next-sequence)))))))))
