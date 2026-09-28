# RS7-PS handoff — persistent Postgres event store for the traffic sweeps

Development tooling only. Work in `/Users/darylroberts/Desktop/Code/orc-convergence-arc`; no commits; never touch
`components/**`, `bases/**` or `specs/**`. Another agent (CV-C) is editing `components/ontology/**` at the same time.

## Why

The RS-7 arms run about seven hours each. Today the bench runner (`development/bench/runner.clj`, `create-context`)
starts an in-memory Grain event store with a random tenant and an LMDB cache under `/tmp`. A crash loses the store
(the harness's event snapshots every 25 tasks only partly cover this), and nothing can be queried after the run.
The user wants a store that persists: Postgres.

## What exists

- A local Postgres 16 container `orc-rs7-postgres`, host `127.0.0.1`, port `5435`, user `orc`, database `orc_rs7`,
  trust auth bound to localhost (no password; never add one to any file). Create further databases with
  `docker exec orc-rs7-postgres createdb -U orc <name>`.
- The pinned Grain revision (`dbf5b5229aad09785e40f71e96adf65c90f971d5`, the sha every Grain dep in the root
  `deps.edn` uses) ships `projects/grain-event-store-postgres-v3`. Its store starts through the event-store-v3
  `start` with `{:conn {:type :postgres …}}`; the datasource is hikari (`interface/datasource.clj`,
  `make-datasource :password`: the conn map minus `:auth` plus `:adapter "postgresql"`, `:username`, `:password`).
  Read that component's `core.clj` and `interface.clj` for the exact conn keys, schema setup and tenant handling
  before writing any code. The SQLite v3 store is already a root dependency, wired the same way (see the
  `deps.edn` comment); mirror how it is declared.

## The change

1. Root `deps.edn`: add `obneyai/grain-event-store-postgres-v3` at the same sha with
   `:deps/root "projects/grain-event-store-postgres-v3"`, placed and commented like the SQLite entry. Confirm the
   classpath resolves once (`clojure -Spath` succeeds) and that only one copy of each shared Grain namespace loads.
2. `runner/create-context` and `runner/start!` take an optional options map:
   `:event-store-conn` (default `{:type :in-memory}`), `:tenant-id` (default random), `:cache-dir` (default a fresh
   directory under `development/bench/.runner-cache/`, not `/tmp`; add that directory to `.gitignore`). Every existing
   caller keeps working unchanged with no options.
3. Resume on a persistent store: when `start!` connects to a store that already holds events for the given tenant,
   it must NOT seed the corpus and padding documents again. It projects the existing events (the same
   `drive-projectors!` path the harness's `restore!` uses) and rebuilds the ColBERT index once. Decide "already
   seeded" from the store's own events for that tenant, never from a flag file.
4. `rs7-traffic-sweep/run-pass!` and `run-e2e!` accept `:store {:kind :in-memory}` or
   `{:kind :postgres :database "<name>" :tenant-id "<uuid>"}` and pass it through to `runner/start!`. `run.edn`
   records the store kind, database name and tenant id (never credentials). On resume with a Postgres store, reuse
   the tenant id recorded in the run's `run.edn`.
5. A launcher note in the harness docstring: one database per arm and pass (for example `rs7_pre_fix_p1`), created
   before the run.

## Tests

Focused only; never the repository-wide suite or any `-M:poly test`. Run namespaces with
`clojure -J-Djava.awt.headless=true -J-Xmx2g -M:dev:test <script.clj>` and check the exit status.
- Extend `development/test/rs7_traffic_sweep_test.clj` (loaded with `load-file`) where the behaviour is pure.
- One live store test against the real container, in a throwaway database you create and drop: start the runner on
  Postgres with a fixed tenant, record that seeding happened (event count), stop it, start again with the same
  tenant, and prove no second seeding (same event count before classification) and that a tree-class concept
  written in the first session reads back in the second.
- The existing harness tests stay green.
- A 3-task smoke of `run-pass!` on a Postgres store, killed after the second task and resumed, finishing all three,
  with `run.edn` showing the store. Report its record statuses.

## Rules

One JVM at a time; never kill a JVM you did not start (a corpus-generation JVM may be running in this worktree).
Every JVM you start exits via `System/exit`. Never write or print any API key. Report faithfully, including any
step you could not complete.

## Report back

Files changed; the conn map you used, with the source line in Grain that defines each key; the live test's event
counts in both sessions; the smoke's statuses and `run.edn` store fields; what you could not verify; orphan check.
