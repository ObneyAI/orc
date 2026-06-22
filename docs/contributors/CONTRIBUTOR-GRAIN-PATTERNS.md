# Pattern Compendium

> **This doc is for ORC/Grain contributors** building framework internals (`defcommand`, `defreadmodel`, `defprocessor`, `defperiodic`, etc.).
> If you are building behavior-tree workflows with the ORC DSL, see [GETTING-STARTED.md](../GETTING-STARTED.md) instead.

A complete guide to the backend patterns used in this project. Written for AI agents and developers who need to understand how things work and build new features that fit naturally.

---

## Architecture Overview

This is a **Polylith monolith** powered by **Grain** (event-sourced CQRS framework).

**Stack:** Clojure + Grain

**Key directories:**
- `components/{service}/` — Domain services (Polylith bricks)

### When you're unsure — read the source

This compendium covers patterns and conventions, but the **Grain framework** is the real source of truth for how things work at the protocol level. When something is unclear:

1. **Grain source** — Ask the user where the grain repo lives locally (typically `../grain/`). Key files:
   - `components/command-processor-v2/` — how commands are processed, CAS, event storage
   - `components/read-model-processor-v2/` — how projections, L1/L2 caching, and partitioning work
   - `components/event-store-v3/` — how append/read work, tenant isolation, schema validation
   - `components/todo-processor-v2/` — how event-driven processors subscribe and dispatch

2. **nREPL** — Use `/nrepl-connect` to connect to the running system. You can inspect state, evaluate queries, and debug live:
   ```clojure
   ;; Check what's in a read model:
   (rmp/project @(resolve 'ai.obney.orc.orc-dev.core/app) :service/items)

   ;; Run a command manually:
   (cp/process-command (assoc context :command {...}))
   ```

**Do not guess at framework behavior.** Read the source.

---

## Part 1: Backend Patterns

### 1.1 Command Handlers

Commands are defined with `defcommand` from `grain.command-processor-v2.interface`. Each lives in `{service}/core/commands.clj`.

**Structure:**
```clojure
(defcommand :service-name command-name
  {:authorized? auth-fn}
  "Docstring."
  [{{:keys [field1 field2]} :command
    :keys [event-store] :as ctx}]
  ;; 1. Read current state from read models
  ;; 2. Validate (return anomaly on failure)
  ;; 3. Emit events on success
  )
```

**Authorization:** The `:authorized?` fn receives the full context and returns boolean:
- `(constantly true)` — public
- `(fn [ctx] (some? (:auth-claims ctx)))` — requires login
- Custom role checks on `(:auth-claims ctx)`

**Validation pattern** — use `cond` with early anomaly returns:
```clojure
(cond
  (not entity)
  {::anom/category ::anom/not-found
   ::anom/message "Not found."}

  (not (:active entity))
  {::anom/category ::anom/conflict
   ::anom/message "Entity is not active."}

  :else
  {:command-result/events [...]})
```

**Anomaly categories:** `::anom/not-found`, `::anom/conflict` (business rule), `::anom/forbidden`, `::anom/incorrect` (bad input)

**Event emission:**
```clojure
{:command-result/events
 [(->event {:type :service/thing-happened
            :tags #{[:entity entity-id]}
            :body {:entity-id entity-id
                   :field1 value1}})]}
```

**CAS (optimistic concurrency):**
```clojure
{:command-result/events [...]
 :command-result/cas {:types event-types
                      :predicate-fn (fn [_events]
                                      (= expected-status (:status (rm/get-thing ctx id))))}}
```

### 1.2 Query Handlers

Queries are defined with `defquery` from `grain.query-processor.interface`. Each lives in `{service}/core/queries.clj`.

**Basic query:**
```clojure
(defquery :service-name some-query
  {:authorized? auth-fn}
  "Docstring."
  [{:keys [auth-claims query] :as ctx}]
  {:query/result {:some "data"}})
```

**Reading from read models:**
```clojure
(let [items (vals (rmp/project ctx :service/items))
      item (get (rmp/project ctx :service/items) item-id)]
  ...)
```

**Partitioned read model access:**
```clojure
(rmp/project ctx :service/items-by-date {:partition-key [location-id date-str]})
```

### 1.3 Read Models

Read models are defined with `defreadmodel` from `grain.read-model-processor-v2.interface`. Each lives in `{service}/core/read_models.clj`.

