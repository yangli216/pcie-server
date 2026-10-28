# Design QA — 门诊病历质控关联视图

- Source visual truth paths:
  - `/var/folders/tq/31b1_m3x7934pqwkcfc3qhcc0000gp/T/codex-clipboard-43e4252b-efa9-4b16-8914-4b96c6d7318c.png`
  - `/var/folders/tq/31b1_m3x7934pqwkcfc3qhcc0000gp/T/codex-clipboard-2310b642-6bc1-40da-bc06-3fb4904b9dee.png`
- Implementation screenshot path: `/tmp/emr-qc-personal-history-linked-final.png`
- Side-by-side comparison path: `/tmp/emr-qc-linkage-comparison-final.png`
- Implementation viewport: `1213 × 1184` CSS px，device scale factor `1`
- State: 本月病历详情；PHIS 原始排版；“个人史”问题已选中并定位。

## Full-view comparison evidence

The first annotation asks to remove the duplicate “当前关联” banner because the selected paragraph and issue card already express the relationship. The final implementation contains no banner above the PHIS document. The second annotation asks to retain the institution/document title and the empty `主诉` / `现病史` rows after selecting an issue. The final implementation keeps the complete sanitized PHIS layout and highlights only the exact related paragraph.

## Focused region comparison evidence

- The institution name, document title, patient line, empty `主诉：` row, empty `现病史：` row, and signature remain visible while a finding is selected.
- Selecting the “个人史” issue highlights only the matching PHIS paragraph and marks the corresponding issue card as linked.
- No duplicate relationship banner is rendered above the document.
- At the tested narrower workspace width, the two-column container has `scrollWidth === clientWidth` and `scrollLeft === 0`; neither panel is horizontally cropped.

## Findings

- P0/P1/P2: none after the final pass.
- P3: both source visuals are cropped at different dimensions, so exact whole-page pixel alignment cannot be judged; the annotated regions and selected interaction state are visibly matched.
- Console: no fresh warning or error after reload and repeating the primary interaction.
- Accessibility: issue cards remain keyboard reachable; the original focus-visible behavior is retained.

## Comparison history

1. P1 — a duplicate relationship banner repeated information already visible in the highlighted paragraph and selected issue card. Fix: remove the banner and its obsolete computed state/styles.
2. P1 — issue selection automatically switched to the normalized chapter view, dropping the PHIS institution/document title and empty sections. Fix: keep the safe PHIS HTML preview whenever available; use the structured view only as a no-HTML fallback or explicit user choice.
3. P2 — fixed minimum column widths caused horizontal drift and left-panel clipping in narrower workspaces. Fix: use `minmax(0, …)` columns and `min-width: 0` panels.
4. P2 — the page emitted an unknown `el-progress` component warning. Fix: register Element UI `Progress`; a fresh interaction run produced no console warning or error.

## Primary interactions tested

- Open a current-month record from the list.
- Keep PHIS original layout active.
- Select missing-section findings and verify the institution title, document title, patient line, `主诉`, and `现病史` remain visible.
- Select the “个人史” finding and verify only its matching paragraph is highlighted.
- Verify the relationship banner is absent and the selected issue card remains visibly linked.
- Verify the two-column detail view does not horizontally overflow in the tested workspace.

final result: passed
