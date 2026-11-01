# Workbook analysis

Status: **verified generated template**.

`Rehab_Response_Tracker.xlsx` was created from the written specification and is committed at the repository root. It contains no personal or sample health records.

## Daily Rehab Log

- Used range: `A1:P121`.
- Excel Table: `DailyRehabLogTable`.
- Header row: exact specification headers in `A1:P1`.
- Date rows: 120 typed dates from `2026-11-01` through `2027-02-28`.
- Formula column: `I2:I121` uses `=IF(OR(F2="",H2=""),"",H2-F2)` with row-relative references. The formula remains blank unless both symptom scores exist and preserves a recorded zero.
- Editable cells: columns B–H and J–P, shown with pale yellow input fill.
- Validation: session type, duration, RPE, symptom scores, response category, sleep hours, next-day decision, and follow-up completion.
- Conditional formatting: descriptive response/follow-up colors only. No medical threshold is inferred.
- Frozen header row and date column.

## How to Use

The sheet explains daily entry, same-row follow-up, scales, missing values, weekly review, privacy, workbook-copy precautions, and the SharePoint permission gate.

## Weekly Review

- Used range: `A2:I26`.
- Eighteen Monday–Sunday periods cover every date in the daily log.
- Formula-driven session count, total duration, average RPE, paired average 24-hour symptom change, completed follow-ups, and recorded flare count.
- Missing RPE and symptom pairs remain blank rather than becoming zero.
- Column I is an editable weekly-notes field.

## Verification

- Exported workbook re-imported with all three sheets, the full date range, Excel Table, and first/last formulas intact.
- Formula-error scan returned no Excel errors.
- A disposable boundary test confirmed that symptoms-before `0` remains numeric and that one sample session produced the correct weekly duration, RPE, symptom delta, follow-up, and response totals. Test values were removed before export.
- Every worksheet was rendered and visually reviewed for readable labels, unclipped content, input cues, and formula output.

The generated file is the approved local integration target. Continue to use a copy for the future Graph feasibility probe and obtain explicit approval before targeting any live federation workbook.