**Pattern:**
```clojure
;; 1. Define event type set
(def thing-event-types
  #{:service/thing-created
    :service/thing-updated
    :service/thing-deleted})

;; 2. Define reducer multimethod
(defmulti things* (fn [_state event] (:event/type event)))

(defmethod things* :service/thing-created
  [state {:keys [thing-id name]}]
  (assoc state thing-id {:id thing-id :name name :active true}))

(defmethod things* :service/thing-updated
  [state {:keys [thing-id changes]}]
  (update state thing-id merge changes))

(defmethod things* :service/thing-deleted
  [state {:keys [thing-id]}]
  (dissoc state thing-id))

(defmethod things* :default [state _] state)

;; 3. Register
(defreadmodel :service things
  {:events thing-event-types :version 1}
  [state event] (things* state event))
```

State is a flat map: `{entity-id entity-data, ...}`. Increment `:version` when the reducer logic changes to bust the cache.

**Partitioned read models** (for large datasets):
```clojure
(defreadmodel :service things-by-category
  {:events thing-event-types
   :version 1
   :partition-fn (fn [entity] (:category-id entity))
   :entity-id-fn :id}
  [state event] (things* state event))
```

**Query helpers** — provide convenience functions for common lookups:
```clojure
(defn get-thing [ctx thing-id]
  (get (rmp/project ctx :service/things) thing-id))

(defn get-active-things [ctx]
  (->> (vals (rmp/project ctx :service/things))
       (filter :active)))
```

### 1.4 Todo Processors

Event-driven side effects. Defined in `{service}/core/todo_processors.clj`.

**Pattern:**
```clojure
(defn send-notification
  [{{:keys [user-id message]} :event :as context}]
  (cp/process-command
   (assoc context :command {:command/id (random-uuid)
                            :command/timestamp (time/now)
                            :command/name :notifications/send
                            :user-id user-id
                            :message message})))

(def todo-processors
  {:service/send-notification
   {:handler-fn #'send-notification
    :topics [:service/thing-happened]}})
```

- Receive the triggering `:event` in context
- Typically dispatch a follow-up command via `cp/process-command`
- Use var references (`#'fn`) for REPL reloading
- `:topics` — event types that trigger this processor

### 1.4.1 Periodic Tasks

Scheduled background jobs. Defined in `{service}/core/periodic_tasks.clj`.

**Pattern:**
```clojure
(defn send-reminders
  "Scan for items due soon and send notifications."
  [context _time]
  (let [items (rm/get-pending-items context)]
    (doseq [item items]
      (cp/process-command
       (assoc context :command {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :service/send-reminder
                                :item-id (:id item)})))))

(def periodic-tasks
  {:service/send-reminders
   {:handler-fn #'send-reminders
    :schedule {:cron "0 * * * *" :timezone "America/Chicago"}}})
```

- Function signature: `[context time]` — context is the full system context, time is provided by the scheduler
- `:schedule` accepts either `{:every N :duration :seconds}` or `{:cron "..." :timezone "..."}`
- Use var references (`#'fn`) for REPL reloading
- Wire into the system via the interface, same as todo-processors

**Multi-tenant iteration** — periodic tasks run globally, so iterate over tenants:
```clojure
(defn my-task [context _time]
  (let [tenants (es/tenants (:event-store context))]
    (doseq [tid (keys tenants)]
      (let [ctx (assoc context :tenant-id tid)]
        ;; ... do work scoped to this tenant
        ))))
```

`es/tenants` returns `{tenant-id {:tenant/last-event-id uuid-or-nil}}`. Use `(keys ...)` for just the ids, or read per-tenant metadata from the value.

**CAS for idempotency** — when multiple instances run the same task, use CAS to prevent duplicate work:
```clojure
(es/append (:event-store ctx)
           {:tenant-id (:tenant-id ctx)
            :events [(->event {:type :service/reminder-sent :tags #{[:item item-id]} :body {...}})]
            :cas {:types #{:service/reminder-sent}
                  :tags #{[:item item-id]}
                  :predicate-fn (fn [existing] (empty? (into [] existing)))}})
```

### 1.5 Schemas

Defined with `defschemas` from `grain.schema-util.interface` in `{service}/interface/schemas.clj`.

