# XLSX skill (Apache POI XSSF via Clojure SCI)

You can build and read .xlsx workbooks via the `xlsx/` namespace.

## Writing a workbook

`xlsx/write-workbook` takes an output path and a sheets-spec — a vector of
sheet maps. Each sheet has an optional :columns spec (which becomes the
header row with auto-sized columns) and a vector of row vectors:

    (xlsx/write-workbook
      "/sandbox/output/report.xlsx"
      [{:name    "Summary"
        :columns [{:header "Vendor"      :width 24}
                  {:header "Invoice #"   :width 14}
                  {:header "Date"        :width 12}
                  {:header "Total"       :width 12}]
        :rows    [["Acme Inc."     "INV-1001" "2025-04-01" 1234.56]
                  ["Globaltech Co" "GT-10587" "2025-04-12"  987.65]]}
       {:name    "Acme Inc."
        :columns [{:header "Description" :width 40}
                  {:header "Quantity"    :width 10}
                  {:header "Unit Price"  :width 12}
                  {:header "Amount"      :width 12}]
        :rows    [["Widget A" 10 12.34 123.40]
                  ["Service B"  5 50.00 250.00]]}])
    ;; => "/sandbox/output/report.xlsx"

Cells accept strings, numbers, booleans, or nil (blank). Auto-sizing is on
by default; turn off per sheet with `:auto-size? false`.

## Reading a workbook

    (xlsx/list-sheets "/sandbox/input/data.xlsx")
    ;; => ["Summary" "Acme Inc." "Globaltech Co"]

    (xlsx/read-sheet "/sandbox/input/data.xlsx" "Summary")
    ;; => [["Vendor" "Invoice #" "Date" "Total"]
    ;;     ["Acme Inc." "INV-1001" "2025-04-01" 1234.56]
    ;;     ...]

If row 0 is a header row, get a vector of maps instead:

    (xlsx/read-sheet-as-maps "/sandbox/input/data.xlsx" "Summary")
    ;; => [{:Vendor "Acme Inc." :Invoice\# "INV-1001" ...}
    ;;     ...]

## Patterns

- Build the per-entity sheets first, then a Summary sheet referencing them
  by name.
- Keep numeric columns numeric (not strings) so totals/sorts work in Excel.
- For nested data extracted by a sub-LM, flatten before writing — POI cells
  hold scalars, not maps.
