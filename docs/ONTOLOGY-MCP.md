# Ontology MCP Server - External Integration Guide

This guide covers how to integrate the ObneyAI Ontology MCP Server with external agents, Claude Code, and other MCP-compatible clients.

The MCP tool set documented here is a read/exploration surface and does not expose the
custom graph lifecycle. JVM consumers that need to create an empty ontology, add or
update concepts, or create relationships should use the tenant-scoped functions in
`ai.obney.orc.ontology.interface`. See
[ONTOLOGY.md — Ontology Lifecycle](ONTOLOGY.md#ontology-lifecycle). Do not load internal
command namespaces as an external mutation API.

## Quick Start

### Starting the Server

```bash
# Option 1: From command line
clojure -M:dev:ontology-server

# Server starts on http://localhost:8765
```

```clojure
;; Option 2: From REPL
(require '[ai.obney.orc.ontology-mcp-server.interface :as mcp])

;; Start with default settings
(def server (mcp/start-server ctx))

;; Start with custom port
(def server (mcp/start-server ctx {:port 9000}))

;; Stop server
(mcp/stop-server server)
```

### Context Requirements

The server accepts a context map with:

```clojure
{:event-store    ; Grain event store (optional - enables memory tools)
 }
```

Without an event store, static ontology tools still work (search, get-concept, etc.).

---

## Available Tools (13 total)

### Knowledge Tools (6)

#### `ontology/search-concepts`
Semantic search over ontology concepts.

**Input Schema:**
```json
{
  "query": "hallucination",      // Required: search text
  "scope": "failure",            // Optional: "failure", "success", "problem", "all"
  "limit": 10                    // Optional: max results (default 10)
}
```

**Example Response:**
```json
{
  "concepts": [
    {
      "uri": "failure:Hallucination",
      "label": "Hallucination",
      "description": "Generated claims not present in sources",
      "scope": "failure",
      "broader": ["failure:Grounding"],
      "indicators": ["claim not found", "made up", "invented"]
    }
  ],
  "count": 3
}
```

#### `ontology/get-concept`
Get a specific concept by URI.

**Input Schema:**
```json
{
  "uri": "failure:Hallucination"  // Required: concept URI
}
```

**Example Response:**
```json
{
  "concept": {
    "uri": "failure:Hallucination",
    "label": "Hallucination",
    "description": "Generated claims not present in sources - invented facts or relationships",
    "scope": "failure",
    "broader": ["failure:Grounding"],
    "narrower": [],
    "indicators": ["claim not found", "made up", "invented", "no evidence", "fabricated"]
  }
}
```

#### `ontology/get-hierarchy`
Get concept with broader (parent) and narrower (child) concepts.

**Input Schema:**
```json
{
  "uri": "failure:Hallucination",  // Required: concept URI
  "depth": 1                       // Optional: hierarchy depth (default 1)
}
```

**Example Response:**
```json
{
  "concept": { "uri": "failure:Hallucination", "label": "Hallucination" },
  "broader": [{ "uri": "failure:Grounding", "label": "Grounding Failure" }],
  "narrower": [],
  "depth": 1
}
```

#### `ontology/get-context`
Get formatted ontology context for LLM prompts.

**Input Schema:**
```json
{
  "problem-type": "Classification",  // Required: problem type
  "domain": "sales",                 // Optional: domain context
  "include": ["patterns", "failures"], // Optional: sections to include
  "max-items": 5                     // Optional: max items per section
}
```

**Example Response:**
```json
{
  "context": "## Classification Patterns\n\n### Effective Patterns\n- ExplicitSchema: Use explicit output structure...",
  "problem-type": "Classification"
}
```

#### `ontology/list-concepts`
List all concepts in a scope.

**Input Schema:**
```json
{
  "scope": "failure"  // Required: "failure", "success", or "problem"
}
```

**Example Response:**
```json
{
  "concepts": [
    { "uri": "failure:Hallucination", "label": "Hallucination" },
    { "uri": "failure:Grounding", "label": "Grounding Failure" }
  ],
  "scope": "failure",
  "count": 22
}
```

#### `ontology/hybrid-search`
Hybrid search using RRF fusion of graph traversal and embeddings.

**Input Schema:**
```json
{
  "query": "validation patterns",     // Required: search text
  "seeds": ["success:ValidationLoop"], // Optional: seed URIs for graph traversal
  "scope": "success",                  // Optional: limit to scope
  "limit": 10                          // Optional: max results
}
```

---

### Memory Tools (3)

These tools require an event store with learned data.

#### `ontology/get-tree-profile`
Get a tree's learned profile with strengths and weaknesses.

**Input Schema:**
```json
{
  "tree-id": "550e8400-e29b-41d4-a716-446655440000"  // Required: tree UUID
}
```

**Example Response:**
```json
{
  "profile": {
    "tree-id": "550e8400-e29b-41d4-a716-446655440000",
    "strengths": [
      { "pattern": "success:ExplicitSchema", "confidence": 0.95, "avg-score": 0.92 }
    ],
    "weaknesses": [
      { "failure": "failure:Hallucination", "frequency": 0.05, "severity": "critical" }
    ],
    "solves": [
      { "problem-uri": "problem:Classification", "success-rate": 0.94 }
    ]
  }
}
```

#### `ontology/get-node-patterns`
Get learned patterns for a node type.

**Input Schema:**
```json
{
  "node-type": "llm"  // Required: "llm", "code", "repl-researcher", "map-each"
}
```

**Example Response:**
```json
{
  "patterns": {
    "structural": {
      "effective": [
        { "pattern": "Use explicit :map schemas", "metrics": { "success-rate": 0.92 } }
      ],
      "ineffective": [
        { "pattern": "Using :any schema", "metrics": { "success-rate": 0.29 } }
      ]
    }
  },
  "node-type": "llm"
}
```

#### `ontology/find-trees-for-problem`
Find successful trees for a problem type.

**Input Schema:**
```json
{
  "problem-type": "Classification",  // Required
  "min-success-rate": 0.7,           // Optional: minimum success rate (default 0.7)
  "limit": 5                         // Optional: max trees (default 5)
}
```

---

### Execution Tools (4)

#### `sheets/list-available`
List published behavior tree sheets.

**Input Schema:**
```json
{}  // No parameters required
```

**Example Response:**
```json
{
  "sheets": [
    { "sheet-id": "...", "name": "Lead Qualifier", "published-version": 3 }
  ],
  "count": 5
}
```

#### `sheets/get-description`
Get sheet metadata and input/output schema.

**Input Schema:**
```json
{
  "sheet-id": "550e8400-e29b-41d4-a716-446655440000"  // Required
}
```

#### `sheets/execute`
Execute a sheet with inputs.

**Input Schema:**
```json
{
  "sheet-id": "550e8400-e29b-41d4-a716-446655440000",  // Required
  "inputs": { "lead-data": { "name": "Acme Corp" } }   // Required: input values
}
```

#### `sheets/find-for-problem`
Find sheets that can solve a specific problem type, ranked by success rate.

**Input Schema:**
```json
{
  "problem-type": "Classification",  // Required: problem type to find sheets for
  "limit": 5                         // Optional: max sheets (default 5)
}
```

**Example Response:**
```json
{
  "sheets": [
    { "sheet-id": "...", "name": "Lead Qualifier", "success-rate": 0.94 }
  ],
  "problem-type": "Classification",
  "count": 2
}
```

---

## Claude Code Integration

### Adding to .mcp.json

```json
{
  "mcpServers": {
    "ontology": {
      "command": "curl",
      "args": ["-X", "POST", "http://localhost:8765/tools/call",
               "-H", "Content-Type: application/json",
               "-d", "@-"]
    }
  }
}
```

### Example Usage in Claude Code

```
You: Search for hallucination patterns in the ontology

Claude: [Uses ontology/search-concepts with query "hallucination"]
Found 3 concepts related to hallucination:
- failure:Hallucination - Generated claims not present in sources
- failure:Hallucination.FactHallucination - Incorrect factual claims
- failure:Hallucination.SourceHallucination - Made up citations
```

---

## HTTP API Reference

### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/tools/list` | POST | List available tools |
| `/tools/call` | POST | Call a tool |
| `/health` | GET | Health check |

### JSON-RPC Format

All requests use JSON-RPC 2.0:

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "ontology/search-concepts",
    "arguments": { "query": "hallucination" }
  },
  "id": 1
}
```

### Example: curl Commands

```bash
# List available tools
curl -X POST http://localhost:8765/tools/list \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'

