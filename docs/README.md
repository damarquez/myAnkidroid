# Card Rendering Notes

This document captures project-specific findings about how card HTML is rendered in AnkiDroid, especially when adding template-driven features such as navigation controls or custom audio UI.

## Two Card HTML Paths

AnkiDroid currently has two different HTML-generation paths for cards:

1. `card_template.html`

Used by the `CardTemplate` renderer in [CardTemplate.kt](../AnkiDroid/src/main/java/com/ichi2/anki/cardviewer/CardTemplate.kt).

This path assembles HTML by replacing placeholders inside the shared asset:

- `::style::`
- `::script::`
- `::class::`
- `::content::`

2. `stdHtml()`

Defined in [PreviewerHelpers.kt](../AnkiDroid/src/main/java/com/ichi2/anki/previewer/PreviewerHelpers.kt).

This path is used by the reviewer and previewer-style card viewers. It builds a separate HTML shell and injects shared JS assets through `extraJsAssets`.

## Practical Consequence

Features can appear to be "broken" if they are only wired into one of these HTML paths.

This happened with the native audio-player enhancement:

- the code was added to `card_template.html`
- testing was done in the preview-style viewer
- the preview-style viewer actually renders via `stdHtml()`
- result: the feature never loaded, even though the implementation itself was otherwise correct

## Rule Of Thumb

If a feature changes how a card is rendered or interacted with inside the WebView, verify it in both:

1. study/reviewer mode
2. preview/render-only mode

Unless the feature is explicitly tied to grading or reviewer-only controls, it should usually work in both modes.

## Current Mapping

- `card_template.html`: shared asset renderer used by `CardTemplate`
- `stdHtml()`: shared shell for reviewer/previewer card viewers
- reviewer-only additions: passed through `extraJsAssets`
- preview-only additions: also passed through `extraJsAssets`

## Development Checklist

When adding a new card-side feature:

1. Identify which renderer path is used by the target screen.
2. Check whether the same feature should exist in both study and preview modes.
3. If yes, wire the asset or HTML change into both `card_template.html` and `stdHtml()`, or move the shared behavior into the common path that both viewers use.
4. Test in both modes before assuming the feature is broken.

## Known Example

The `ankidroid-audio-config` feature is a concrete example:

- normal `[sound:...]` tags are rendered natively
- the richer player UI is a WebView enhancement layer
- therefore the enhancement script must be loaded in the actual viewer HTML path being exercised
- for reviewer/previewer screens, that means `stdHtml()`, not only `card_template.html`
