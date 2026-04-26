# DOCX skill (Apache POI XWPF via Clojure SCI)

You can write .docx documents via the `docx/` namespace using a small
Hiccup-like element vector.

## Writing structured docs

`docx/write-docx` takes an output path and a vector of element forms:

    (docx/write-docx
      "/sandbox/output/report.docx"
      [[:h1 "Document Analysis Report"]
       [:p  "Generated 2025-04-25."]
       [:h2 "Key Dates"]
       [:bullets ["Submission deadline: 2025-05-01 14:00 EST"
                  "Effective date: 2025-06-01"]]
       [:h2 "Key Entities"]
       [:table {:headers ["Name" "Role" "Contact"]
                :rows    [["John Doe"     "Project lead" "john@example.com"]
                          ["Acme Corp."   "Vendor"       ""]]}]
       [:h2 "Analysis"]
       [:p "Body prose here…"]])
    ;; => "/sandbox/output/report.docx"

Supported tags:
- `[:h1 text]` … `[:h6 text]` — heading levels
- `[:p text]` — paragraph
- `[:bullets [item …]]` — bullet list
- `[:table {:headers [...] :rows [[…] …]}]` — table
- `[:br]` — empty paragraph (vertical spacing)

## Markdown shortcut

If your output is already markdown (e.g., a report from an LLM step),
convert it to a docx in one shot:

    (docx/write-markdown-as-docx
      "/sandbox/output/report.docx"
      "# Title\n\nIntro paragraph.\n\n## Section\n\n- bullet 1\n- bullet 2")

The markdown parser supports `# h1`, `## h2`, `### h3`, blank-line-separated
paragraphs, and `-` bullet lines. For richer formatting (bold, tables) use
the element-vector form directly.