Three sections — `events`, `commands`, `queries`:
```clojure
(defschemas events
  {:service/thing-created
   [:map [:thing-id :uuid] [:name :string]]})

(defschemas commands
  {:service/create-thing
   [:map [:name [:string {:min 1 :error/message "Required"}]]]})

(defschemas queries
  {:service/things-page [:map]})
```

Uses Malli syntax. Common patterns: `:uuid`, `:string`, `[:enum :a :b]`, `[:maybe :string]`, `[:vector ...]`, `[:map-of :uuid ...]`.

### 1.6 Test Patterns

**Test context:**
```clojure
(h/with-test-context [ctx]
  ;; ctx has: event-store, cache, tenant-id, registries, mock email, jwt-secret
  ...)
```

**Running commands in tests:**
```clojure
(let [result (h/process-command! ctx {:command/name :service/do-thing :field "value"})]
  (is (h/event-of-type? result :service/thing-done))
  (let [event (h/find-event result :service/thing-done)]
    (is (= "value" (:field event)))))
```

**Testing failures:**
```clojure
(let [result (h/process-command! ctx {:command/name :service/do-thing ...})]
  (is (= ::anom/conflict (::anom/category result))))
```

**Auth in tests:**
```clojure
(let [{:keys [user-id]} (h/sign-up-and-verify! ctx :email-address "test@example.com")]
  (h/process-command! (h/with-auth ctx {:user-id user-id :email "test@example.com"})
                      {:command/name :service/do-thing}))
```

### 1.7 Event Tagging Strategy

Every event carries a `:tags` set — tuples of `[:entity-type entity-id]`. Tags serve the purpose of efficient filtered reads.

**Tag design rules:**

1. **Always tag with the primary entity:**
```clojure
(->event {:type :service/item-created
          :tags #{[:item item-id]}
          :body {...}})
```

2. **Add cross-cutting tags for related entities:**
```clojure
(->event {:type :scheduling/appointment-booked
          :tags #{[:appointment appointment-id]
                  [:provider provider-id]
                  [:patient patient-id]}
          :body {...}})
```
This allows querying appointments by provider OR by patient efficiently.

3. **Tenant-wide events use empty tags:**
```clojure
(->event {:type :tenant/settings-updated
          :tags #{}
          :body {...}})
```

**Using tags in read model queries:**
```clojure
;; Full projection (all entities):
(rmp/project ctx :service/items)

;; Tag-filtered projection (single entity — much faster):
(rmp/project ctx :service/items {:tags #{[:item item-id]}})

;; Common helper pattern:
(defn get-item [ctx item-id]
  (get (rmp/project ctx :service/items {:tags #{[:item item-id]}})
       item-id))
```

### 1.8 The Interface Contract

Every Polylith component has an `interface.clj` that defines its public boundary. The pattern is specific:

```clojure
(ns ai.obney.orc.my-service.interface
  (:require ;; Side-effect requires — loading these registers commands/queries in global registries
            [ai.obney.orc.my-service.core.commands]
            [ai.obney.orc.my-service.core.queries]
            ;; Aliased requires for re-export
            [ai.obney.orc.my-service.interface.read-models :as rm]
            [ai.obney.orc.my-service.core.todo-processors :as tp]))

;; Re-export registries for wiring
(def todo-processors tp/todo-processors)

;; Re-export read model helpers as stable public API
(def get-item rm/get-item)
(def get-active-items rm/get-active-items)
```

**What goes where:**
- **Commands/queries** — NOT re-exported. They're accessed by keyword name (`:service/create-item`) through global registries. The `require` is side-effect-only.
- **Todo processors** — re-exported as `todo-processors` map for wiring into Integrant.
- **Read model helpers** — re-exported as stable functions. These are the public API for cross-service data access.
- **Schemas** — live in `interface/schemas.clj` (separate namespace). Required for side-effect registration.

**What stays private (in `core/`):**
- Multimethod reducers (`things*`)
- Event type sets (`thing-event-types`)
- Internal helper functions

### 1.9 Cross-Service Data Access

Any query or command can read from another service's read model. There are two ways:

**Via interface helpers** (preferred — stable API):
```clojure
(:require [ai.obney.orc.other-service.interface :as other])

(let [item (other/get-item ctx item-id)]
  ...)
```

