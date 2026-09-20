# Hidden Text Viewer Layout Investigation

## Problem

Two screenshots in `xchange/` show different layouts for what appears to be the same card answer content:

- `xchange/study_layout.jpeg`: the answer layout during study looks correct.
- `xchange/view_layout.jpeg`: the answer shown in the viewer after selecting text and using the `Char` navigation button is offset. The hidden text covers are visible, but the rows are shifted and there is extra vertical spacing.

The affected templates use `ankidroid-hidden-text-config` to turn marked text lines into expandable hidden rows. The visible symptom is around those hidden rows, but the issue may not be caused by the hidden text script itself.

## Initial Observation

The viewer screenshot shows two native audio player rows above each hidden-text group, while the study screenshot shows one. This made `{{FrontSide}} + {{Back}}` look suspicious at first.

That explanation is incomplete. The reviewed answer also renders the answer side, so `{{FrontSide}} + {{Back}}` is expected in both cases. The fact that study answer mode is correct means the template structure alone is probably not enough to explain the mismatch.

## Relevant Template Details

The Mandarin memory palace templates begin their answer side with:

```html
{{FrontSide}}
<br>
{{Back}}
```

The same answer templates also include:

- `ankidroid-audio-config`
- `ankidroid-hidden-text-config`
- `ankidroid-nav-config`

The `Char` navigation entries open linked cards with:

```json
"openMode": "answer"
```

Relevant files:

- `testTemplates/MandarinMemPalaceScene/Card1Back.html`
- `testTemplates/MandarinMemPalaceScene/Card2Back.html`
- `AnkiDroid/src/main/assets/scripts/ankidroid-selection-nav.js`

## Rendering Paths Found

There are multiple ways the app draws card content.

### Legacy Study Screen

The legacy reviewer renders a complete HTML document for each side.

Key path:

- `AnkiDroid/src/main/java/com/ichi2/anki/AbstractFlashcardViewer.kt`
- `displayCardQuestion()`
- `displayCardAnswer()`
- `AndroidCardRenderContext.renderCard()`
- `card_template.html`

The answer side is rendered as a full document and loaded with `WebView.loadDataWithBaseURL()`.

### Previewer / Direct Viewer

The previewer starts with a static document from `stdHtml()` and then injects question or answer content into `#qa` using JavaScript.

Key path:

- `AnkiDroid/src/main/java/com/ichi2/anki/previewer/PreviewerViewModel.kt`
- `AnkiDroid/src/main/java/com/ichi2/anki/previewer/CardViewerViewModel.kt`
- `showQuestion()`
- `showAnswer()`
- `_showQuestion(...)`
- `_showAnswer(...)`

When a linked card is opened with `openMode: "answer"`, the previewer can call `showAnswer()` directly, without first showing the question.

### New Study Screen

The new study screen also uses `CardViewerViewModel` and `stdHtml()`, but `ReviewerFragment` has reviewer-specific behavior that the previewer does not share.

One notable difference:

```kotlin
private fun resetZoom() {
    webViewLayout.settings.loadWithOverviewMode = false
    webViewLayout.settings.loadWithOverviewMode = true
}
```

`ReviewerFragment` calls `resetZoom()` when answer state changes. `PreviewerFragment` does not appear to do the same.

Relevant file:

- `AnkiDroid/src/main/java/com/ichi2/anki/ui/windows/reviewer/ReviewerFragment.kt`

## Why `{{FrontSide}} + {{Back}}` Is Probably Not the Whole Problem

`TemplateManager` renders both question and answer in one render pass. It renders the question first, extracts question-side audio tags, then injects the rendered question text into `{{FrontSide}}` while rendering the answer.

Relevant file:

- `libanki/src/main/java/com/ichi2/anki/libanki/TemplateManager.kt`

Important behavior:

```kotlin
val qtext = applyCustomFilters(partial.qnodes, this, frontSide = null)
val qout = col.backend.extractAvTags(text = qtext, questionSide = true)

val atext = applyCustomFilters(partial.anodes, this, frontSide = qout.text)
val aout = col.backend.extractAvTags(text = atext, questionSide = false)
```

This means direct answer rendering should still have a valid `FrontSide`; it does not depend on the question having already been displayed in the WebView.

## Stronger Investigation Leads

### 1. Different HTML Container and Update Model

The legacy reviewer loads a full document with `#content`. The previewer/new reviewer load one shell and replace `#qa`.

This can matter because:

- scripts inside injected answer HTML need to be re-evaluated correctly;
- mutation observers can fire during partial DOM construction;
- CSS selectors may behave differently under `#qa` versus `#content`;
- initial answer rendering through `_showAnswer()` may not exactly match the question-then-answer sequence.

### 2. Previewer Direct Answer Skips the Question State

During normal study, the user sees question first, then answer. Direct linked preview in answer mode starts on answer.

This may affect:

- scroll position;
- WebView scale;
- image/audio layout measurement;
- observer timing in `ankidroid-audio-player.js` and `ankidroid-hidden-text.js`.

### 3. Zoom Reset Exists in Reviewer but Not Previewer

The new reviewer resets WebView overview mode when the displayed side changes. The previewer does not. This is a strong suspect because the bad screenshot looks like a layout/scale issue around wide flex rows.

Files to compare:

- `AnkiDroid/src/main/java/com/ichi2/anki/ui/windows/reviewer/ReviewerFragment.kt`
- `AnkiDroid/src/main/java/com/ichi2/anki/previewer/PreviewerFragment.kt`
- `AnkiDroid/src/main/java/com/ichi2/anki/previewer/CardViewerFragment.kt`

### 4. Linked Preview Inside Previewer Has Its Own Navigation Stack

When a linked card is opened from the previewer, `PreviewerFragment` does not start a new activity. It captures scroll and asks `PreviewerViewModel.openLinkedCard(...)` to replace the current card.

Relevant files:

- `AnkiDroid/src/main/java/com/ichi2/anki/previewer/PreviewerFragment.kt`
- `AnkiDroid/src/main/java/com/ichi2/anki/previewer/PreviewerViewModel.kt`

This path is different from opening linked preview from the reviewer, which starts a new `Previewer` activity. It is worth checking whether the bad screenshot was produced from reviewer-to-previewer, or previewer-to-linked-previewer.

## Suggested Next Checks

1. Confirm whether `study_layout.jpeg` is from the legacy reviewer or the new reviewer.
2. Capture the final DOM for both cases after all hooks run:
   - reviewed answer;
   - direct preview answer.
3. Compare `document.body.className`, `window.scrollY`, `window.visualViewport.scale`, `document.body.style.zoom`, and `document.getElementById("qa").innerHTML`.
4. Temporarily add the reviewer `resetZoom()` behavior to `PreviewerFragment` after answer rendering and check whether the layout matches.
5. Test opening the same linked card with `openMode: "question"` and then manually showing the answer in previewer. If that layout matches study, the direct `_showAnswer()` path is likely involved.
6. Check whether duplicate native audio player rows are expected from the template content or are being produced by repeated enhancement in `ankidroid-audio-player.js`.

## Current Conclusion

The problem is probably not just the hidden-text configuration and probably not simply `{{FrontSide}} + {{Back}}`.

The most useful direction is to compare the rendering context and lifecycle:

- legacy full-document reviewer path versus previewer injected `#qa` path;
- direct answer rendering versus question-then-answer rendering;
- reviewer zoom reset versus previewer no zoom reset.

