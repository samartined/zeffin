// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

enum class SortBy { ACTIVITY, CREATED, TITLE }

enum class Period { ALL, WEEK, MONTH }

/** How the list is shown; each tab keeps its own (All is grouped by day, Favorites is not). */
data class ConversationFilters(
    val groupByDay: Boolean = true,
    val sort: SortBy = SortBy.ACTIVITY,
    val period: Period = Period.ALL,
    val onlyFlipper: Boolean = false,
) {
    companion object {
        fun defaults(favorites: Boolean) = if (favorites) ConversationFilters(groupByDay = false) else ConversationFilters()
    }
}

data class ConvRowUi(
    val id: String, val title: String, val time: String, val last: String, val favorite: Boolean, val pinned: Boolean,
    val active: String?,
    /** Remote Control is on in it: the icon before the star, with this label for TalkBack. */
    val remote: String? = null,
    /** While selecting several: whether this one is selected; null when not selecting. */
    val selected: Boolean? = null,
)

/**
 * Several conversations selected: the bar's count and what it shows, and what the actions do to all
 * of them: Pin pins them all unless every one is pinned ([pin] false: Unpin), the same with favorites; Rename and
 * Copy ID with one only; Delete when the server can delete.
 */
data class SelectionUi(val title: String, val sub: String, val pin: Boolean, val favorite: Boolean, val single: Boolean,
                       val canDelete: Boolean)

/** Deleting several asks first: how many, what they hold, and that a running turn stops first. */
data class DeleteManyUi(val title: String, val sub: String, val rows: List<Pair<String, String>>, val text: String,
                        val delete: String, val keep: String)

/** Some could not be deleted: they stay selected, and this says why, with Try again. */
data class DeletionsUi(val title: String, val text: String)

data class ConvGroupUi(val title: String, val pinned: Boolean, val rows: List<ConvRowUi>)

data class ConversationsUi(
    val favorites: Boolean,
    val groups: List<ConvGroupUi>,
    /** "3 favorites · not grouped · newest first", with Change; only when it differs from the usual view. */
    val summary: String?,
    val query: String,
    val empty: String?,
    val available: Boolean,
)

/** The sheet open over the list. */
enum class ConvSheet { NONE, FILTERS, SORT, PERIOD, RENAME, DELETE, DELETE_MANY }