**Via direct projection** (when no helper exists):
```clojure
(:require [ai.obney.grain.read-model-processor-v2.interface :as rmp])

(let [users (rmp/project ctx :user/users)]
  ...)
```

**Cross-service events** — a command in one service can emit events with another service's type. The target service's read model will process them:
```clojure
;; In tenant-service command:
(->event {:type :crm/contact-created    ;; CRM event type, emitted by tenant command
          :tags #{[:contact contact-id]}
          :body {...}})
```

---

## Part 2: Data Flow Summary

```
Command Invocation
  |
  v
cp/process-command
  |
  v
[defcommand handler]
  |
validation + anomaly check
  |
emit events (->event)
  |
command-processor/append
  |
pubsub broadcast
  /             \
 v               v
[Todo Processors]   [Read Model Cache]
(side effects)      (invalidation)
```

---

## Part 3: Decision Recipes

Quick-reference for "I need to build X — what pattern do I use?"

| I need to... | Reach for... | Key files |
|---|---|---|
| **Create/update a domain entity** | `defcommand` returning events | commands.clj |
| **Query domain data** | `defquery` reading from read models | queries.clj, read_models.clj |
| **Read another service's data** | `rmp/project ctx :other/read-model` or interface helper | queries.clj or commands.clj |
| **Filter read model to one entity** | `rmp/project ctx :rm {:tags #{[:entity id]}}` | read_models.clj (helper fn) |
| **React to an event with a side effect** | Todo processor subscribed to event type, dispatches follow-up command | todo_processors.clj |
| **Run something on a schedule** | Periodic task with `:cron` schedule | periodic_tasks.clj |
| **Add a new domain entity** | Full service: schemas -> read model -> commands -> queries -> interface | See checklist below |

---

## Quick Reference: New Feature Checklist

Adding a new feature? Create these files:

1. **`components/{service}/interface/schemas.clj`** — event + command + query schemas
2. **`components/{service}/core/read_models.clj`** — event type sets, reducer multimethods, defreadmodel, query helpers
3. **`components/{service}/core/commands.clj`** — defcommand handlers
4. **`components/{service}/core/queries.clj`** — defquery handlers
5. **`components/{service}/core/todo_processors.clj`** — event-driven side effects (if needed)
6. **`components/{service}/interface.clj`** — public API (re-export commands, queries, read models, todo-processors registries)
7. **`components/{service}/test/test_helpers.clj`** — test factories
8. **`components/{service}/test/*_test.clj`** — tests

Then wire the interface + schemas into the system:
- Add `require` for the interface + schemas
- Add todo-processors to the system's todo-processor concat

---

## Debugging

When something doesn't work, use **nREPL** (`/nrepl-connect`) and **the server console logs** (mulog). Do not guess — inspect live state.

### Common problems and how to diagnose them

**Command returns success but events aren't stored:**
```clojure
;; Via nREPL — read events directly:
(into [] (es/read (:event-store ctx) {:tenant-id tenant-id :types #{:service/thing-created}}))
;; Empty? Check if the command schema is registered (defschemas in interface/schemas.clj loaded?)
;; The v3 event store validates events against schemas — unregistered types silently fail.
```

**Read model returns nil or empty map:**
```clojure
;; Via nREPL — check if events exist:
(into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{:service/thing-created}}))
;; Check if the read model is registered:
(get @(ai.obney.grain.read-model-processor-v2.interface/global-read-model-registry) :service/things)
;; nil means the namespace wasn't loaded (require the interface)
```

### General debugging via nREPL

```clojure
;; Get the running system context:
(def ctx (:ai.obney.orc.orc-dev.core/context @ai.obney.orc.orc-dev.core/app))

;; Query a read model:
(require '[ai.obney.grain.read-model-processor-v2.interface :as rmp])
(rmp/project ctx :service/things)

;; Run a command:
(require '[ai.obney.grain.command-processor-v2.interface :as cp])
(require '[ai.obney.grain.time.interface :as time])
(cp/process-command (assoc ctx :command {:command/id (random-uuid)
                                         :command/timestamp (time/now)
                                         :command/name :service/create-thing
                                         :name "Test"}))

;; Read raw events:
(require '[ai.obney.grain.event-store-v3.interface :as es])
(into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))

;; Check registered commands:
(keys (cp/global-command-registry))

;; Check registered queries:
(keys @(ai.obney.grain.query-processor.interface/query-registry*))
```
