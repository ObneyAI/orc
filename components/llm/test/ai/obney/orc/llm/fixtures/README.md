# Provider observation replay fixtures

These decoded response bodies were copied from the client repair's preserved
response fixtures 39 and 41. They originate in the authored-tree experiment
receipts, include no request headers or credentials, and represent previously
recorded model responses rather than new calls. The unique component-local
resource names keep ORC tests independent of dependency test classpaths.

Receipt 39 covers readable reasoning, summaries and encrypted records. Receipt
41 covers opaque reasoning without exposed text. Tests intercept final HTTP with
dummy credentials and preserve the distinction between recorded responses and
new execution identities.
