(function () {
    const CONFIG_ID = "ankidroid-audio-config";
    const PLAYER_CLASS = "ankidroid-native-audio-player";
    const STOP_ALL_CLASS = "ankidroid-native-audio-stop-all";
    const STATE_EVENT = "ankidroid-audio-player-state";
    const SPEED_ICON = "\u26A1\uFE0E";

    let activePlayerId = null;
    let observerInstalled = false;

    function stripJsonComments(text) {
        return String(text || "")
            .replace(/\/\*[\s\S]*?\*\//g, "")
            .replace(/^\s*\/\/.*$/gm, "");
    }

    function uniqueNumericList(values, fallback) {
        const normalized = Array.isArray(values)
            ? values.map(value => Number(value)).filter(value => Number.isFinite(value))
            : [];
        const unique = Array.from(new Set(normalized));
        return unique.length ? unique : fallback;
    }

    function getAudioConfig() {
        const configEl = document.getElementById(CONFIG_ID);
        if (!configEl) return null;
        try {
            const parsed = JSON.parse(stripJsonComments(configEl.textContent || "{}"));
            if (parsed.enabled === false) return null;

            const playbackSpeeds = uniqueNumericList(parsed.playbackSpeeds, [1.0]);
            const repeatCounts = uniqueNumericList(parsed.repeatCounts, [
                Number(parsed.repeatCount) || 1,
            ]).map(value => Math.max(1, Math.round(value)));
            const defaultPlaybackSpeed =
                playbackSpeeds.find(value => value === Number(parsed.defaultPlaybackSpeed)) ||
                playbackSpeeds[0];
            const defaultRepeatCount =
                repeatCounts.find(
                    value => value === Number(parsed.defaultRepeatCount || parsed.repeatCount),
                ) || repeatCounts[0];

            const config = {
                playbackSpeeds,
                repeatCounts,
                defaultPlaybackSpeed,
                defaultRepeatCount,
                gapMs: Math.max(0, Number(parsed.gapMs) || 0),
                showStopAllButton: parsed.showStopAllButton === true,
                prefix: typeof parsed.prefix === "string" ? parsed.prefix : "",
                suffix: typeof parsed.suffix === "string" ? parsed.suffix : "",
            };
            return config;
        } catch (e) {
            console.error("Invalid ankidroid-audio-config", e);
            return null;
        }
    }

    function formatSpeed(value) {
        return `${Number(value).toFixed(2).replace(/\.00$/, "")}x`;
    }

    function lockButtonWidthToWidestLabel(button, labels) {
        const original = button.textContent;
        let maxWidth = 0;
        labels.forEach(label => {
            button.textContent = label;
            const width = button.getBoundingClientRect().width;
            if (width > maxWidth) maxWidth = width;
        });
        button.textContent = original;
        if (maxWidth > 0) {
            button.style.minWidth = `${Math.ceil(maxWidth)}px`;
            button.style.textAlign = "center";
        }
    }

    function setPlayerState(player, state) {
        if (!player) return;
        player.dataset.state = state;
        const playButton = player.querySelector(".ankidroid-audio-play");
        if (playButton) {
            playButton.textContent = state === "playing" ? "\u25A0" : "\u25B6";
            playButton.setAttribute("aria-label", state === "playing" ? "Stop" : "Play");
        }
    }

    function stopActivePlayer() {
        if (!activePlayerId) return;
        window.location.href = `stopsound:player?playerId=${encodeURIComponent(activePlayerId)}`;
    }

    function stopAllPlayers() {
        window.location.href = "stopsound:all";
    }

    function setAllPlayersIdle() {
        document.querySelectorAll(`.${PLAYER_CLASS}`).forEach(player => {
            setPlayerState(player, "idle");
        });
        activePlayerId = null;
    }

    function buildPlayUrl(player) {
        const href = player.dataset.playUrl;
        const playerId = player.dataset.playerId;
        const repeatCount = Number(player.dataset.repeatCount || "1");
        const playbackRate = Number(player.dataset.playbackRate || "1");
        const gapMs = Number(player.dataset.gapMs || "0");
        const params = new URLSearchParams({
            playerId,
            repeat: String(repeatCount),
            rate: String(playbackRate),
            gapMs: String(gapMs),
        });
        return `${href}?${params.toString()}`;
    }

    function createAffixNode(text, className) {
        if (!text) return null;
        const span = document.createElement("span");
        span.className = className;
        span.textContent = text;
        span.style.whiteSpace = "pre-wrap";
        return span;
    }

    function createPlayer(anchor, config, index) {
        const player = document.createElement("span");
        player.className = PLAYER_CLASS;
        player.dataset.playUrl = anchor.getAttribute("href") || "";
        player.dataset.playerId = `ankidroid-audio-player-${index}`;
        player.dataset.playbackRate = String(config.defaultPlaybackSpeed);
        player.dataset.repeatCount = String(config.defaultRepeatCount);
        player.dataset.gapMs = String(config.gapMs);
        player.dataset.state = "idle";

        const playButton = document.createElement("button");
        playButton.type = "button";
        playButton.className = "ankidroid-audio-play";
        playButton.textContent = "\u25B6";
        playButton.setAttribute("aria-label", "Play");

        playButton.addEventListener("click", function (event) {
            event.preventDefault();
            event.stopPropagation();

            const isCurrent = activePlayerId === player.dataset.playerId;
            if (isCurrent && player.dataset.state === "playing") {
                stopActivePlayer();
                return;
            }

            if (activePlayerId && activePlayerId !== player.dataset.playerId) {
                stopActivePlayer();
            }

            activePlayerId = player.dataset.playerId;
            setPlayerState(player, "playing");
            window.location.href = buildPlayUrl(player);
        });
        player.appendChild(playButton);

        let speedButton = null;
        let repeatButton = null;

        if (config.playbackSpeeds.length > 1) {
            speedButton = document.createElement("button");
            speedButton.type = "button";
            speedButton.className = "ankidroid-audio-speed";
            speedButton.setAttribute("aria-label", "Playback speed");
            speedButton.textContent = `${SPEED_ICON} ${formatSpeed(config.defaultPlaybackSpeed)}`;
            speedButton.addEventListener("click", function (event) {
                event.preventDefault();
                event.stopPropagation();
                const current = Number(player.dataset.playbackRate || config.defaultPlaybackSpeed);
                const currentIndex = config.playbackSpeeds.indexOf(current);
                const nextIndex = (currentIndex + 1) % config.playbackSpeeds.length;
                const nextValue = config.playbackSpeeds[nextIndex];
                player.dataset.playbackRate = String(nextValue);
                speedButton.textContent = `${SPEED_ICON} ${formatSpeed(nextValue)}`;
            });
            player.appendChild(speedButton);
        }

        if (config.repeatCounts.length > 1) {
            repeatButton = document.createElement("button");
            repeatButton.type = "button";
            repeatButton.className = "ankidroid-audio-repeat";
            repeatButton.setAttribute("aria-label", "Repeat count");
            repeatButton.textContent = `\u21BB ${config.defaultRepeatCount}x`;
            repeatButton.addEventListener("click", function (event) {
                event.preventDefault();
                event.stopPropagation();
                const current = Number(player.dataset.repeatCount || config.defaultRepeatCount);
                const currentIndex = config.repeatCounts.indexOf(current);
                const nextIndex = (currentIndex + 1) % config.repeatCounts.length;
                const nextValue = config.repeatCounts[nextIndex];
                player.dataset.repeatCount = String(nextValue);
                repeatButton.textContent = `\u21BB ${nextValue}x`;
            });
            player.appendChild(repeatButton);
        }

        anchor.style.display = "none";
        anchor.dataset.ankidroidAudioEnhanced = "true";
        const fragment = document.createDocumentFragment();
        const prefix = createAffixNode(config.prefix, "ankidroid-audio-prefix");
        const suffix = createAffixNode(config.suffix, "ankidroid-audio-suffix");
        if (prefix) fragment.appendChild(prefix);
        fragment.appendChild(player);
        if (suffix) fragment.appendChild(suffix);
        anchor.parentNode.insertBefore(fragment, anchor.nextSibling);

        lockButtonWidthToWidestLabel(playButton, ["\u25B6", "\u25A0"]);
        if (speedButton) {
            lockButtonWidthToWidestLabel(
                speedButton,
                config.playbackSpeeds.map(v => `${SPEED_ICON} ${formatSpeed(v)}`),
            );
        }
        if (repeatButton) {
            lockButtonWidthToWidestLabel(
                repeatButton,
                config.repeatCounts.map(v => `\u21BB ${v}x`),
            );
        }
    }

    function ensureStopAllButton(config, hasPlayers) {
        let button = document.querySelector(`.${STOP_ALL_CLASS}`);
        if (!config.showStopAllButton || !hasPlayers) {
            if (button) button.remove();
            return;
        }

        if (!button) {
            button = document.createElement("button");
            button.type = "button";
            button.className = STOP_ALL_CLASS;
            button.textContent = "Stop";
            button.addEventListener("click", function (event) {
                event.preventDefault();
                event.stopPropagation();
                stopAllPlayers();
            });
            document.body.appendChild(button);
        }
    }

    function enhanceAudioPlayers() {
        const config = getAudioConfig();
        if (!config) return;

        const anchors = document.querySelectorAll(
            ".replay-button.soundLink:not([data-ankidroid-audio-enhanced])",
        );
        anchors.forEach((anchor, index) => {
            createPlayer(anchor, config, index);
        });
        const hasPlayers = document.querySelectorAll(`.${PLAYER_CLASS}`).length > 0;
        ensureStopAllButton(config, hasPlayers);
    }

    function installObserver() {
        if (observerInstalled) return;
        const qa = document.getElementById("qa");
        if (!qa || typeof MutationObserver === "undefined") return;

        const observer = new MutationObserver(function () {
            enhanceAudioPlayers();
        });
        observer.observe(qa, { childList: true, subtree: true });
        observerInstalled = true;
    }

    window.addEventListener(STATE_EVENT, function (event) {
        const detail = event && event.detail ? event.detail : {};
        const playerId = detail.playerId || "";
        const state = detail.state || "idle";
        if (!playerId && (state === "stopped" || state === "complete")) {
            setAllPlayersIdle();
            return;
        }

        const player = document.querySelector(`.${PLAYER_CLASS}[data-player-id="${playerId}"]`);

        if (state === "playing") {
            activePlayerId = playerId || activePlayerId;
            setPlayerState(player, "playing");
            return;
        }

        if (activePlayerId === playerId || state === "stopped" || state === "complete") {
            activePlayerId = null;
        }
        setPlayerState(player, "idle");
    });

    function installHook(hookArray) {
        hookArray.push(function () {
            enhanceAudioPlayers();
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

    if (!hooksInstalled) {
        enhanceAudioPlayers();
        document.addEventListener("DOMContentLoaded", function () {
            installObserver();
            enhanceAudioPlayers();
        });
    } else {
        enhanceAudioPlayers();
    }
})();
