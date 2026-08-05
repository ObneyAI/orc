# ORC Value Storage

ORC records every blackboard write as a canonical
`:sheet/execution-value-written` event. Where the raw bytes live is an explicit
runtime choice:

- `:event-store` (the default) keeps `:value` in the event.
- `:file-store` writes every value to the configured file store and puts a
  `:value-reference` descriptor in the event.

There is no size threshold, type inspection, or fallback policy. One execution
context uses one configured mode for every canonical value.

## Configure a backend

Start a file store, then place it in the ORC context. A store can be supplied at
`:orc/file-store` or nested under `[:orc/value-storage :file-store]`.

```clojure
(require '[ai.obney.orc.file-store.interface :as file-store]
         '[ai.obney.orc.file-store-local.interface])

(def store
  (file-store/start
    {:type :local
     :storage-dir-path "/var/lib/orc/values"}))

(def ctx
  {:event-store event-store
   :tenant-id tenant-id
   :orc/file-store store
   :orc/value-storage {:type :file-store
                       :prefix "orc/values"}})
```

The local backend rejects file IDs that escape its configured directory and
creates parent directories as needed.

For S3:

```clojure
(require '[ai.obney.orc.file-store-s3.interface])

(def store
  (file-store/start
    {:type :s3
     :s3-bucket "orc-files"
     :aws/region "us-east-1"}))
```

For LocalStack, add `:localstack/enabled true` and
`:localstack/endpoint "http://localhost:4566"`. The endpoint currently uses
LocalStack's standard `localhost:4566` address.

The full ORC package includes the local and S3 implementations. Consumers of a
smaller package must include the backend component whose `:type` they select.

## Object and event shape

Values are Nippy-encoded and written before the event is appended. The default
object key is:

```text
orc/values/<tenant-id-or-default>/<tick-id>/<value-id>.nippy
```

Change only the leading namespace with `[:orc/value-storage :prefix]`.
The canonical event contains metadata instead of the raw value:

```clojure
{:event/type :sheet/execution-value-written
 :tick-id tick-id
 :value-id value-id
 :value-profile {...}
 :value-reference
 {:file-id "orc/values/default/.../...nippy"
  :byte-size 18432
  :content-hash "sha256:..."
  :format :nippy}}
```

The reference is not a storage-policy decision or a deduplication key. It is a
durable pointer plus integrity metadata.

## Reads, traces, and failures

Callers continue to use normal ORC result and query APIs. ORC transparently
rehydrates referenced values for execution delivery, nested/delegated value
resolution, RLM reads, and `:sheet/node-trace-detail`.

On every read ORC verifies the byte length and SHA-256 digest before decoding.
A missing, truncated, corrupt, or unsupported object raises an error; ORC does
not silently return a partial value or fall back to an inline copy. Because the
object write completes before its event is appended, a successfully appended
reference never intentionally points at a write that ORC skipped.

Changing storage mode affects new writes only. Existing inline events and
referenced events can be read together, provided the referenced backend remains
available in the context.
