(function () {
    const CONFIG_ID = "ankidroid-hidden-text-config";
    const DEFAULT_SELECTOR = ".field-rich-content, [data-ankidroid-hidden-text]";
    const WRAPPER_CLASS = "ankidroid-hidden-text-wrapper";
    const TOGGLE_CLASS = "ankidroid-hidden-text-toggle";
    const CONTENT_CLASS = "ankidroid-hidden-text-content";
    const ALT_CLASS = "ankidroid-hidden-text-alt";

    let observerInstalled = false;

    function stripJsonComments(text) {
        return String(text || "")
            .replace(/\/\*[\s\S]*?\*\//g, "")
            .replace(/^\s*\/\/.*$/gm, "");
    }

    function parseInitialState(value) {
        return String(value || "hidden").toLowerCase() === "revealed" ? "revealed" : "hidden";
    }

    function normalizeRule(rule) {
        if (!rule || rule.enabled === false) return null;

        const begin = typeof rule.begin === "string" && rule.begin.length ? rule.begin : null;
        const end = typeof rule.end === "string" && rule.end.length ? rule.end : null;
        if (!begin && !end) return null;
        if (begin && end && begin === end) return null;

        return {
            begin,
            end,
            label: String(rule.label || ""),
            initialState: parseInitialState(rule.initialState),
            delimiterHasEOL: rule.delimiterHasEOL === true,
        };
    }

    function getHiddenTextConfig() {
        const configEl = document.getElementById(CONFIG_ID);
        if (!configEl) return null;
        try {
            const parsed = JSON.parse(stripJsonComments(configEl.textContent || "{}"));
            if (parsed.enabled === false) return null;
            const rules = Array.isArray(parsed.rules)
                ? parsed.rules.map(normalizeRule).filter(Boolean)
                : [];
            if (!rules.length) return null;
            return {
                selector:
                    typeof parsed.targetSelector === "string" && parsed.targetSelector.trim()
                        ? parsed.targetSelector.trim()
                        : DEFAULT_SELECTOR,
                rules,
            };
        } catch (e) {
            console.error("Invalid ankidroid-hidden-text-config", e);
            return null;
        }
    }

    function childIndex(node) {
        const parent = node && node.parentNode;
        if (!parent) return 0;
        return Array.prototype.indexOf.call(parent.childNodes, node);
    }

    function collectUnits(root) {
        const units = [];

        function visit(node) {
            if (!node) return;
            if (node.nodeType === Node.TEXT_NODE) {
                const text = node.textContent || "";
                for (let i = 0; i < text.length; i++) {
                    units.push({ type: "char", char: text.charAt(i), node, offset: i });
                }
                return;
            }
            if (node.nodeType !== Node.ELEMENT_NODE) return;

            const tagName = String(node.tagName || "").toLowerCase();
            if (tagName === "br") {
                units.push({ type: "br", char: "\n", node });
                return;
            }

            Array.from(node.childNodes).forEach(visit);
        }

        visit(root);
        return units;
    }

    function unitsToText(units) {
        return units.map(unit => unit.char).join("");
    }

    function pointBeforeUnit(unit) {
        if (unit.type === "char") {
            return { container: unit.node, offset: unit.offset };
        }
        return { container: unit.node.parentNode, offset: childIndex(unit.node) };
    }

    function pointAfterUnit(unit) {
        if (unit.type === "char") {
            return { container: unit.node, offset: unit.offset + 1 };
        }
        return { container: unit.node.parentNode, offset: childIndex(unit.node) + 1 };
    }

    function pointAt(root, units, index) {
        if (!units.length) {
            return { container: root, offset: root.childNodes.length };
        }
        if (index <= 0) {
            return pointBeforeUnit(units[0]);
        }
        if (index >= units.length) {
            return pointAfterUnit(units[units.length - 1]);
        }
        return pointBeforeUnit(units[index]);
    }

    function createRange(root, units, startIndex, endIndex) {
        const range = document.createRange();
        const startPoint = pointAt(root, units, startIndex);
        const endPoint = pointAt(root, units, endIndex);
        range.setStart(startPoint.container, startPoint.offset);
        range.setEnd(endPoint.container, endPoint.offset);
        return range;
    }

    function trimRightSpaces(text, start, end) {
        let cursor = end;
        while (cursor > start && /[ \t\r]/.test(text.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    function trimLeftSpaces(text, start, end) {
        let cursor = start;
        while (cursor < end && /[ \t\r]/.test(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    function hasBoundaryBefore(text, index) {
        let cursor = index - 1;
        while (cursor >= 0 && /[ \t\r]/.test(text.charAt(cursor))) {
            cursor--;
        }
        return cursor < 0 || text.charAt(cursor) === "\n";
    }

    function hasBoundaryAfter(text, index) {
        let cursor = index;
        while (cursor < text.length && /[ \t\r]/.test(text.charAt(cursor))) {
            cursor++;
        }
        return cursor >= text.length || text.charAt(cursor) === "\n";
    }

    function lineStartIndex(text, index) {
        let cursor = index - 1;
        while (cursor >= 0 && text.charAt(cursor) !== "\n") {
            cursor--;
        }
        return cursor + 1;
    }

    function lineEndIndex(text, index) {
        let cursor = index;
        while (cursor < text.length && text.charAt(cursor) !== "\n") {
            cursor++;
        }
        return cursor;
    }

    function overlaps(existingMatches, start, end) {
        return existingMatches.some(match => start < match.fullEnd && end > match.fullStart);
    }

    function findRuleMatches(text, rule, existingMatches) {
        const matches = [];

        function pushMatch(candidate) {
            if (candidate.contentStart >= candidate.contentEnd) return;
            if (overlaps(existingMatches, candidate.fullStart, candidate.fullEnd)) return;
            if (overlaps(matches, candidate.fullStart, candidate.fullEnd)) return;
            matches.push(candidate);
        }

        if (rule.begin && rule.end) {
            let searchFrom = 0;
            while (searchFrom < text.length) {
                const beginStart = text.indexOf(rule.begin, searchFrom);
                if (beginStart < 0) break;
                const beginEnd = beginStart + rule.begin.length;
                if (rule.delimiterHasEOL && !hasBoundaryBefore(text, beginStart)) {
                    searchFrom = beginStart + 1;
                    continue;
                }

                const endStart = text.indexOf(rule.end, beginEnd);
                if (endStart < 0) break;
                const endEnd = endStart + rule.end.length;
                if (rule.delimiterHasEOL && !hasBoundaryAfter(text, endEnd)) {
                    searchFrom = beginStart + 1;
                    continue;
                }

                pushMatch({
                    beginRemoveStart: beginStart,
                    beginRemoveEnd: beginEnd,
                    endRemoveStart: endStart,
                    endRemoveEnd: endEnd,
                    contentStart: beginEnd,
                    contentEnd: endStart,
                    fullStart: beginStart,
                    fullEnd: endEnd,
                    label: rule.label,
                    initialState: rule.initialState,
                });
                searchFrom = endEnd;
            }
            return matches;
        }

        if (rule.end) {
            let searchFrom = 0;
            while (searchFrom < text.length) {
                const endStart = text.indexOf(rule.end, searchFrom);
                if (endStart < 0) break;
                const endEnd = endStart + rule.end.length;
                if (rule.delimiterHasEOL && !hasBoundaryAfter(text, endEnd)) {
                    searchFrom = endStart + 1;
                    continue;
                }

                const naturalStart = lineStartIndex(text, endStart);
                const contentEnd = trimRightSpaces(text, naturalStart, endStart);
                pushMatch({
                    beginRemoveStart: null,
                    beginRemoveEnd: null,
                    endRemoveStart: contentEnd,
                    endRemoveEnd: endEnd,
                    contentStart: naturalStart,
                    contentEnd,
                    fullStart: naturalStart,
                    fullEnd: endEnd,
                    label: rule.label,
                    initialState: rule.initialState,
                });
                searchFrom = endEnd;
            }
            return matches;
        }

        let searchFrom = 0;
        while (searchFrom < text.length) {
            const beginStart = text.indexOf(rule.begin, searchFrom);
            if (beginStart < 0) break;
            const beginEnd = beginStart + rule.begin.length;
            if (rule.delimiterHasEOL && !hasBoundaryBefore(text, beginStart)) {
                searchFrom = beginStart + 1;
                continue;
            }

            const naturalEnd = lineEndIndex(text, beginEnd);
            const contentStart = trimLeftSpaces(text, beginEnd, naturalEnd);
            pushMatch({
                beginRemoveStart: beginStart,
                beginRemoveEnd: beginEnd,
                endRemoveStart: null,
                endRemoveEnd: null,
                contentStart,
                contentEnd: naturalEnd,
                fullStart: beginStart,
                fullEnd: naturalEnd,
                label: rule.label,
                initialState: rule.initialState,
            });
            searchFrom = beginEnd;
        }
        return matches;
    }

    function createWrapper(fragment, label, initialState) {
        const wrapper = document.createElement("div");
        wrapper.className = WRAPPER_CLASS;

        const button = document.createElement("button");
        button.type = "button";
        button.className = TOGGLE_CLASS;

        const content = document.createElement("div");
        content.className = CONTENT_CLASS;
        if (initialState === "revealed") {
            content.classList.add("is-revealed");
            button.textContent = "-";
        } else {
            content.classList.add("is-hidden");
            button.textContent = "+";
        }

        content.appendChild(fragment);

        if (label) {
            content.classList.add("has-alt");
            const alt = document.createElement("span");
            alt.className = ALT_CLASS;
            alt.textContent = label;
            content.appendChild(alt);
        }

        button.addEventListener("click", function (event) {
            event.preventDefault();
            event.stopPropagation();
            const hidden = content.classList.contains("is-hidden");
            if (hidden) {
                content.classList.remove("is-hidden");
                content.classList.add("is-revealed");
                button.textContent = "-";
            } else {
                content.classList.remove("is-revealed");
                content.classList.add("is-hidden");
                button.textContent = "+";
            }
        });

        wrapper.appendChild(button);
        wrapper.appendChild(content);
        return wrapper;
    }

    function applyMatchesToTarget(target, matches, units) {
        for (let i = matches.length - 1; i >= 0; i--) {
            const match = matches[i];

            if (
                typeof match.endRemoveStart === "number" &&
                typeof match.endRemoveEnd === "number" &&
                match.endRemoveEnd > match.endRemoveStart
            ) {
                createRange(
                    target,
                    units,
                    match.endRemoveStart,
                    match.endRemoveEnd,
                ).deleteContents();
            }

            const contentRange = createRange(target, units, match.contentStart, match.contentEnd);
            const fragment = contentRange.extractContents();
            const wrapper = createWrapper(fragment, match.label, match.initialState);
            contentRange.insertNode(wrapper);

            if (
                typeof match.beginRemoveStart === "number" &&
                typeof match.beginRemoveEnd === "number" &&
                match.beginRemoveEnd > match.beginRemoveStart
            ) {
                createRange(
                    target,
                    units,
                    match.beginRemoveStart,
                    match.beginRemoveEnd,
                ).deleteContents();
            }
        }
    }

    function enhanceHiddenText() {
        const config = getHiddenTextConfig();
        if (!config) return;

        const targets = document.querySelectorAll(config.selector);
        targets.forEach(target => {
            if (!target || target.dataset.ankidroidHiddenTextProcessed === "1") return;

            const units = collectUnits(target);
            const text = unitsToText(units);
            if (!text) {
                target.dataset.ankidroidHiddenTextProcessed = "1";
                return;
            }

            const matches = [];
            config.rules.forEach(rule => {
                findRuleMatches(text, rule, matches).forEach(match => matches.push(match));
            });

            if (matches.length) {
                applyMatchesToTarget(
                    target,
                    matches.sort((a, b) => a.fullStart - b.fullStart),
                    units,
                );
            }
            target.dataset.ankidroidHiddenTextProcessed = "1";
        });
    }

    function installObserver() {
        if (observerInstalled) return;
        const qa = document.getElementById("qa") || document.getElementById("content");
        if (!qa || typeof MutationObserver === "undefined") return;

        const observer = new MutationObserver(function () {
            enhanceHiddenText();
        });
        observer.observe(qa, { childList: true, subtree: true });
        observerInstalled = true;
    }

    function installHook(hookArray) {
        hookArray.push(function () {
            enhanceHiddenText();
            return Promise.resolve();
        });
    }

    let hooksInstalled = false;
    if (typeof require === "function") {
        try {
            const reviewer = require("anki/reviewer");
            if (
                reviewer &&
                Array.isArray(reviewer.onUpdateHook) &&
                Array.isArray(reviewer.onShownHook)
            ) {
                installHook(reviewer.onUpdateHook);
                installHook(reviewer.onShownHook);
                hooksInstalled = true;
            }
        } catch (e) {
            // ignore
        }
    }

    if (
        !hooksInstalled &&
        typeof onUpdateHook !== "undefined" &&
        Array.isArray(onUpdateHook) &&
        typeof onShownHook !== "undefined" &&
        Array.isArray(onShownHook)
    ) {
        installHook(onUpdateHook);
        installHook(onShownHook);
        hooksInstalled = true;
    }

    installObserver();
    enhanceHiddenText();

    if (!hooksInstalled) {
        document.addEventListener("DOMContentLoaded", function () {
            installObserver();
            enhanceHiddenText();
        });
    }
})();
