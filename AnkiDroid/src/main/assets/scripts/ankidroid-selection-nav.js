// Renders in-page Char/Word/Search buttons when the user selects text inside a
// card whose template contains a <script id="ankidroid-nav-config" type="application/json">
// block. Clicking a button fires ankidroid-preview://navigate-card?payload=...,
// handled natively by the Previewer/Reviewer fragments.
(function () {
    const SEARCH_HINT_ID = "ankidroid-selection-search-hint";
    const SEARCH_BTN_CLASS = "ankidroid-selection-search-btn";

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

    function titleCaseToken(value) {
        const text = String(value || "").trim();
        if (!text) return "";
        return text.charAt(0).toUpperCase() + text.slice(1);
    }

    function normalizeOriginField(value) {
        return String(value || "")
            .normalize("NFKC")
            .replace(/[\u200B-\u200D\u2060\uFEFF\u200E\u200F\u202A-\u202E]/g, "")
            .replace(/\s+/g, " ")
            .trim()
            .toLowerCase();
    }

    function parseOriginFieldFromId(id) {
        const text = String(id || "").trim();
        if (!text) return "";
        const prefixes = ["source-", "target-", "field-", "fld-"];
        for (const prefix of prefixes) {
            if (text.toLowerCase().startsWith(prefix)) {
                return text.slice(prefix.length).trim();
            }
        }
        return "";
    }

    function parseOriginFieldFromClassList(classList) {
        if (!classList || typeof classList.length !== "number") return "";
        for (const cls of classList) {
            const text = String(cls || "").trim();
            if (!text) continue;
            const lower = text.toLowerCase();
            if (
                lower === "field-container" ||
                lower === "field-rich-content" ||
                lower === "field-label"
            ) {
                continue;
            }
            if (lower.endsWith("-field") && lower !== "field") {
                return titleCaseToken(text.slice(0, -"-field".length));
            }
            if (
                lower.startsWith("field-") &&
                lower.length > "field-".length &&
                lower !== "field-container" &&
                lower !== "field-rich-content" &&
                lower !== "field-label"
            ) {
                return titleCaseToken(text.slice("field-".length));
            }
        }
        return "";
    }

    function parseOriginFieldFromLabel(container) {
        if (!container || typeof container.querySelector !== "function") return "";
        const label = container.querySelector(".field-label");
        if (!label) return "";
        return String(label.textContent || "")
            .replace(/[:\s]+$/g, "")
            .trim();
    }

    function resolveOriginFieldFromNode(node) {
        let current = node;
        while (current) {
            if (current.nodeType === Node.ELEMENT_NODE) {
                const explicit =
                    current.getAttribute("data-ankidroid-origin-field") ||
                    current.getAttribute("data-origin-field") ||
                    current.getAttribute("data-field");
                if (explicit && String(explicit).trim()) {
                    return String(explicit).trim();
                }

                const fromId = parseOriginFieldFromId(current.id);
                if (fromId) return fromId;

                const fromClass = parseOriginFieldFromClassList(current.classList);
                if (fromClass) return fromClass;

                const fromLabel = parseOriginFieldFromLabel(current);
                if (fromLabel) return fromLabel;
            }
            current = current.parentNode;
        }
        return "";
    }

    function getSelectionOriginField() {
        const sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) return "";
        const range = sel.getRangeAt(0);
        return (
            resolveOriginFieldFromNode(range.startContainer) ||
            resolveOriginFieldFromNode(range.endContainer)
        );
    }

    function deckExists(entry) {
        const navMode = String((entry && entry.navMode) || "deck").toLowerCase();
        if (navMode === "app") return true;
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
            const result = { character: [], singleCharWord: [], word: [], missingDecks: [] };
            for (const key of sections) {
                const rawEntries = parsed[key];
                if (!rawEntries) continue;
                const entries = Array.isArray(rawEntries) ? rawEntries : [rawEntries];
                for (const entry of entries) {
                    if (!entry) continue;
                    if (deckExists(entry)) {
                        result[key].push(entry);
                    } else {
                        result.missingDecks.push(entry.deck);
                    }
                }
            }
            return result;
        } catch (e) {
            return null;
        }
    }

    function chooseSearchConfigs(entries, originField) {
        if (!Array.isArray(entries) || entries.length === 0) return [];
        const normalizedOrigin = normalizeOriginField(originField);
        const matchingEntries = [];
        const genericEntries = [];
        for (const entry of entries) {
            const entryOrigin = normalizeOriginField(entry && entry.originField);
            if (!entryOrigin) {
                genericEntries.push(entry);
                continue;
            }
            if (normalizedOrigin && entryOrigin === normalizedOrigin) {
                matchingEntries.push(entry);
            }
        }
        return matchingEntries.length ? matchingEntries : genericEntries;
    }

    function buildNavigationPayload(query, config, selectedText, originField) {
        return JSON.stringify({
            query,
            selectedText,
            originField,
            openMode: String((config && config.openMode) || "question").toLowerCase(),
            search: {
                deck: String((config && config.deck) || ""),
                field: String((config && config.field) || "Front"),
                fallbackField: String((config && config.fallbackField) || ""),
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

    function getDefaultButtonLabel(sectionName) {
        if (sectionName === "character") return "Char";
        if (sectionName === "singleCharWord") return "Word";
        return "Search";
    }

    function buildAppUrl(template, selectedText) {
        const rawTemplate = String(template || "").trim();
        if (!rawTemplate) return "";
        return rawTemplate.replace(/\[\[\s*(url:)?([^\]]+)\s*\]\]/gi, function (_match, urlPrefix) {
            const value = selectedText;
            return urlPrefix ? encodeURIComponent(value) : value;
        });
    }

    function openAppAction(url) {
        try {
            window.location.href = url;
        } catch (e) {}
    }

    function createSearchButton(selectedText, rect) {
        removeSelectionSearchUI();

        const clean = (selectedText || "").trim();
        if (!clean || !rect) return;

        const config = getSearchConfig();
        if (!config) return;

        const hasAnySection =
            config.character.length || config.singleCharWord.length || config.word.length;
        if (!hasAnySection && config.missingDecks && config.missingDecks.length) {
            return;
        }

        const isSingleChar = clean.length === 1;
        const originField = getSelectionOriginField();
        const characterConfigs = chooseSearchConfigs(config.character, originField);
        const singleCharWordConfigs = chooseSearchConfigs(config.singleCharWord, originField);
        const wordConfigs = chooseSearchConfigs(config.word, originField);

        const btnHeight = 36;
        const btnGap = 8;
        const nativePopupSafetyGap = 72;

        const buttons = [];

        function makeBtn(label, clickHandler) {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = SEARCH_BTN_CLASS;
            btn.textContent = label;

            btn.style.position = "fixed";
            btn.style.zIndex = "999999";
            btn.style.minWidth = "84px";
            btn.style.height = btnHeight + "px";
            btn.style.padding = "var(--ankidroid-selection-nav-button-padding, 0 14px)";
            btn.style.border = "var(--ankidroid-selection-nav-button-border, none)";
            btn.style.borderRadius = "var(--ankidroid-selection-nav-button-radius, 18px)";
            btn.style.background = "var(--ankidroid-selection-nav-button-bg, #2e7d32)";
            btn.style.color = "var(--ankidroid-selection-nav-button-color, #fff)";
            btn.style.fontSize = "var(--ankidroid-selection-nav-button-font-size, 14px)";
            btn.style.fontWeight = "var(--ankidroid-selection-nav-button-font-weight, 600)";
            btn.style.boxShadow =
                "var(--ankidroid-selection-nav-button-shadow, 0 3px 10px rgba(0,0,0,0.25))";
            btn.style.opacity = "var(--ankidroid-selection-nav-button-opacity, 0.96)";

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
                clickHandler();
                removeSelectionSearchUI();
            });

            document.body.appendChild(btn);
            buttons.push(btn);
        }

        function addButtonsForConfigs(sectionName, entries) {
            entries.forEach(function (entry) {
                const navMode = String((entry && entry.navMode) || "deck").toLowerCase();
                const label = String((entry && entry.label) || getDefaultButtonLabel(sectionName));
                if (navMode === "app") {
                    const url = buildAppUrl(entry && entry.urlTemplate, clean);
                    if (!url) return;
                    makeBtn(label, function () {
                        openAppAction(url);
                    });
                    return;
                }
                const query = buildFieldQuery(clean, entry);
                makeBtn(label, function () {
                    const payload = buildNavigationPayload(query, entry, clean, originField);
                    openPreviewSearch(payload);
                });
            });
        }

        if (isSingleChar) {
            addButtonsForConfigs("character", characterConfigs);
            addButtonsForConfigs("singleCharWord", singleCharWordConfigs);
            if (!buttons.length) {
                addButtonsForConfigs("word", wordConfigs);
            }
        } else {
            addButtonsForConfigs("word", wordConfigs);
        }

        if (!buttons.length) return;

        const widths = buttons.map(btn => Math.ceil(btn.getBoundingClientRect().width));
        const totalWidth =
            widths.reduce((sum, width) => sum + width, 0) + (buttons.length - 1) * btnGap;
        let startLeft = rect.left + rect.width / 2 - totalWidth / 2;
        startLeft = Math.max(8, Math.min(window.innerWidth - totalWidth - 8, startLeft));
        let top = rect.bottom + nativePopupSafetyGap;
        if (top + btnHeight > window.innerHeight - 8) {
            top = rect.top - btnHeight - nativePopupSafetyGap;
        }
        top = Math.max(8, Math.min(window.innerHeight - btnHeight - 8, top));

        let left = startLeft;
        buttons.forEach((btn, i) => {
            btn.style.left = left + "px";
            btn.style.top = top + "px";
            left += widths[i] + btnGap;
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
