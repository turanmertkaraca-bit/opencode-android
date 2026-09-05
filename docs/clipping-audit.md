# Clipping audit — the repeatable checklist (P27)

The field: "sometimes some ui elements clip just a little bit, you see it
once you focus." Every fix below came from a real field screenshot or a
code-path that could produce one. Run this list on EVERY new screen, and
re-run it after any layout change.

## How to audit (5 minutes, no tooling)

1. Font scale: set device font size to SMALLEST and LARGEST (Settings →
   Display → Font size). Robolectric equivalent: the P27UiTest deck pin.
2. Window: phone portrait, then DeX / split-screen at ~600dp (the wide
   layout kicks in — content insets change).
3. Strings: a 200-char file path in the live tree, a 120-char project
   name on the deck, a model name + provider path + price in the picker,
   a cost like $123.4567 in the Σ pill.
4. Look ONLY at: card edges, letterspaced labels, rows with trailing
   meta, bottom-most rows, 1dp borders.

## The known bug classes (all fixed in P27 — do not reintroduce)

| Class | Where it bit | The rule |
|---|---|---|
| Fixed-height cards clip growing content | DeckView: footer row cut mid-line when the path wrapped to 2 lines | Cards measure NATURALLY first; the shared height grows to the tallest child, clamped to the viewport (DeckView.onMeasure) |
| Floating overlays anchored to decor with fixed margins | "↓ latest" pill floated ON TOP of the composer text when the input grew | Overlay pills live INSIDE the region they belong to (the transcript FrameLayout), never on the decor with a magic margin |
| Letterspaced ALL-CAPS labels kiss the next element | LIVE / PROJECT / section labels — trailing advance | Matching end padding (~0.4em) on every letterspaced label |
| Single-line ellipsized text with trailing meta collide | Model rows: price text ran into the ellipsized provider path | The meta goes in its OWN column (weight-separated), never appended to the ellipsized string |
| Exact-match heights vs 1dp borders | dividers/badges at fractional dp on some densities | Borders are stroke=1 on rounded drawables (never a 1px-tall view inside a wrap_content parent that rounds down) |
| Bottom row clipped by scroll edge | chat list bottom row under the composer | list bottom padding ≥ 14dp; ScrollView fillViewport on |
| Toasts covering interactive UI | 3-line model-picker toast covered the sheet | Long explanations go to the in-sheet hint line; toasts stay one short line |

## Regression pins

- `P27UiTest.deckCardHeight_growsToFitTallestContent` — the deck fix.
- `P27Test.middleEllipsize_keepsFilenameTail` — long paths degrade to a
  readable tail, never a clipped string.
