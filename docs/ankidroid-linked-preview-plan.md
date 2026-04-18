# AnkiDroid Linked Preview Navigation Plan

## Goal

Add template-driven linked-card navigation to `myAnkidroid` without disturbing the live review flow.

The user should be able to:

- study a normal card in the reviewer
- select text on a configured card
- tap a template-provided action
- open a linked card in preview-only mode
- recursively navigate to other linked cards
- backtrack through the navigation chain
- return to the original review card unchanged

This should work only for templates that opt in through hidden configuration. Normal decks should behave exactly as they do today.

## Core Design Decisions

- Keep the scheduler-backed reviewer untouched.
- Open linked cards in preview-only mode, not grading mode.
- Maintain a native navigation trail/stack inside AnkiDroid.
- Let templates opt in through hidden directives/config.
- Reuse ideas from `Outranki`, but implement the navigation natively in AnkiDroid instead of in a separate app.

## Existing Code Anchors

Reviewer and JS bridge:

- `AnkiDroid/src/main/java/com/ichi2/anki/AbstractFlashcardViewer.kt`
- `AnkiDroid/src/main/java/com/ichi2/anki/AnkiDroidJsAPI.kt`

Preview infrastructure:

- `AnkiDroid/src/main/java/com/ichi2/anki/previewer/PreviewerFragment.kt`
- `AnkiDroid/src/main/java/com/ichi2/anki/previewer/PreviewerViewModel.kt`
- `AnkiDroid/src/main/java/com/ichi2/anki/previewer/CardViewerActivity.kt`

Browser / ID transport:

- `AnkiDroid/src/main/java/com/ichi2/anki/browser/CardBrowserViewModel.kt`

Template prototype:

- `H:\git\personal\ankiMandarin\AnkiNoteTypes\MandarinMemPalaceScene\Card1Back.html`
- `H:\git\personal\ankiMandarin\AnkiNoteTypes\MandarinMemPalaceScene\Card2Back.html`

Outranki references:

- `H:\git\personal\outranki\app\src\main\java\com\damarquez\outranki\ui\anki\NotePreviewViewModel.kt`
- `H:\git\personal\outranki\app\src\main\java\com\damarquez\outranki\ui\anki\NotePreviewWebViewScreen.kt`
- `H:\git\personal\outranki\app\src\main\java\com\damarquez\outranki\ui\study\StudyViewModel.kt`

## What Outranki Proves

Outranki proves:

- recursive preview-chain navigation is workable
- a stack-based backtrack model is workable
- template JS can trigger search and open linked content
- a separate app can hand control back to AnkiDroid reviewer

Outranki does **not** prove:

- that AnkiDroid already supports opening an arbitrary searched card directly into scheduler-backed grading flow

For `myAnkidroid`, this means:

- linked preview navigation is feasible
- backtracking is feasible
- native implementation inside AnkiDroid should be smoother than the external-app approach

## Product Boundaries

### In scope

- reviewer -> linked preview
- linked preview -> linked preview
- native result picker for multiple matches
- native backtracking to previous linked preview
- return to the original reviewer card
- hidden template configuration for enabling the feature

### Out of scope for the first milestone

- grading linked cards directly from the linked preview chain
- AI integration
- Azure TTS integration
- editor autofill features
- a large general-purpose template command language

## Template Configuration Strategy

The feature should be opt-in and template-driven.

### Preferred approach

Use explicit hidden config embedded in the rendered HTML, instead of scraping visible text or relying on ad hoc heuristics.

Good candidates:

- a hidden element with `data-*` attributes
- a hidden JSON blob in a `<script type="application/json">`

### Minimal v1 configuration

Support only:

- config version
- target deck
- target field
- simple transforms: trim, prefix, suffix
- open behavior: `single-or-picker`

### Example: hidden element

```html
<div
  class="ankidroid-nav-config"
  hidden
  data-version="1"
  data-mode="selection-search"
  data-single-char-deck="MandarinMP::Characters"
  data-multi-char-deck="MandarinMP::Vocabulary"
  data-field="Front"
  data-open="single-or-picker">
</div>
```

### Example: JSON

```html
<script type="application/json" id="ankidroid-nav-config">
{
  "version": 1,
  "mode": "selection-search",
  "rules": [
    {
      "when": "single-char",
      "deck": "MandarinMP::Characters",
      "field": "Front"
    },
    {
      "when": "default",
      "deck": "MandarinMP::Vocabulary",
      "field": "Front"
    }
  ],
  "open": "single-or-picker"
}
</script>
```

## Native Architecture

### 1. New JS bridge method

Add a native JS API method to replace the search-only browser handoff.

Examples:

- `ankiNavigateCard(query)`
- `ankiNavigateCard(configJson)`
- `ankiNavigateCardWithCallback(configJson)`

Responsibilities:

- accept the request from template JS
- parse input
- resolve matching cards/notes natively
- open preview directly for one result
- show picker for multiple results
- show message for zero results

### 2. Native match resolution

Add a native resolver that:

- builds the search query from template config and selected text
- runs the search
- resolves note/card IDs
- decides which card to preview

Important design rule:

