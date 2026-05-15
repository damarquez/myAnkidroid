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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.R
import com.ichi2.anki.browser.IdsFile
import com.ichi2.anki.databinding.FragmentTemplatePreviewerBinding
import com.ichi2.anki.libanki.CardOrdinal
import com.ichi2.anki.navigation.NAVIGATION_OPEN_MODE_SHARE
import com.ichi2.anki.navigation.NavigationMatch
import com.ichi2.anki.navigation.findNavigationMatches
import com.ichi2.anki.navigation.parseNavigationRequest
import com.ichi2.anki.notelinks.extractGuidFromNoteLinkUrl
import com.ichi2.anki.notelinks.findFirstCardIdForNoteGuid
import com.ichi2.anki.snackbar.BaseSnackbarBuilderProvider
import com.ichi2.anki.snackbar.SnackbarBuilder
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.workarounds.SafeWebViewLayout
import com.ichi2.utils.IntentUtil
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class TemplatePreviewerFragment :
    CardViewerFragment(R.layout.fragment_template_previewer),
    BaseSnackbarBuilderProvider {
    override val viewModel: TemplatePreviewerViewModel by viewModels()

    lateinit var binding: FragmentTemplatePreviewerBinding

    override val webViewLayout: SafeWebViewLayout get() = binding.webViewLayout

    override val baseSnackbarBuilder: SnackbarBuilder
        get() = { anchorView = binding.showAnswer }

    override fun onLoadInitialHtml(): String =
        stdHtml(
            context = requireContext(),
            extraJsAssets = listOf("scripts/ankidroid-selection-nav.js"),
            nightMode = com.ichi2.themes.Themes.isNightTheme,
        )

    override fun onCreateWebViewClient(savedInstanceState: Bundle?): CardViewerWebViewClient =
        object : CardViewerWebViewClient(savedInstanceState) {
            override fun handleUrl(
                webView: WebView,
                url: Uri,
            ): Boolean {
                extractGuidFromNoteLinkUrl(url)?.let { guid ->
                    openLinkedNoteGuid(guid)
                    return true
                }
                if (url.scheme == "ankidroid-preview" && url.host == "navigate-card") {
                    val payload =
                        url.getQueryParameter("payload")
                            ?: url
                                .getQueryParameter("search")
                                .orEmpty()
                    openLinkedPreviewSearch(payload)
                    return true
                }
                return super.handleUrl(webView, url)
            }
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        // binding must be set before super.onViewCreated
        // as super.onViewCreated depends on webViewLayout, which depends on the binding
        binding = FragmentTemplatePreviewerBinding.bind(view)

        super.onViewCreated(view, savedInstanceState)

        binding.showAnswer.setOnClickListener { viewModel.toggleShowAnswer() }
        viewModel.showingAnswer
            .onEach { showingAnswer ->
                binding.showAnswer.text =
                    if (showingAnswer) {
                        getString(R.string.hide_answer)
                    } else {
                        getString(R.string.show_answer)
                    }
            }.launchIn(lifecycleScope)

        binding.webViewContainer.setFrameStyle()
    }

    private fun openLinkedPreviewSearch(payload: String) {
        lifecycleScope.launch {
            val request = parseNavigationRequest(payload)
            if (request.query.isBlank() && request.selectedText.isBlank()) {
                showSnackbar(getString(R.string.search_card_js_api_no_results))
                return@launch
            }

            if (request.openMode == NAVIGATION_OPEN_MODE_SHARE) {
                val share = request.share
                val textToShare = "${share?.prefix ?: ""}${request.selectedText}${share?.suffix ?: ""}"
                IntentUtil.shareText(requireContext(), textToShare)
                return@launch
            }

            val matches =
                runCatching { findNavigationMatches(request) }
                    .getOrElse {
                        showSnackbar(getString(R.string.search_card_js_api_no_results))
                        return@launch
                    }

            when {
                matches.isEmpty() -> showSnackbar(getString(R.string.search_card_js_api_no_results))
                matches.size == 1 -> openLinkedCardPreview(matches.single().cardId, request.openMode)
                else -> showNavigationMatchPicker(matches, request.openMode)
            }
        }
    }

    private fun openLinkedNoteGuid(guid: String) {
        lifecycleScope.launch {
            val cardId = findFirstCardIdForNoteGuid(guid)
            if (cardId == null) {
                showSnackbar(getString(R.string.search_card_js_api_no_results))
                return@launch
            }
            openLinkedCardPreview(cardId, openMode = "")
        }
    }

    private fun openLinkedCardPreview(
        cardId: Long,
        openMode: String,
    ) {
        val idsFile = IdsFile(requireContext().cacheDir, listOf(cardId), prefix = "linked-preview")
        val intent =
            PreviewerFragment.getIntent(
                requireContext(),
                idsFile = idsFile,
                currentIndex = 0,
                showAnswer = openMode == com.ichi2.anki.navigation.NAVIGATION_OPEN_MODE_ANSWER,
            )
        startActivity(intent)
    }

    private fun showNavigationMatchPicker(
        matches: List<NavigationMatch>,
        openMode: String,
    ) {
        val items =
            matches
                .map { match ->
                    val preview = match.preview.ifBlank { "(blank)" }
                    "$preview • ${match.deckName}"
                }.toTypedArray()

        AlertDialog
            .Builder(requireContext())
            .setTitle("Choose linked card")
            .setItems(items) { _, which ->
                openLinkedCardPreview(matches[which].cardId, openMode)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Updates the content displayed in the previewer with the provided fields and tags
     *
     * Should not be called for cloze deletions, since they they have dynamic ord
     *
     * @param fields The list of field values to display
     * @param tags The list of tags associated with the note
     */
    fun updateContent(
        fields: List<String>,
        tags: List<String>,
    ) {
        viewModel.updateContent(fields, tags)
    }

    /**
     * Retrieves a safe cloze ordinal number for cloze deletions.
     *
     * @return The safe cloze ordinal number
     */
    suspend fun getSafeClozeOrd(): CardOrdinal = viewModel.getSafeClozeOrd()

    companion object {
        const val ARGS_KEY = "templatePreviewerArgs"

        fun newInstance(arguments: TemplatePreviewerArguments): TemplatePreviewerFragment =
            TemplatePreviewerFragment().apply {
                val args = bundleOf(ARGS_KEY to arguments)
                this.arguments = args
            }
    }
}
