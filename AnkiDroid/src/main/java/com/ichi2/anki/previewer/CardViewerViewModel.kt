/*
 *  Copyright (c) 2024 Brayan Oliveira <brayandso.dev@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.previewer

import android.net.Uri
import androidx.annotation.CallSuper
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.OnErrorListener
import com.ichi2.anki.cardviewer.CardMediaPlayer
import com.ichi2.anki.cardviewer.MediaErrorHandler
import com.ichi2.anki.cardviewer.PlaybackCommandOptions
import com.ichi2.anki.launchCatchingIO
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.LinkedNoteDisplayMode
import com.ichi2.anki.libanki.TtsPlayer
import com.ichi2.anki.linkednotes.injectLinkedNoteBanner
import com.ichi2.anki.multimedia.AudioAutoPlayConfig
import com.ichi2.anki.multimedia.AudioAutoPlayMode
import com.ichi2.anki.multimedia.getAvTag
import com.ichi2.anki.multimedia.parseAudioAutoPlayConfigFromHtml
import com.ichi2.anki.multimedia.parseAudioPlayerOptions
import com.ichi2.anki.multimedia.replaceAvRefsWithPlayButtons
import com.ichi2.anki.notelinks.expandNoteLinksToHtml
import com.ichi2.anki.pages.AnkiServer
import com.ichi2.anki.pages.PostRequestHandler
import com.ichi2.anki.pages.PostRequestUri
import com.ichi2.anki.reviewer.CardSide
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber

abstract class CardViewerViewModel(
    val savedStateHandle: SavedStateHandle,
) : ViewModel(),
    OnErrorListener,
    PostRequestHandler {
    override val onError = MutableSharedFlow<String>()
    val onMediaError = MutableSharedFlow<String>()
    val onTtsError = MutableSharedFlow<TtsPlayer.TtsError>()
    val mediaErrorHandler =
        MediaErrorHandler(
            onMediaError = { viewModelScope.launch { onMediaError.emit(it) } },
            onTtsError = { viewModelScope.launch { onTtsError.emit(it) } },
        )

    val eval = MutableSharedFlow<String>()

    val showingAnswer = savedStateHandle.getMutableStateFlow(KEY_SHOWING_ANSWER, false)
    val linkedNoteDisplayMode =
        savedStateHandle.getMutableStateFlow(KEY_LINKED_NOTE_DISPLAY_MODE, LinkedNoteDisplayMode.MERGED.name)
    private var displayedAudioAutoPlayConfig = AudioAutoPlayConfig()

    protected val cardMediaPlayer =
        CardMediaPlayer(
            javascriptEvaluator = { launchCatchingIO { eval.emit(it) } },
            mediaErrorListener = mediaErrorHandler,
        ).also {
            addCloseable(it)
        }
    abstract var currentCard: Deferred<Card>

    abstract val server: AnkiServer

    @CallSuper
    override fun onCleared() {
        server.stop()
        super.onCleared()
    }

    /* *********************************************************************************************
     ************************ Public methods: meant to be used by the View **************************
     ********************************************************************************************* */

    /**
     * Call this after the webView has finished loading the page
     *
     * @param isAfterRecreation whether this is being called after an `Activity` recreation
     */
    abstract fun onPageFinished(isAfterRecreation: Boolean)

    fun baseUrl(): String = server.baseUrl()

    fun setSoundPlayerEnabled(isEnabled: Boolean) {
        viewModelScope.launch {
            cardMediaPlayer.setEnabled(isEnabled)
        }
    }

    fun playSoundFromUrl(url: String) {
        launchCatchingIO {
            val options = parseAudioPlayerOptions(url)
            getAvTag(currentCard.await(), url)?.let {
                emitAudioPlayerState(
                    state = "playing",
                    playerId = options.playerId,
                )
                cardMediaPlayer.playOneAndAwait(
                    it,
                    PlaybackCommandOptions(
                        playbackRate = options.playbackRate,
                        repeatCount = options.repeatCount,
                        gapMs = options.gapMs,
                    ),
                )
                emitAudioPlayerState(
                    state = "complete",
                    playerId = options.playerId,
                )
            }
        }
    }

    fun stopSoundFromUrl(url: String) {
        launchCatchingIO {
            cardMediaPlayer.stop()
            val parsed = Uri.parse(url)
            val playerId =
                if (parsed.isHierarchical) {
                    parsed.getQueryParameter("playerId")
                } else {
                    null
                }
            emitAudioPlayerState(
                state = "stopped",
                playerId = playerId,
            )
        }
    }

    fun onVideoFinished() = cardMediaPlayer.onVideoFinished()

    // A coroutine in the cardMediaPlayer waits for the video to complete
    // This cancels it
    fun onVideoPaused() = cardMediaPlayer.onVideoPaused()

    open fun toggleLinkedNoteDisplayMode() {
        launchCatchingIO {
            linkedNoteDisplayMode.emit(
                if (currentLinkedNoteDisplayMode() == LinkedNoteDisplayMode.MERGED) {
                    LinkedNoteDisplayMode.ORIGINAL.name
                } else {
                    LinkedNoteDisplayMode.MERGED.name
                },
            )
            onLinkedNoteDisplayModeChanged()
        }
    }

    /* *********************************************************************************************
     *************************************** Internal methods ***************************************
     ********************************************************************************************* */

    protected abstract suspend fun typeAnsFilter(text: String): String

    protected open suspend fun onLinkedNoteDisplayModeChanged() {
        val side = if (showingAnswer.value) CardSide.ANSWER else CardSide.QUESTION
        if (showingAnswer.value) showAnswer() else showQuestion()
        cardMediaPlayer.loadCardAvTags(currentCard.await(), currentLinkedNoteDisplayMode())
        autoplayMediaForDisplayedSide(side)
    }

    protected fun currentLinkedNoteDisplayMode(): LinkedNoteDisplayMode =
        runCatching { LinkedNoteDisplayMode.valueOf(linkedNoteDisplayMode.value) }
            .getOrDefault(LinkedNoteDisplayMode.MERGED)

    protected suspend fun autoplayMediaForDisplayedSide(side: CardSide) {
        when (displayedAudioAutoPlayConfig.mode) {
            AudioAutoPlayMode.SYSTEM -> cardMediaPlayer.autoplayAllForSide(side, displayedAudioAutoPlayConfig.playbackOptions)
            AudioAutoPlayMode.FORCE_YES ->
                cardMediaPlayer.playAllForSide(
                    side,
                    displayedAudioAutoPlayConfig.playbackOptions,
                    isAutomaticPlayback = true,
                )
            AudioAutoPlayMode.FORCE_NO -> Unit
        }
    }

    private suspend fun bodyClass() = bodyClassForCardOrd(currentCard.await().ord)

    /** From the [desktop code](https://github.com/ankitects/anki/blob/1ff55475b93ac43748d513794bcaabd5d7df6d9d/qt/aqt/reviewer.py#L358) */
    private suspend fun mungeQA(text: String) = typeAnsFilter(prepareCardTextForDisplay(text))

    @VisibleForTesting
    suspend fun prepareCardTextForDisplay(text: String): String =
        replaceAvRefsWithPlayButtons(
            text = expandNoteLinksToHtml(withCol { media.escapeMediaFilenames(text) }),
            renderOutput =
                currentCard.await().let { card ->
                    withCol { card.renderOutput(this, linkedNoteDisplayMode = currentLinkedNoteDisplayMode()) }
                },
        )

    protected open suspend fun showQuestion() {
        Timber.v("showQuestion")
        showingAnswer.emit(false)

        val card = currentCard.await()
        val questionData =
            withCol {
                injectLinkedNoteBanner(
                    this,
                    card,
                    card.question(this, linkedNoteDisplayMode = currentLinkedNoteDisplayMode()),
                    currentLinkedNoteDisplayMode(),
                )
            }
        val question = mungeQA(questionData)
        displayedAudioAutoPlayConfig = parseAudioAutoPlayConfigFromHtml(question)
        val answer =
            withCol {
                media.escapeMediaFilenames(
                    injectLinkedNoteBanner(
                        this,
                        card,
                        card.answer(this, linkedNoteDisplayMode = currentLinkedNoteDisplayMode()),
                        currentLinkedNoteDisplayMode(),
                    ),
                )
            }

        eval.emit("_showQuestion(${Json.encodeToString(question)}, ${Json.encodeToString(answer)}, '${bodyClass()}');")
    }

    /**
     * Parses the card answer and sends a [eval] request to load it into the `qa` HTML div
     *
     * * [Anki reference](https://github.com/ankitects/anki/blob/c985acb9fe36d3651eb83cf4cfe44d046ec7458f/qt/aqt/reviewer.py#L460)
     * * [Typescript reference](https://github.com/ankitects/anki/blob/c985acb9fe36d3651eb83cf4cfe44d046ec7458f/ts/reviewer/index.ts#L193)
     *
     * @see [stdHtml]
     */
    protected open suspend fun showAnswer() {
        Timber.v("showAnswer()")
        showingAnswer.emit(true)
        mediaErrorHandler.onCardSideChange()

        val card = currentCard.await()
        val answerData =
            withCol {
                injectLinkedNoteBanner(
                    this,
                    card,
                    card.answer(this, linkedNoteDisplayMode = currentLinkedNoteDisplayMode()),
                    currentLinkedNoteDisplayMode(),
                )
            }
        val answer = mungeQA(answerData)
        displayedAudioAutoPlayConfig = parseAudioAutoPlayConfigFromHtml(answer)

        eval.emit("_showAnswer(${Json.encodeToString(answer)}, '${bodyClass()}');")
    }

    override suspend fun handlePostRequest(
        uri: PostRequestUri,
        bytes: ByteArray,
    ): ByteArray =
        when (uri.backendMethodName) {
            null -> throw IllegalArgumentException("Unhandled POST request: $uri")
            "i18nResources" -> withCol { i18nResourcesRaw(bytes) }
            else -> throw IllegalArgumentException("Unhandled Anki request: $uri")
        }

    private suspend fun emitAudioPlayerState(
        state: String,
        playerId: String?,
    ) {
        eval.emit(
            "window.dispatchEvent(new CustomEvent('ankidroid-audio-player-state', { detail: { state: ${org.json.JSONObject.quote(
                state,
            )}, playerId: ${if (playerId != null) org.json.JSONObject.quote(playerId) else "null"} } }));",
        )
    }

    companion object {
        private const val KEY_SHOWING_ANSWER = "showingAnswer"
        private const val KEY_LINKED_NOTE_DISPLAY_MODE = "linkedNoteDisplayMode"
    }
}