# Search concepts
curl -X POST http://localhost:8765/tools/call \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"ontology/search-concepts","arguments":{"query":"hallucination"}},"id":2}'

# Get concept details
curl -X POST http://localhost:8765/tools/call \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"ontology/get-concept","arguments":{"uri":"failure:Hallucination"}},"id":3}'

# List all failure concepts
curl -X POST http://localhost:8765/tools/call \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"ontology/list-concepts","arguments":{"scope":"failure"}},"id":4}'
```

---

## Error Handling

### Error Codes

| Code | Meaning |
|------|---------|
| -32700 | Parse error (invalid JSON) |
| -32600 | Invalid request (missing required fields) |
| -32601 | Method not found |
| -32602 | Invalid params |
| -32603 | Internal error |

### Error Response Format

```json
{
  "jsonrpc": "2.0",
  "error": {
    "code": -32602,
    "message": "Invalid params",
    "data": { "tool": "ontology/get-concept", "issue": "Missing uri parameter" }
  },
  "id": 1
}
```

---

## Best Practices

### When to Use Which Tool

| Goal | Tool |
|------|------|
| Understand a failure type | `ontology/get-concept` |
| Find related concepts | `ontology/get-hierarchy` |
| Search by keyword | `ontology/search-concepts` |
| Build LLM context | `ontology/get-context` |
| Find proven patterns | `ontology/get-node-patterns` |
| Select a workflow | `ontology/find-trees-for-problem` |

### Workflow: Building Context for an LLM Task

```
1. ontology/get-context {"problem-type": "Classification"}
   → Get relevant patterns and failure modes

2. ontology/get-node-patterns {"node-type": "llm"}
   → Get specific patterns for LLM nodes

3. ontology/find-trees-for-problem {"problem-type": "Classification"}
   → Find successful reference implementations
```

### Workflow: Investigating a Failure

```
1. ontology/search-concepts {"query": "error description here"}
   → Find matching failure concepts

2. ontology/get-hierarchy {"uri": "failure:MatchedConcept"}
   → Understand failure taxonomy

3. ontology/get-concept {"uri": "failure:MatchedConcept"}
   → Get full details including indicators and mitigation
```

---

## Static Ontology Reference

The server includes a pre-built static ontology with:

| Scope | Count | Examples |
|-------|-------|----------|
| Failures | 22 | Hallucination, Grounding, Truncation, InstructionFollowing |
| Successes | 18 | ExplicitSchema, ValidationLoop, ChainOfThought, FewShotExamples |
| Problems | 21 | Classification, Summarization, DataExtraction, Generation |

This static ontology is always available, even without an event store.
