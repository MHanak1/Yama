package net.mhanak.yama.media.sources

/** A kind of library item the user may rate / favourite. */
enum class RateableKind { Track, Album, Artist, Genre, Playlist }

/**
 * How a source lets the user express liking an item — i.e. which control the UI renders. The style
 * is per [RateableKind] (see [MusicSource.ratingStyle]) because backends differ both in *mechanism*
 * (Jellyfin favourites with a heart; Navidrome/Subsonic rate with 1–5 stars) and in *which* kinds
 * are rateable at all. [None] means "no rating concept here" and the control hides itself.
 */
sealed interface RatingStyle {
    object None : RatingStyle

    /** Binary favourite — a heart, filled when [Rating.favorite]. */
    object Favorite : RatingStyle

    /** A 1..[max] star rating; tapping opens a picker. [Rating.stars] holds the current value. */
    data class Stars(val max: Int = 5) : RatingStyle
}

/**
 * An item's current rating. [favorite] is the value behind [RatingStyle.Favorite]; [stars]
 * (null = unrated) behind [RatingStyle.Stars]. One value type carries both so the rating control can
 * stay style-agnostic and a source that exposes both (e.g. Jellyfin) loses no information.
 */
data class Rating(val favorite: Boolean = false, val stars: Int? = null) {
    companion object {
        val Unrated = Rating()
    }
}