- preview should be card-based, not just note-based, because AnkiDroid preview infrastructure already centers on card IDs

V1 simplification:

- if a note matches, open its first card unless later config says otherwise

### 3. Native linked preview session

Create a native navigation session for linked preview.

Suggested model:

- `NavigationRoot`
  - original reviewer card ID
  - original deck ID
  - maybe original side state
- `NavigationSnapshot`
  - current linked card ID
  - scroll position
  - optional richer UI state later
- `NavigationSession`
  - root
  - stack of snapshots

Behavior:

- each linked open pushes the current linked card onto the stack
- back pops to the previous linked card
- when the stack is empty, return to the original reviewer card

### 4. Result picker

For multiple matches:

- show a native dialog or bottom sheet
- list preview text and deck name
- opening an item starts linked preview

## Incremental Delivery Plan

The implementation should be incremental so the highest-risk seams are tested early.

### Milestone 1: Single-result navigation from reviewer to preview

Goal:

- prove reviewer WebView -> native JS bridge -> search -> preview launch

Scope:

- add one native JS API method
- pass a plain search query from the template
- if exactly one result exists, open preview-only mode
- if zero or many, only show a message for now

Success criteria:

- selecting text on a configured reviewer card opens the linked card in preview
- backing out returns to the original reviewer card without changing review state

Likely hurdles:

- resolving a previewable card ID from search
- launching preview from the reviewer cleanly
- preserving reviewer state

### Milestone 2: Multiple-result picker

Goal:

- handle ambiguous searches cleanly

Scope:

- if many results are found, show a native picker
- picking a result opens preview

Success criteria:

- user can select among matches without leaving reviewer context

Likely hurdles:

- deciding what text to show in the picker
- note vs card ambiguity

### Milestone 3: Native preview chain

Goal:

- recursive linked preview navigation with backtracking

Scope:

- add native stack/session support
- allow linked preview to trigger another linked preview
- implement back pop behavior
- return to root reviewer when the chain is exhausted

Success criteria:

- reviewer -> linked preview A -> linked preview B -> back to A -> back to reviewer

Likely hurdles:

- whether to extend existing previewer or add a dedicated linked-preview flow
- preserving UI state between stack entries

### Milestone 4: Template configuration v1

Goal:

- replace hardcoded query-building logic with explicit hidden config

Scope:

- add config parser
- support small schema only
- update Mandarin template prototype to use it

Success criteria:

- template controls behavior declaratively

Likely hurdles:

- choosing a small, stable schema

### Milestone 5: UX polish

Goal:

- make the feature feel smooth and understandable

Scope:

- better empty-result messaging
- loading feedback
- cleaner result picker labels
- toolbar/back affordances
- tune transitions if needed

Success criteria:

- the feature does not feel like a debugging tool

### Milestone 6: Reuse for future editor/template extensions

Future possible reuse:

- search other decks to fill fields
- AI actions
- Azure TTS hints

This is intentionally deferred.

## Validation Gates

These checkpoints should stop the work if the foundation is not solid.

### Gate after Milestone 1

If preview launch from reviewer is awkward or fragile:

- switch to a dedicated linked-preview fragment/activity instead of forcing it into the generic previewer

If search resolution is ambiguous:

- define a strict v1 card-selection rule before adding picker/stack behavior

If reviewer state restoration is fragile:

- stop and fix that before recursion

### Gate after Milestone 3

If stack restoration becomes too complex:

- reduce preserved state for v1 to only card identity plus minimal scroll state

If deep recursion makes the UI confusing:

- add explicit breadcrumb or toolbar subtitle before expanding further

## Milestone 1 Implementation Checklist

1. Add a minimal native JS API method in `AnkiDroidJsAPI`.
2. Add a minimal native search resolver that returns one card ID if the result is unique.
3. Launch preview-only UI for that card.
4. Wire the Mandarin template prototype to call the new API instead of `ankiSearchCard(...)`.
5. Manually verify reviewer -> preview -> back.

## Testing Strategy

### Manual tests

- no config present: reviewer unchanged
- config present: selection button appears and triggers navigation
- zero matches: user gets a clear message
- one match: linked preview opens
- multiple matches: user gets a clear message in milestone 1, picker in milestone 2
- linked preview returns safely to reviewer
- original review card remains unchanged
- media/audio still work in linked preview

### Automated tests where practical

- config parser unit tests
- search resolution unit tests
- navigation stack viewmodel tests
- instrumentation tests if preview launch/state restoration needs them

## Non-Goals for the First Working Version

Do not attempt all of these before the first clean navigation path works:

- direct grading of linked cards
- config-driven AI actions
- config-driven TTS actions
- editor-side field autofill
- generalized mini-language for transforms and rules

## Definition of First Usable Version

From the reviewer, on a configured template:

- select text
- tap navigate
- run a search
- if exactly one result exists, open that result in preview-only mode
- back returns to the original reviewer card

This is the first proof that the architecture is sound.

## Recommended Next Step

Start with Milestone 1 only.

Do not implement:

- config parsing
- multiple-result picker
- stack-based recursion

until the single-result reviewer -> preview path is stable.
