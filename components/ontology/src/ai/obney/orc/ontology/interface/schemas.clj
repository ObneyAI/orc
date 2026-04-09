(ns ai.obney.orc.ontology.interface.schemas
  "Ontology component schemas - defines commands, events, and queries for
   the event-sourced ontology system.

   Three-layer ontology system:
   - Failure Ontology: Why things go wrong (Hallucination, InstructionViolation, etc.)
   - Success Ontology: What makes things work (StructuralPatterns, InstructionPatterns)
   - Problem Domain: What types of problems exist (Classification, InformationRetrieval)"
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Shared Domain Schemas
;; =============================================================================

(def ontology-scope
  "Scope levels for ontology concepts"
  [:enum :failure :success :problem :node-type :custom])

(def severity-level
  "Severity levels for failures"
  [:enum :low :medium :high :critical])

(def pattern-type
  "Types of patterns that can be learned"
  [:enum :search :instruction :execution :structural])

(def node-type
  "Node types that can learn patterns"
  [:enum :llm :repl-researcher :code :map-each :condition :llm-condition])

;; =============================================================================
;; Event Schemas
;; =============================================================================

(defschemas events
  {;; -------------------------------------------------------------------------
   ;; Ontology Lifecycle Events
   ;; -------------------------------------------------------------------------

   :ontology/ontology-created
   [:map
    [:ontology-id :uuid]
    [:name :string]
    [:scope ontology-scope]
    [:description {:optional true} :string]
    [:base-uri {:optional true} :string]
    [:created-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Concept Events
   ;; -------------------------------------------------------------------------

   :ontology/concept-created
   [:map
    [:ontology-id :uuid]
    [:concept-id :uuid]
    [:uri :string]                        ;; e.g., "failure:Hallucination"
    [:label :string]
    [:description :string]
    [:scope ontology-scope]
    [:broader {:optional true} [:vector :string]]  ;; Parent URIs
    [:indicators {:optional true} [:vector :string]]  ;; Text patterns
    [:created-at :string]]

   :ontology/concept-updated
   [:map
    [:concept-id :uuid]
    [:changes [:map-of :keyword :any]]
    [:updated-at :string]]

   :ontology/relationship-created
   [:map
    [:relationship-id :uuid]
    [:source-uri :string]
    [:target-uri :string]
    [:predicate :string]                  ;; "skos:broader", "skos:related", "owl:causes"
    [:properties {:optional true} [:map-of :keyword :any]]
    [:created-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Tree Profile Events
   ;; -------------------------------------------------------------------------

   :ontology/tree-strength-recorded
   [:map
    [:tree-id :uuid]
    [:pattern-uri :string]                ;; e.g., "success:MultiSourceGathering"
    [:confidence :double]
    [:evidence-trace-ids [:vector :uuid]]
    [:avg-score :double]
    [:recorded-at :string]
    ;; Domain-agnostic rich context fields for self-learning
    [:context-conditions {:optional true} [:map-of :keyword :any]]
    [:state-conditions {:optional true} [:map-of :keyword :any]]  ;; backward compat
    [:action-taken {:optional true} [:map
                                     [:type {:optional true} :string]
                                     [:target {:optional true} :any]
                                     [:reason {:optional true} :string]]]
    [:domain-type {:optional true} :string]
    [:expected-outcome {:optional true} :string]]

   :ontology/tree-weakness-recorded
   [:map
    [:tree-id :uuid]
    [:failure-uri :string]                ;; e.g., "failure:Hallucination"
    [:subtype-uri {:optional true} :string]
    [:frequency :double]
    [:severity severity-level]
    [:triggers [:vector :string]]
    [:evidence-trace-ids [:vector :uuid]]
    [:recorded-at :string]
    ;; Domain-agnostic rich context fields for self-learning
    [:failure-context {:optional true} [:map-of :keyword :any]]
    [:failure-conditions {:optional true} [:map-of :keyword :any]]  ;; backward compat
    [:attempted-action {:optional true} [:map
                                         [:type {:optional true} :string]
                                         [:target {:optional true} :any]
                                         [:reason {:optional true} :string]]]
    [:domain-type {:optional true} :string]]

   :ontology/tree-problem-mapping-created
   [:map
    [:tree-id :uuid]
    [:problem-uri :string]                ;; e.g., "problem:Classification"
    [:success-rate :double]
    [:execution-count :int]
    [:recorded-at :string]]

   :ontology/tree-problem-mapping-updated
   [:map
    [:tree-id :uuid]
    [:problem-uri :string]
    [:success-rate :double]
    [:execution-count :int]
    [:updated-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Learned Rules Events (Self-Learning)
   ;; -------------------------------------------------------------------------

   :ontology/learned-rule-extracted
   [:map
    [:rule-id :uuid]
    [:tree-id :uuid]
    [:rule [:map
            [:condition [:map-of :keyword :any]]
            [:action [:map-of :keyword :any]]
            [:confidence :double]
            [:success-rate :double]
            [:evidence-episodes [:vector :uuid]]]]
    [:problem-type :string]
    [:domain-type {:optional true} :string]
    [:extracted-at :string]]

   :ontology/domain-knowledge-added
   [:map
    [:knowledge-id :uuid]
    [:tree-id :uuid]
    [:node-id {:optional true} :uuid]
    [:description :string]
    [:based-on-failure-traces [:vector :uuid]]
    [:impact-score {:optional true} :double]
    [:added-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Node-Level Learning Events
   ;; -------------------------------------------------------------------------

   :ontology/node-pattern-learned
   [:map
    [:node-id :uuid]
    [:sheet-id :uuid]
    [:node-type node-type]
    [:pattern-type pattern-type]
    [:effective? :boolean]
    [:pattern-description :string]
    [:metrics [:map
               [:success-rate {:optional true} :double]
               [:avg-score {:optional true} :double]
               [:failure-rate {:optional true} :double]]]
    [:evidence-trace-ids [:vector :uuid]]
    [:learned-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Discovery Events
   ;; -------------------------------------------------------------------------

   :ontology/failure-subtype-discovered
   [:map
    [:discovery-id :uuid]
    [:parent-uri :string]                 ;; Existing broader concept
    [:proposed-uri :string]
    [:label :string]
    [:description :string]
    [:evidence-count :int]
    [:discovered-at :string]
    [:status [:enum :proposed :approved :rejected]]]

   ;; -------------------------------------------------------------------------
   ;; Embedding Events (Phase 4)
   ;; -------------------------------------------------------------------------

   :ontology/embedding-model-configured
   [:map
    [:ontology-id :uuid]
    [:scope [:enum :failure :success :problem :node-type :custom :all]]
    [:model-id :string]                   ;; "sentence-transformers/all-MiniLM-L6-v2"
    [:dimensions :int]                    ;; 384
    [:configured-at :string]]

   :ontology/concept-embedded
   [:map
    [:uri :string]                        ;; "failure:Hallucination" or "onet:11-1011.00"
    [:ontology-id {:optional true} :uuid] ;; Links to evolutionary ontology
    [:concept-id {:optional true} :uuid]  ;; Legacy individual concept ID
    [:text-embedded :string]              ;; Source text that was embedded
    [:field-source :string]               ;; "label+description" or "triggers"
    [:embedding [:vector :double]]        ;; 384-dim vector (MUST be :double, not Float)
    [:model-id :string]
    [:embedded-at :string]]

   :ontology/tree-profile-embedded
   [:map
    [:tree-id :uuid]
    [:text-embedded :string]              ;; Serialized profile summary
    [:embedding [:vector :double]]
    [:model-id :string]
    [:embedded-at :string]]

   :ontology/evaluation-embedded
   [:map
    [:trace-id :uuid]
    [:dimension :string]                  ;; "Grounding", "Reasoning", etc.
    [:feedback :string]                   ;; Original feedback text
    [:embedding [:vector :double]]
    [:failure-uri {:optional true} :string]  ;; Classified failure
    [:model-id :string]
    [:embedded-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Apartment Search Events
   ;; -------------------------------------------------------------------------

   :apartment/listing-recorded
   [:map
    [:listing-id :uuid]
    [:source-site :string]                ;; "zillow.com", "apartments.com", etc.
    [:source-url :string]                 ;; Full URL to listing
    [:extracted-at :string]               ;; ISO timestamp
    [:title :string]
    [:price :string]                      ;; "$2,500/mo"
    [:price-numeric {:optional true} :double]  ;; 2500.0 for queries
    [:bedrooms {:optional true} :string]  ;; "2 bd" or "2"
    [:bathrooms {:optional true} :string] ;; "1 ba" or "1"
    [:sqft {:optional true} :string]      ;; "850 sqft"
    [:address :string]
    [:neighborhood {:optional true} :string]
    [:city :string]
    [:state :string]
    [:amenities [:vector :string]]        ;; ["Pool", "Gym", "Parking"]
    [:lat {:optional true} :double]
    [:lng {:optional true} :double]]

   :apartment/listing-updated
   [:map
    [:listing-id :uuid]
    [:source-url :string]
    [:previous-price :string]
    [:price :string]
    [:price-numeric {:optional true} :double]
    [:updated-at :string]]

   :apartment/search-recorded
   [:map
    [:search-id :uuid]
    [:location :string]                   ;; "San Francisco, CA"
    [:max-rent {:optional true} :int]
    [:filters {:optional true} [:map-of :keyword :any]]
    [:listing-count :int]
    [:source-sites [:vector :string]]     ;; ["zillow.com", "apartments.com"]
    [:listing-ids [:vector :uuid]]
    [:searched-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Site Registry Events (Multi-Site Discovery)
   ;; -------------------------------------------------------------------------

   :apartment/site-registered
   [:map
    [:site-id :uuid]
    [:domain :string]                     ;; "redfin.com"
    [:display-name :string]               ;; "Redfin"
    [:category [:enum :corporate :peer-to-peer :aggregator :local]]
    [:discovered-via [:enum :manual :web-search :referral]]
    [:url-pattern {:optional true} :string]  ;; "https://www.{domain}/{location}/rentals/"
    [:requires-headed {:optional true} :boolean]
    [:known-challenges {:optional true} [:vector :string]]  ;; ["press-hold", "popup"]
    [:notes {:optional true} :string]
    [:registered-at :string]]

   :apartment/site-trust-updated
   [:map
    [:site-id :uuid]
    [:domain :string]
    [:trust-score :double]                ;; 0.0-1.0
    [:extraction-count :int]              ;; How many successful extractions
    [:last-success-at {:optional true} :string]
    [:last-failure-at {:optional true} :string]
    [:updated-at :string]]

   :apartment/site-pattern-learned
   [:map
    [:site-id :uuid]
    [:domain :string]
    [:pattern-type [:enum :navigation :search :extraction :bot-bypass :pagination]]
    [:pattern-data [:map-of :keyword :any]]  ;; Site-specific tactics
    [:confidence :double]
    [:learned-at :string]]})

;; =============================================================================
;; Command Schemas
;; =============================================================================

(defschemas commands
  {:ontology/create-ontology
   [:map
    [:name :string]
    [:scope ontology-scope]
    [:description {:optional true} :string]
    [:base-uri {:optional true} :string]]

   :ontology/create-concept
   [:map
    [:ontology-id :uuid]
    [:uri :string]
    [:label :string]
    [:description :string]
    [:scope ontology-scope]
    [:broader {:optional true} [:vector :string]]
    [:indicators {:optional true} [:vector :string]]]

   :ontology/create-relationship
   [:map
    [:source-uri :string]
    [:target-uri :string]
    [:predicate :string]]

   :ontology/initialize-static-ontology
   [:map
    [:scope {:optional true} ontology-scope]]  ;; Optional: only initialize specific scope

   :ontology/record-tree-strength
   [:map
    [:tree-id :uuid]
    [:pattern-uri :string]
    [:confidence :double]
    [:evidence-trace-ids [:vector :uuid]]
    [:avg-score :double]
    ;; Domain-agnostic rich context fields
    [:context-conditions {:optional true} [:map-of :keyword :any]]
    [:state-conditions {:optional true} [:map-of :keyword :any]]  ;; backward compat
    [:action-taken {:optional true} [:map
                                     [:type {:optional true} :string]
                                     [:target {:optional true} :any]
                                     [:reason {:optional true} :string]]]
    [:domain-type {:optional true} :string]
    [:expected-outcome {:optional true} :string]]

   :ontology/record-tree-weakness
   [:map
    [:tree-id :uuid]
    [:failure-uri :string]
    [:subtype-uri {:optional true} :string]
    [:frequency :double]
    [:severity severity-level]
    [:triggers [:vector :string]]
    [:evidence-trace-ids [:vector :uuid]]
    ;; Domain-agnostic rich context fields
    [:failure-context {:optional true} [:map-of :keyword :any]]
    [:failure-conditions {:optional true} [:map-of :keyword :any]]  ;; backward compat
    [:attempted-action {:optional true} [:map
                                         [:type {:optional true} :string]
                                         [:target {:optional true} :any]
                                         [:reason {:optional true} :string]]]
    [:domain-type {:optional true} :string]]

   :ontology/record-problem-mapping
   [:map
    [:tree-id :uuid]
    [:problem-uri :string]
    [:success-rate :double]
    [:execution-count :int]]

   :ontology/record-node-pattern
   [:map
    [:node-id :uuid]
    [:sheet-id :uuid]
    [:node-type node-type]
    [:pattern-type pattern-type]
    [:effective? :boolean]
    [:pattern-description :string]
    [:metrics [:map-of :keyword :double]]
    [:evidence-trace-ids [:vector :uuid]]]

   :ontology/add-domain-knowledge
   [:map
    [:tree-id :uuid]
    [:node-id {:optional true} :uuid]
    [:description :string]
    [:based-on-failure-traces [:vector :uuid]]
    [:impact-score {:optional true} :double]]

   :ontology/extract-learned-rules
   [:map
    [:tree-id :uuid]
    [:problem-type :string]
    [:min-episodes {:optional true} :int]
    [:domain-type {:optional true} :string]
    [:domain-description {:optional true} :string]]

   :ontology/classify-evaluation
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:evaluation-result [:map
                          [:score :double]
                          [:dimensions [:vector [:map
                                                 [:name :string]
                                                 [:score :double]
                                                 [:feedback :string]]]]]]
    [:auto-record? {:optional true} :boolean]]

   ;; Embedding Commands (Phase 4)

   :ontology/configure-embedding-model
   [:map
    [:scope [:enum :failure :success :problem :node-type :custom :all]]
    [:model-id :string]                   ;; "sentence-transformers/all-MiniLM-L6-v2"
    [:dimensions {:optional true} :int]]  ;; Auto-detected if not provided

   :ontology/embed-concept
   [:map
    [:uri :string]
    [:fields {:optional true} [:set [:enum :label :description :indicators :triggers]]]]

   :ontology/embed-concepts-batch
   [:map
    [:scope {:optional true} ontology-scope]
    [:uris {:optional true} [:vector :string]]  ;; Specific URIs, or all in scope
    [:fields {:optional true} [:set [:enum :label :description :indicators :triggers]]]]

   :ontology/embed-tree-profile
   [:map
    [:tree-id :uuid]]

   :ontology/embed-evaluation-feedback
   [:map
    [:trace-id :uuid]
    [:dimension :string]
    [:feedback :string]
    [:failure-uri {:optional true} :string]]

   ;; Discovery Commands

   :ontology/run-pattern-discovery
   [:map
    [:sheet-id :uuid]
    [:min-traces {:optional true} :int]
    [:score-threshold {:optional true} :double]]

   ;; -------------------------------------------------------------------------
   ;; Evolutionary Builder Commands (CQRS wrappers)
   ;; -------------------------------------------------------------------------

   :ontology/build-from-sources
   [:map
    [:sources [:vector [:map
                        [:path {:optional true} :string]
                        [:content {:optional true} :string]
                        [:type [:enum "csv" "sql" "text" "rdf" "json"]]]]]
    [:config {:optional true} [:map
                               [:base-uri {:optional true} :string]
                               [:similarity-threshold {:optional true} :double]
                               [:emit-owl-sameAs? {:optional true} :boolean]]]]

   :ontology/evolve
   [:map
    [:ontology-id :uuid]
    [:sources [:vector [:map
                        [:path {:optional true} :string]
                        [:content {:optional true} :string]
                        [:type [:enum "csv" "sql" "text" "rdf" "json"]]]]]
    [:config {:optional true} [:map
                               [:prefer-existing-uris? {:optional true} :boolean]
                               [:similarity-threshold {:optional true} :double]]]]

   :ontology/record-colbert-index
   [:map
    [:ontology-id :uuid]
    [:index-id :uuid]
    [:index-name :string]
    [:document-count :int]
    [:colbert-fields [:vector :keyword]]]

   ;; -------------------------------------------------------------------------
   ;; Apartment Search Commands
   ;; -------------------------------------------------------------------------

   :apartment/record-listing
   [:map
    [:listing-id :uuid]
    [:source-site :string]
    [:source-url :string]
    [:extracted-at :string]
    [:title :string]
    [:price :string]
    [:price-numeric {:optional true} :double]
    [:bedrooms {:optional true} :string]
    [:bathrooms {:optional true} :string]
    [:sqft {:optional true} :string]
    [:address :string]
    [:neighborhood {:optional true} :string]
    [:city :string]
    [:state :string]
    [:amenities {:optional true} [:vector :string]]
    [:lat {:optional true} :double]
    [:lng {:optional true} :double]]

   :apartment/record-search
   [:map
    [:search-id :uuid]
    [:location :string]
    [:max-rent {:optional true} :int]
    [:filters {:optional true} [:map-of :keyword :any]]
    [:listing-count :int]
    [:source-sites [:vector :string]]
    [:listing-ids [:vector :uuid]]]

   ;; -------------------------------------------------------------------------
   ;; Site Registry Commands (Multi-Site Discovery)
   ;; -------------------------------------------------------------------------

   :apartment/register-site
   [:map
    [:domain :string]
    [:display-name :string]
    [:category [:enum :corporate :peer-to-peer :aggregator :local]]
    [:discovered-via [:enum :manual :web-search :referral]]
    [:url-pattern {:optional true} :string]
    [:requires-headed {:optional true} :boolean]
    [:known-challenges {:optional true} [:vector :string]]
    [:notes {:optional true} :string]]

   :apartment/update-site-trust
   [:map
    [:domain :string]
    [:success? :boolean]                  ;; true = successful extraction, false = failure
    [:listings-extracted {:optional true} :int]]

   :apartment/record-site-pattern
   [:map
    [:domain :string]
    [:pattern-type [:enum :navigation :search :extraction :bot-bypass :pagination]]
    [:pattern-data [:map-of :keyword :any]]
    [:confidence {:optional true} :double]]})

;; =============================================================================
;; Query Schemas
;; =============================================================================

(defschemas queries
  {:ontology/get-concepts
   [:map
    [:scope {:optional true} ontology-scope]
    [:broader-uri {:optional true} :string]
    [:include-narrower? {:optional true} :boolean]]

   :ontology/get-concept
   [:map
    [:uri :string]]

   :ontology/get-tree-profile
   [:map
    [:tree-id :uuid]]

   :ontology/find-similar-trees
   [:map
    [:problem-type :string]
    [:required-patterns {:optional true} [:set :string]]
    [:min-success-rate {:optional true} :double]
    [:limit {:optional true} :int]]

   :ontology/find-failure-patterns
   [:map
    [:problem-type :string]
    [:min-frequency {:optional true} :double]]

   :ontology/get-node-type-learnings
   [:map
    [:node-type node-type]]

   :ontology/export-ttl
   [:map
    [:scope {:optional true} ontology-scope]
    [:include-instances? {:optional true} :boolean]
    [:base-uri {:optional true} :string]]

   :ontology/build-context
   [:map
    [:problem-type :string]
    [:required-patterns {:optional true} [:set :string]]]

   ;; Embedding Queries (Phase 4)

   :ontology/semantic-search
   [:map
    [:query :string]
    [:scope {:optional true} ontology-scope]
    [:limit {:optional true} :int]
    [:min-similarity {:optional true} :double]]

   :ontology/hybrid-search
   [:map
    [:query :string]
    [:seed-uris {:optional true} [:vector :string]]
    [:scope {:optional true} ontology-scope]
    [:limit {:optional true} :int]]

   :ontology/get-concept-embedding
   [:map
    [:uri :string]]

   ;; -------------------------------------------------------------------------
   ;; Apartment Search Queries
   ;; -------------------------------------------------------------------------

   :apartment/get-listing
   [:map
    [:listing-id :uuid]]

   :apartment/get-listing-by-url
   [:map
    [:source-url :string]]

   :apartment/get-listings-by-city
   [:map
    [:city :string]
    [:limit {:optional true} :int]]

   :apartment/get-listings-by-site
   [:map
    [:source-site :string]
    [:limit {:optional true} :int]]

   :apartment/get-listings-by-price-range
   [:map
    [:min-price :double]
    [:max-price :double]
    [:city {:optional true} :string]
    [:limit {:optional true} :int]]

   :apartment/get-price-history
   [:map
    [:listing-id :uuid]]

   :apartment/get-recent-searches
   [:map
    [:location {:optional true} :string]
    [:limit {:optional true} :int]]

   ;; -------------------------------------------------------------------------
   ;; Site Registry Queries (Multi-Site Discovery)
   ;; -------------------------------------------------------------------------

   :apartment/get-site
   [:map
    [:domain :string]]

   :apartment/get-trusted-sites
   [:map
    [:min-trust {:optional true} :double]  ;; Default 0.5
    [:limit {:optional true} :int]]

   :apartment/get-site-patterns
   [:map
    [:domain :string]
    [:pattern-type {:optional true} [:enum :navigation :search :extraction :bot-bypass :pagination]]]})

;; =============================================================================
;; Evolutionary Ontology Builder - Shared Domain Schemas
;; =============================================================================

(def source-type
  "Types of data sources for evolutionary ontology building"
  [:enum "csv" "sql" "text" "rdf" "json"])

(def match-type
  "Types of entity matches during resolution"
  [:enum "exact" "semantic"])

(def resolution-mode
  "Modes for entity resolution"
  [:enum "batch" "incremental"])

(def schema-element-type
  "Types of schema elements that can be extended"
  [:enum "class" "object-property" "datatype-property"])

(def ttl-format
  "Serialization formats for ontology snapshots"
  [:enum "turtle" "rdf-xml" "json-ld"])

;; =============================================================================
;; Evolutionary Ontology Builder - Event Schemas
;; =============================================================================

(defschemas evolutionary-events
  {;; -------------------------------------------------------------------------
   ;; Source Registry Events
   ;; -------------------------------------------------------------------------

   :evolutionary/source-registered
   [:map
    [:source-id :uuid]
    [:source-uri :string]
    [:source-type source-type]
    [:content-hash :string]                  ;; SHA-256
    [:file-size :int]
    [:namespace :string]
    [:metadata {:optional true} [:map-of :keyword :any]]
    [:registered-at :string]]

   :evolutionary/source-stats-updated
   [:map
    [:source-id :uuid]
    [:concepts-extracted :int]
    [:triples-generated :int]
    [:entities-resolved :int]
    [:updated-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Concept Extraction Events
   ;; -------------------------------------------------------------------------

   :evolutionary/concepts-extracted
   [:map
    [:source-id :uuid]
    [:ontology-id :uuid]
    [:concepts [:vector [:map
                         [:uri :string]
                         [:label :string]
                         [:definition {:optional true} :string]
                         [:entity-type :string]
                         [:alt-labels {:optional true} [:vector :string]]
                         [:source-id {:optional true} :uuid]
                         [:confidence {:optional true} :double]]]]
    [:extracted-at :string]]

   :evolutionary/relationships-extracted
   [:map
    [:source-id :uuid]
    [:ontology-id :uuid]
    [:relationships [:vector [:map
                              [:subject :string]
                              [:predicate :string]
                              [:object :string]
                              [:confidence {:optional true} :double]]]]
    [:extracted-at :string]]

   :evolutionary/schema-extracted
   [:map
    [:source-id :uuid]
    [:ontology-id :uuid]
    [:classes [:vector [:map
                        [:uri :string]
                        [:label :string]]]]
    [:object-properties [:vector [:map
                                  [:uri :string]
                                  [:domain :string]
                                  [:range :string]]]]
    [:datatype-properties [:vector [:map
                                    [:uri :string]
                                    [:domain :string]
                                    [:datatype :string]]]]
    [:extracted-at :string]]

   ;; -------------------------------------------------------------------------
   ;; T-box/A-box Events (OWL Schema + Individuals)
   ;; -------------------------------------------------------------------------

   :evolutionary/tbox-extracted
   [:map
    [:source-id :uuid]
    [:ontology-id :uuid]
    [:classes [:vector [:map
                        [:uri :string]
                        [:label {:optional true} :string]
                        [:description {:optional true} :string]]]]
    [:object-properties [:vector [:map
                                  [:uri :string]
                                  [:label {:optional true} :string]
                                  [:domain {:optional true} :string]
                                  [:range {:optional true} :string]]]]
    [:datatype-properties [:vector [:map
                                    [:uri :string]
                                    [:label {:optional true} :string]
                                    [:domain {:optional true} :string]
                                    [:datatype {:optional true} :string]]]]
    [:extracted-at :string]]

   :evolutionary/abox-extracted
   [:map
    [:source-id :uuid]
    [:ontology-id :uuid]
    [:individuals [:vector [:map
                            [:uri :string]
                            [:type :string]
                            [:label :string]
                            [:properties {:optional true} [:map-of [:or :keyword :string] :any]]]]]
    [:extracted-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Entity Resolution Events
   ;; -------------------------------------------------------------------------

   :evolutionary/entities-resolved
   [:map
    [:ontology-id :uuid]
    [:resolution-mode resolution-mode]
    [:matches [:vector [:map
                        [:source1-uri :string]
                        [:source2-uri :string]
                        [:similarity-score :double]
                        [:match-type match-type]]]]
    [:canonical-map [:map-of :string :string]]
    [:alignment-triples [:vector [:tuple :string :string :string]]]
    [:exact-matches :int]
    [:semantic-matches :int]
    [:resolved-at :string]]

   :evolutionary/canonical-uri-assigned
   [:map
    [:original-uri :string]
    [:canonical-uri :string]
    [:reason :string]
    [:assigned-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Graph Evolution Events
   ;; -------------------------------------------------------------------------

   :evolutionary/graph-merged
   [:map
    [:ontology-id :uuid]
    [:source-ids [:vector :uuid]]
    [:triples-before :int]
    [:triples-after :int]
    [:concepts-added :int]
    [:concepts-merged :int]
    [:merged-at :string]]

   :evolutionary/schema-extended
   [:map
    [:ontology-id :uuid]
    [:extensions [:vector [:map
                           [:uri :string]
                           [:element-type schema-element-type]
                           [:label :string]
                           [:source-id :uuid]]]]
    [:extended-at :string]]

   :evolutionary/ttl-snapshot-created
   [:map
    [:ontology-id :uuid]
    [:snapshot-id :uuid]
    [:format ttl-format]
    [:triple-count :int]
    [:checksum :string]
    [:created-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Build Orchestration Events
   ;; -------------------------------------------------------------------------

   :evolutionary/build-started
   [:map
    [:build-id :uuid]
    [:ontology-id :uuid]
    [:mode resolution-mode]
    [:source-count :int]
    [:config [:map-of :keyword :any]]
    [:started-at :string]]

   :evolutionary/build-completed
   [:map
    [:build-id :uuid]
    [:ontology-id :uuid]
    [:total-sources :int]
    [:total-concepts :int]
    [:total-triples :int]
    [:entities-resolved :int]
    [:duration-ms :int]
    [:completed-at :string]]

   :evolutionary/build-failed
   [:map
    [:build-id :uuid]
    [:ontology-id :uuid]
    [:error :string]
    [:failed-at-stage :string]
    [:failed-at :string]]

   ;; -------------------------------------------------------------------------
   ;; ColBERT Integration Events
   ;; -------------------------------------------------------------------------

   :evolutionary/colbert-indexed
   [:map
    [:ontology-id :uuid]
    [:index-id :uuid]
    [:index-name :string]
    [:colbert-fields [:vector :keyword]]
    [:document-count :int]
    [:indexed-at :string]]

   :evolutionary/colbert-index-updated
   [:map
    [:ontology-id :uuid]
    [:index-id :uuid]
    [:added-document-count {:optional true} :int]
    [:updated-at :string]]

   ;; -------------------------------------------------------------------------
   ;; Embedding Integration Events (for RRF hybrid search)
   ;; -------------------------------------------------------------------------

   :evolutionary/concepts-embedded
   [:map
    [:ontology-id :uuid]
    [:build-id :uuid]
    [:embedded-count :int]
    [:embedding-fields [:vector :keyword]]
    [:model-id :string]
    [:embedded-at :string]]

   :evolutionary/concepts-embedding-updated
   [:map
    [:ontology-id :uuid]
    [:new-embedded-count :int]
    [:total-embedded-count :int]
    [:embedding-fields [:vector :keyword]]
    [:updated-at :string]]})

;; =============================================================================
;; Evolutionary Ontology Builder - Command Schemas
;; =============================================================================

(defschemas evolutionary-commands
  {;; -------------------------------------------------------------------------
   ;; Source Management Commands
   ;; -------------------------------------------------------------------------

   :evolutionary/register-source
   [:map
    [:source-uri :string]
    [:source-type source-type]
    [:content {:optional true} :string]       ;; For text/json sources
    [:namespace {:optional true} :string]
    [:metadata {:optional true} [:map-of :keyword :any]]]

   :evolutionary/check-source-processed
   [:map
    [:source-uri {:optional true} :string]
    [:content {:optional true} :string]]      ;; Compute hash from content

   ;; -------------------------------------------------------------------------
   ;; Extraction Pipeline Commands
   ;; -------------------------------------------------------------------------

   :evolutionary/extract-from-csv
   [:map
    [:source-id :string]
    [:csv-path :string]
    [:config {:optional true} [:map
                               [:entity-column {:optional true} :string]
                               [:entity-type {:optional true} :string]
                               [:description-columns {:optional true} [:vector :string]]
                               [:relationship-columns {:optional true} [:vector :string]]]]]

   :evolutionary/extract-from-text
   [:map
    [:source-id :string]
    [:text :string]
    [:config {:optional true} [:map
                               [:domain {:optional true} :string]
                               [:extract-causal? {:optional true} :boolean]
                               [:max-depth {:optional true} :int]]]]

   :evolutionary/extract-from-sql
   [:map
    [:source-id :string]
    [:db-config [:map
                 [:host :string]
                 [:port :int]
                 [:database :string]
                 [:user {:optional true} :string]
                 [:password {:optional true} :string]]]
    [:config {:optional true} [:map
                               [:include-tables {:optional true} [:vector :string]]
                               [:exclude-tables {:optional true} [:vector :string]]
                               [:include-fks? {:optional true} :boolean]]]]

   ;; -------------------------------------------------------------------------
   ;; Entity Resolution Commands
   ;; -------------------------------------------------------------------------

   :evolutionary/resolve-entities-batch
   [:map
    [:ontology-id :uuid]
    [:source-ids [:vector :string]]
    [:config {:optional true} [:map
                               [:similarity-threshold {:optional true} :double]
                               [:emit-owl-sameAs? {:optional true} :boolean]
                               [:use-type-blocking? {:optional true} :boolean]]]]

   :evolutionary/resolve-entities-incremental
   [:map
    [:ontology-id :uuid]
    [:new-source-ids [:vector :string]]
    [:existing-labels [:vector :string]]       ;; Labels from existing ontology
    [:config {:optional true} [:map
                               [:similarity-threshold {:optional true} :double]
                               [:prefer-existing-uris? {:optional true} :boolean]]]]

   ;; -------------------------------------------------------------------------
   ;; Graph Evolution Commands
   ;; -------------------------------------------------------------------------

   :evolutionary/merge-sources
   [:map
    [:ontology-id :uuid]
    [:source-ids [:vector :string]]
    [:resolution-result [:map
                         [:canonical-map [:map-of :string :string]]
                         [:alignment-triples {:optional true} [:vector [:tuple :string :string :string]]]]]]

   :evolutionary/generate-ttl-snapshot
   [:map
    [:ontology-id :uuid]
    [:format {:optional true} ttl-format]
    [:include-metadata? {:optional true} :boolean]]

   ;; -------------------------------------------------------------------------
   ;; Orchestration Commands
   ;; -------------------------------------------------------------------------

   :evolutionary/build-from-sources
   [:map
    [:sources [:vector [:map
                        [:path {:optional true} :string]
                        [:content {:optional true} :string]
                        [:type source-type]]]]
    [:config {:optional true} [:map
                               [:base-uri {:optional true} :string]
                               [:similarity-threshold {:optional true} :double]
                               [:emit-owl-sameAs? {:optional true} :boolean]]]]

   :evolutionary/evolve
   [:map
    [:ontology-id :uuid]
    [:sources [:vector [:map
                        [:path {:optional true} :string]
                        [:content {:optional true} :string]
                        [:type source-type]]]]
    [:config {:optional true} [:map
                               [:prefer-existing-uris? {:optional true} :boolean]
                               [:similarity-threshold {:optional true} :double]]]]})

;; =============================================================================
;; Evolutionary Ontology Builder - Query Schemas
;; =============================================================================

(defschemas evolutionary-queries
  {:evolutionary/get-source
   [:map
    [:source-id :string]]

   :evolutionary/get-source-by-hash
   [:map
    [:content-hash :string]]

   :evolutionary/was-processed?
   [:map
    [:source-uri {:optional true} :string]
    [:content {:optional true} :string]]

   :evolutionary/all-sources
   [:map
    [:source-type {:optional true} source-type]]

   :evolutionary/get-all-concepts
   [:map
    [:ontology-id :uuid]]

   :evolutionary/get-concepts-by-source
   [:map
    [:source-id :string]]

   :evolutionary/get-concepts-by-type
   [:map
    [:entity-type :string]]

   :evolutionary/get-canonical-uri
   [:map
    [:uri :string]]

   :evolutionary/get-all-canonical-mappings
   [:map
    [:ontology-id {:optional true} :uuid]]

   :evolutionary/get-evolution-state
   [:map
    [:ontology-id :uuid]]

   :evolutionary/get-build-history
   [:map
    [:ontology-id :uuid]
    [:limit {:optional true} :int]]})

;; =============================================================================
;; Read Model Schemas
;; =============================================================================

(defschemas read-models
  {;; -------------------------------------------------------------------------
   ;; Evolutionary Ontology Builder Read Models
   ;; -------------------------------------------------------------------------

   :evolutionary/source-registry
   [:map-of :string                            ;; source-id -> source-entry
    [:map
     [:source-id :string]
     [:source-uri :string]
     [:source-type source-type]
     [:content-hash :string]
     [:file-size :int]
     [:namespace :string]
     [:metadata {:optional true} [:map-of :keyword :any]]
     [:concepts-extracted {:optional true} :int]
     [:triples-generated {:optional true} :int]
     [:entities-resolved {:optional true} :int]
     [:registered-at :string]]]

   :evolutionary/content-hash-index
   [:map-of :string :string]                   ;; content-hash -> source-id

   :evolutionary/concept-graph
   [:map-of :string                            ;; uri -> concept-with-relationships
    [:map
     [:uri :string]
     [:label :string]
     [:definition {:optional true} :string]
     [:entity-type :string]
     [:source-id :string]
     [:alt-labels {:optional true} [:vector :string]]
     [:relationships [:vector [:map
                               [:predicate :string]
                               [:object :string]
                               [:confidence {:optional true} :double]]]]]]

   :evolutionary/canonical-uri-map
   [:map-of :string :string]                   ;; original-uri -> canonical-uri

   :evolutionary/evolution-state
   [:map
    [:ontology-id :uuid]
    [:total-sources :int]
    [:total-concepts :int]
    [:total-triples :int]
    [:schema-extensions [:vector [:map
                                  [:uri :string]
                                  [:element-type schema-element-type]
                                  [:source-id :string]]]]
    [:last-evolved-at {:optional true} :string]]

   ;; -------------------------------------------------------------------------
   ;; Original ORC Optimization Read Models
   ;; -------------------------------------------------------------------------

   :ontology/concepts
   [:map-of :string                       ;; URI -> Concept
    [:map
     [:uri :string]
     [:id :uuid]
     [:label :string]
     [:description :string]
     [:scope ontology-scope]
     [:broader [:set :string]]
     [:narrower [:set :string]]
     [:related [:set :string]]
     [:indicators [:vector :string]]
     [:created-at :string]]]

   :ontology/tree-profile
   [:map
    [:tree-id :uuid]
    [:strengths [:vector [:map
                          [:pattern :string]
                          [:confidence :double]
                          [:evidence-count :int]
                          [:avg-score :double]
                          [:recorded-at {:optional true} :string]
                          ;; Domain-agnostic fields
                          [:context-conditions {:optional true} [:map-of :keyword :any]]
                          [:action-taken {:optional true} [:map
                                                           [:type {:optional true} :string]
                                                           [:target {:optional true} :any]
                                                           [:reason {:optional true} :string]]]
                          [:domain-type {:optional true} :string]
                          [:expected-outcome {:optional true} :string]]]]
    [:weaknesses [:vector [:map
                           [:failure :string]
                           [:subtype {:optional true} :string]
                           [:frequency :double]
                           [:severity severity-level]
                           [:triggers [:vector :string]]
                           [:recorded-at {:optional true} :string]
                           ;; Domain-agnostic fields
                           [:failure-context {:optional true} [:map-of :keyword :any]]
                           [:attempted-action {:optional true} [:map
                                                                [:type {:optional true} :string]
                                                                [:target {:optional true} :any]
                                                                [:reason {:optional true} :string]]]
                           [:domain-type {:optional true} :string]]]]
    [:solves [:vector [:map
                       [:problem-uri :string]
                       [:success-rate :double]
                       [:execution-count :int]]]]
    [:domain-knowledge [:vector [:map
                                  [:id :uuid]
                                  [:description :string]
                                  [:impact-score {:optional true} :double]]]]]

   :ontology/learned-rules
   [:map-of :uuid                          ;; tree-id -> rules vector
    [:vector [:map
              [:rule-id :uuid]
              [:condition [:map-of :keyword :any]]
              [:action [:map-of :keyword :any]]
              [:confidence :double]
              [:success-rate :double]
              [:evidence-episodes [:vector :uuid]]
              [:problem-type :string]
              [:domain-type {:optional true} :string]
              [:extracted-at :string]]]]

   :ontology/node-experiences
   [:map-of node-type                     ;; node-type -> experiences
    [:map-of pattern-type                 ;; pattern-type -> by-effectiveness
     [:map
      [:effective [:vector [:map
                            [:pattern :string]
                            [:metrics [:map-of :keyword :double]]
                            [:evidence-count :int]]]]
      [:ineffective [:vector [:map
                              [:pattern :string]
                              [:metrics [:map-of :keyword :double]]
                              [:evidence-count :int]]]]]]]

   ;; Embedding Read Models (Phase 4)

   :ontology/concept-embeddings
   [:map-of :string                       ;; URI -> embedding data
    [:map
     [:uri :string]
     [:embedding [:vector :double]]
     [:text-embedded :string]
     [:model-id :string]
     [:embedded-at :string]]]

   :ontology/embedding-config
   [:map-of ontology-scope                ;; scope -> model config
    [:map
     [:model-id :string]
     [:dimensions :int]
     [:configured-at :string]]]

   ;; -------------------------------------------------------------------------
   ;; Apartment Search Read Models
   ;; -------------------------------------------------------------------------

   :apartment/listings
   [:map
    [:by-id [:map-of :uuid                ;; listing-id -> listing
             [:map
              [:listing-id :uuid]
              [:source-site :string]
              [:source-url :string]
              [:extracted-at :string]
              [:title :string]
              [:price :string]
              [:price-numeric {:optional true} :double]
              [:bedrooms {:optional true} :string]
              [:bathrooms {:optional true} :string]
              [:sqft {:optional true} :string]
              [:address :string]
              [:neighborhood {:optional true} :string]
              [:city :string]
              [:state :string]
              [:amenities [:vector :string]]
              [:lat {:optional true} :double]
              [:lng {:optional true} :double]]]]
    [:by-url [:map-of :string :uuid]]     ;; source-url -> listing-id
    [:by-city [:map-of :string [:set :uuid]]]  ;; city -> listing-ids
    [:by-site [:map-of :string [:set :uuid]]]  ;; source-site -> listing-ids
    [:price-history [:map-of :uuid        ;; listing-id -> price history
                     [:vector [:map
                               [:price :string]
                               [:price-numeric {:optional true} :double]
                               [:date :string]]]]]]

   :apartment/searches
   [:map
    [:by-id [:map-of :uuid                ;; search-id -> search record
             [:map
              [:search-id :uuid]
              [:location :string]
              [:max-rent {:optional true} :int]
              [:filters {:optional true} [:map-of :keyword :any]]
              [:listing-count :int]
              [:source-sites [:vector :string]]
              [:listing-ids [:vector :uuid]]
              [:searched-at :string]]]]
    [:by-location [:map-of :string [:vector :uuid]]]]

   ;; -------------------------------------------------------------------------
   ;; Site Registry Read Models (Multi-Site Discovery)
   ;; -------------------------------------------------------------------------

   :apartment/site-registry
   [:map
    [:by-domain [:map-of :string          ;; domain -> site
                 [:map
                  [:site-id :uuid]
                  [:domain :string]
                  [:display-name :string]
                  [:category [:enum :corporate :peer-to-peer :aggregator :local]]
                  [:discovered-via [:enum :manual :web-search :referral]]
                  [:url-pattern {:optional true} :string]
                  [:requires-headed {:optional true} :boolean]
                  [:known-challenges {:optional true} [:vector :string]]
                  [:notes {:optional true} :string]
                  [:trust-score :double]
                  [:extraction-count :int]
                  [:last-success-at {:optional true} :string]
                  [:last-failure-at {:optional true} :string]
                  [:registered-at :string]]]]
    [:by-trust [:vector :string]]         ;; domains sorted by trust score
    [:patterns [:map-of :string           ;; domain -> patterns
                [:vector [:map
                          [:pattern-type [:enum :navigation :search :extraction :bot-bypass :pagination]]
                          [:pattern-data [:map-of :keyword :any]]
                          [:confidence :double]
                          [:learned-at :string]]]]]]]})
