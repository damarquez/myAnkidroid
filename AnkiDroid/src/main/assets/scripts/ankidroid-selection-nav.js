// Renders in-page Char/Word/Search buttons when the user selects text inside a
// card whose template contains a <script id="ankidroid-nav-config" type="application/json">
// block. Clicking a button fires ankidroid-preview://navigate-card?payload=...,
// handled natively by the Previewer/Reviewer fragments.
(function () {
    const SEARCH_HINT_ID = "ankidroid-selection-search-hint";
    const SEARCH_BTN_CLASS = "ankidroid-selection-search-btn";
    const DEBUG_BADGE_ID = "ankidroid-selection-nav-debug";

    function showDebugBadge(text, color) {
        try {
            const existing = document.getElementById(DEBUG_BADGE_ID);
            if (existing) existing.remove();
            const el = document.createElement("div");
            el.id = DEBUG_BADGE_ID;
            el.textContent = text;
            el.style.cssText =
                "position:fixed;top:8px;right:8px;z-index:999999;padding:4px 8px;" +
                "border-radius:6px;font-size:11px;font-weight:700;color:#fff;" +
                "background:" +
                color +
                ";box-shadow:0 2px 6px rgba(0,0,0,0.3);";
            document.body.appendChild(el);
            setTimeout(() => {
                if (el.parentNode) el.remove();
            }, 3000);
        } catch (e) {}
    }

    function removeSelectionSearchUI() {
        document.querySelectorAll("." + SEARCH_BTN_CLASS).forEach(btn => btn.remove());
        const oldHint = document.getElementById(SEARCH_HINT_ID);
        if (oldHint) oldHint.remove();
    }

    function getSelectedText() {
        const sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) return "";
        return (sel.toString() || "").trim();
    }

    function escapeForAnkiSearch(text) {
        return text.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
    }

    function getSelectionRect() {
        const sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) return null;
        const range = sel.getRangeAt(0);
        if (range.collapsed) return null;
        const rect = range.getBoundingClientRect();
        if (rect && (rect.width || rect.height)) return rect;
        const rects = range.getClientRects();
        if (rects && rects.length) return rects[0];
        return null;
    }

    function stripJsonComments(text) {
        return String(text || "")
            .replace(/\/\*[\s\S]*?\*\//g, "")
            .replace(/^\s*\/\/.*$/gm, "");
    }

    function deckExists(entry) {
        const known = window.__ankidroidKnownDecks;
        // If we haven't received the list yet, don't filter — avoids hiding buttons
        // during the brief window between page load and native injection.
        if (!Array.isArray(known) || known.length === 0) return true;
        const target = String((entry && entry.deck) || "").toLowerCase();
        if (!target) return false;
        return known.some(name => String(name).toLowerCase() === target);
    }

    function getSearchConfig() {
        const configEl = document.getElementById("ankidroid-nav-config");
        if (!configEl) return null;
        try {
            const parsed = JSON.parse(stripJsonComments(configEl.textContent || "{}"));
            const sections = ["character", "singleCharWord", "word"];
            const result = { character: null, singleCharWord: null, word: null, missingDecks: [] };
            for (const key of sections) {
                const entry = parsed[key];
                if (!entry) continue;
                if (deckExists(entry)) {
                    result[key] = entry;
                } else {
                    result.missingDecks.push(entry.deck);
                }
            }
            return result;
        } catch (e) {
            return null;
        }
    }

    function buildNavigationPayload(query, config, selectedText) {
        return JSON.stringify({
            query,
            selectedText,
            openMode: String((config && config.openMode) || "question").toLowerCase(),
            search: {
                deck: String((config && config.deck) || ""),
                field: String((config && config.field) || "Front"),
                matchMode: String((config && config.matchMode) || "exact").toLowerCase(),
                prefix: String((config && config.prefix) || ""),
                suffix: String((config && config.suffix) || ""),
            },
        });
    }

    function buildFieldQuery(text, config) {
        const escapedDeck = escapeForAnkiSearch(config.deck || "");
        const escapedField = String(config.field || "Front");
        const normalizedText = `${config.prefix || ""}${text}${config.suffix || ""}`;
        const escapedText = escapeForAnkiSearch(normalizedText);
        const matchMode = String(config.matchMode || "exact").toLowerCase();
        const fieldClause =
            matchMode === "partial"
                ? `${escapedField}:*${escapedText}*`
                : `${escapedField}:"${escapedText}"`;
        return `deck:"${escapedDeck}" ${fieldClause}`;
    }

    function openPreviewSearch(payload) {
        try {
            window.location.href = `ankidroid-preview://navigate-card?payload=${encodeURIComponent(payload)}`;
        } catch (e) {}
    }

    function createSearchButton(selectedText, rect) {
        removeSelectionSearchUI();

        const clean = (selectedText || "").trim();
        if (!clean || !rect) return;

        const config = getSearchConfig();
        if (!config) return;

        const hasAnySection = config.character || config.singleCharWord || config.word;
        if (!hasAnySection && config.missingDecks && config.missingDecks.length) {
            const unique = Array.from(new Set(config.missingDecks));
            showDebugBadge("nav: unknown deck " + unique.join(", "), "#c62828");
            return;
        }

        const isSingleChar = clean.length === 1;

        const btnWidth = 84;
        const btnHeight = 36;
        const btnGap = 8;
        const nativePopupSafetyGap = 72;

        const buttons = [];

        function makeBtn(label, query, searchConfig) {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = SEARCH_BTN_CLASS;
            btn.textContent = label;

            btn.style.position = "fixed";
            btn.style.zIndex = "999999";
            btn.style.width = btnWidth + "px";
            btn.style.height = btnHeight + "px";
            btn.style.border = "none";
            btn.style.borderRadius = "18px";
            btn.style.background = "#2e7d32";
            btn.style.color = "#fff";
            btn.style.fontSize = "14px";
            btn.style.fontWeight = "600";
            btn.style.boxShadow = "0 3px 10px rgba(0,0,0,0.25)";
            btn.style.opacity = "0.96";

            btn.addEventListener("mousedown", function (e) {
                e.preventDefault();
                e.stopPropagation();
            });
            btn.addEventListener(
                "touchstart",
                function (e) {
                    e.stopPropagation();
                },
                { passive: true },
            );
            btn.addEventListener("click", function (e) {
                e.preventDefault();
                e.stopPropagation();
                const payload = buildNavigationPayload(query, searchConfig, clean);
                openPreviewSearch(payload);
                removeSelectionSearchUI();
            });

            document.body.appendChild(btn);
            buttons.push(btn);
        }

        if (isSingleChar) {
            if (config.character) {
                makeBtn("Char", buildFieldQuery(clean, config.character), config.character);
            }
            if (config.singleCharWord) {
                makeBtn(
                    "Word",
                    buildFieldQuery(clean, config.singleCharWord),
                    config.singleCharWord,
                );
            }
            if (!buttons.length && config.word) {
                makeBtn("Search", buildFieldQuery(clean, config.word), config.word);
            }
        } else if (config.word) {
            makeBtn("Search", buildFieldQuery(clean, config.word), config.word);
        }

        if (!buttons.length) return;

        const totalWidth = buttons.length * btnWidth + (buttons.length - 1) * btnGap;
        let startLeft = rect.left + rect.width / 2 - totalWidth / 2;
        startLeft = Math.max(8, Math.min(window.innerWidth - totalWidth - 8, startLeft));
        let top = rect.bottom + nativePopupSafetyGap;
        if (top + btnHeight > window.innerHeight - 8) {
            top = rect.top - btnHeight - nativePopupSafetyGap;
        }
        top = Math.max(8, Math.min(window.innerHeight - btnHeight - 8, top));

        buttons.forEach((btn, i) => {
            btn.style.left = startLeft + i * (btnWidth + btnGap) + "px";
            btn.style.top = top + "px";
        });
    }

    function updateSelectionButton() {
        const text = getSelectedText();
        if (!text) {
            removeSelectionSearchUI();
            return;
        }
        const rect = getSelectionRect();
        if (!rect) {
            removeSelectionSearchUI();
            return;
        }
        createSearchButton(text, rect);
    }

    document.addEventListener("selectionchange", function () {
        setTimeout(updateSelectionButton, 180);
    });

    document.addEventListener(
        "scroll",
        function () {
            const text = getSelectedText();
            if (!text) {
                removeSelectionSearchUI();
                return;
            }
            setTimeout(updateSelectionButton, 10);
        },
        true,
    );

    // Visible load confirmation. Polls briefly because the card content (which
    // contains #ankidroid-nav-config) and the known-decks list are both pushed
    // into the page after the initial HTML loads.
    (function announceReady() {
        showDebugBadge("nav: script loaded", "#1565c0");
        let tries = 0;
        const iv = setInterval(() => {
            tries++;
            const hasConfigEl = !!document.getElementById("ankidroid-nav-config");
            const hasDecks = Array.isArray(window.__ankidroidKnownDecks);
            if (hasConfigEl && hasDecks) {
                const config = getSearchConfig();
                const anySection =
                    config && (config.character || config.singleCharWord || config.word);
                if (anySection) {
                    showDebugBadge("nav: config OK", "#2e7d32");
                } else if (config && config.missingDecks && config.missingDecks.length) {
                    const unique = Array.from(new Set(config.missingDecks));
                    showDebugBadge("nav: unknown deck " + unique.join(", "), "#c62828");
                } else {
                    showDebugBadge("nav: empty config", "#c62828");
                }
                clearInterval(iv);
            } else if (tries > 20) {
                showDebugBadge(hasConfigEl ? "nav: decks unknown" : "nav: no config", "#c62828");
                clearInterval(iv);
            }
        }, 500);
    })();

    document.addEventListener(
        "click",
        function (e) {
            const clickedButton = e.target.closest("." + SEARCH_BTN_CLASS);
            if (!clickedButton) {
                const sel = window.getSelection();
                if (!sel || sel.isCollapsed) {
                    removeSelectionSearchUI();
                }
            }
        },
        true,
    );
})();
