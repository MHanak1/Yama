package net.mhanak.yama.media.sources.local

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.mhanak.yama.getAppDataDir
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Artist
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.media.model.Lyrics
import net.mhanak.yama.media.model.Playlist
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.AccountedSource
import net.mhanak.yama.media.sources.AlbumSortOrder
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.FavoriteCapable
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.SourceAccount
import net.mhanak.yama.media.sources.SourceType
import net.mhanak.yama.media.sources.StaleWhileRevalidateSource
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.util.AppPreferences
import net.mhanak.yama.util.StreamingQuality
import java.io.File
import java.security.MessageDigest

/**
 * A [MusicSource] that indexes audio off the device's own filesystem — no network. It mirrors
 * [net.mhanak.yama.media.sources.JellyfinSource]'s shape (StateFlows + stale-while-revalidate) so the
 * rest of the app browses and plays it unchanged.
 *
 * The on-disk [store] is ingestion-agnostic; this class is the *ingester*, pairing [scanAudioFiles]
 * (platform file enumeration) with [readTrackTags] (platform tag reading) and writing rows in. Album
 * art is extracted from embedded tags once per album into [artworkDir] and referenced by `file://`
 * URI, which Coil3 and both playback engines load directly.
 *
 * IDs are stable content hashes (track = hash(path), album = hash(albumArtist+album), artist/genre =
 * hash(name)) so detail navigation, favourites and queue restore survive rescans.
 */

/**
 * Progress of the tag-reading phase of a [LocalSource] scan: [done] of [total] files ingested. Used to
 * drive a determinate first-run scanning indicator. [fraction] is null (→ indeterminate) when [total]
 * is 0, so the UI never divides by zero on an empty folder set.
 */
data class ScanProgress(val done: Int, val total: Int) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

class LocalSource(
    private val store: LocalLibraryStore,
    private val artworkDir: File,
) : StaleWhileRevalidateSource(), FavoriteCapable, AccountedSource {
    override val type: SourceType = SourceType.Local

    // No auth concept — the source is always usable. Kept as a var to satisfy the interface; nothing
    // flips it false.
    override var isAuthenticated: Boolean by mutableStateOf(true)

    // --- AccountedSource -------------------------------------------------------------------------
    // Local is a single fixed identity — no login, no account switching, no logout.

    private val localAccount = SourceAccount(
        id = SOURCE_KEY,
        sourceType = SourceType.Local,
        name = SourceType.Local.displayName,
        subtitle = "On this device",
        avatarUrl = null,
        // The local source has no offline partition (not OfflineCapable), so its stable key is the
        // fixed "local" — matching AppContainer.scrobbleKey's fallback for per-server settings.
        stableKey = SOURCE_KEY,
    )

    override val accounts: List<SourceAccount> get() = listOf(localAccount)
    override val currentAccountId: String get() = SOURCE_KEY
    override fun selectAccount(id: String) {} // single account, nothing to switch
    // supportsLogout stays false (default)

    // -------------------------------------------------------------------------------------

    // Local files have no playlist concept in a first pass (.m3u parsing is a later seam).
    // _playlists is inherited from StaleWhileRevalidateSource; it stays empty for this source.

    // The watched root folders. Exposed so the settings UI can list/add/remove them and stay in sync.
    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    // Live progress of the tag-reading phase of a scan, or null when no scan is ingesting files.
    // Published per file in [refresh] so a first-run "scanning" screen can show a determinate bar; the
    // preceding directory walk ([scanAudioFiles]) reports nothing (total unknown), so the UI shows an
    // indeterminate spinner until this turns non-null.
    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

    // In-memory mirror of the stored rows + derived albums, so browse queries are plain lookups and
    // never re-read the store or re-hash. Rebuilt on every emit.
    @Volatile private var rows: List<StoredTrack> = emptyList()
    @Volatile private var albumsById: Map<String, Album> = emptyMap()

    init {
        // Seed the platform default Music dir the first time (null = never configured); an explicitly
        // empty list is preserved so a user who removed every folder isn't re-seeded.
        val configured = AppPreferences.localFolders()
        val initial = configured ?: defaultMusicFolders().also { AppPreferences.setLocalFolders(it) }
        _folders.value = initial

        // Stale-while-revalidate: emit whatever is already indexed at once, then rescan in the
        // background. The rescan is incremental, so an unchanged library settles almost immediately.
        deriveAndEmit(store.all(SOURCE_KEY))
        scope.launch { runCatching { refresh() } }
    }

    override suspend fun refresh() = refresh(forceAll = false)

    // Serialises scans. Nothing stops several refreshes overlapping — init fires one, and every
    // addFolder/rescan fires another — and concurrent runs would interleave their per-file progress
    // writes into [_scanProgress] (the "bar jitters between two points" bug). The lock also keeps
    // store.replaceAll from racing. A queued second scan is incremental, so it settles almost instantly.
    private val refreshMutex = Mutex()

    private suspend fun refresh(forceAll: Boolean) = refreshMutex.withLock { runRefresh {
        if (forceAll) artworkDir.listFiles()?.forEach { it.delete() }
        artworkDir.mkdirs()
        val files = scanAudioFiles(_folders.value)
        val existingByPath = if (forceAll) emptyMap() else store.all(SOURCE_KEY).associateBy { it.path }
        // albumId -> resolved artwork file:// URI for this pass, so every track of an album shares
        // one extracted cover and we extract it at most once.
        val albumArt = HashMap<String, String>()

        val result = ArrayList<StoredTrack>(files.size)
        try {
            for ((index, f) in files.withIndex()) {
                _scanProgress.value = ScanProgress(done = index, total = files.size)
                val prev = existingByPath[f.path]
                if (prev != null && prev.lastModified == f.lastModified) {
                    // Unchanged — reuse the row as-is (incremental skip) and seed the art cache.
                    result += prev
                    if (prev.albumId != null && prev.artworkPath != null) albumArt.putIfAbsent(prev.albumId, prev.artworkPath)
                    continue
                }
                result += ingest(f, albumArt)
            }
            store.replaceAll(SOURCE_KEY, result)
            deriveAndEmit(result)
        } finally {
            // Clear progress even if a file throws mid-scan, so a stuck bar can't outlive the pass.
            _scanProgress.value = null
        }
    } }

    /** Read one file's tags (falling back to the filename) and build its [StoredTrack] row. */
    private fun ingest(f: AudioFile, albumArt: HashMap<String, String>): StoredTrack {
        val file = File(f.path)
        val tags = runCatching { readTrackTags(f.path) }.getOrNull()

        // A file with no readable title tag is treated as metadata-less; everything below still falls
        // back to the filename/folder so it stays playable when the "skip" setting is off.
        val hasMetadata = !tags?.title.isNullOrBlank()

        val title = tags?.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val albumName = tags?.album?.takeIf { it.isNotBlank() } ?: file.parentFile?.name ?: UNKNOWN_ALBUM
        // The album-artist drives album identity, so tracks of one album group together. Prefer the
        // explicit tag; for compilations (differing track artists, no shared album-artist) fall back to
        // "Various Artists" so the whole album stays one entry instead of splitting per track; only
        // otherwise borrow the track artist (a single-artist album that just omits the album-artist tag).
        val albumArtistName = tags?.albumArtist?.takeIf { it.isNotBlank() }
            ?: if (tags?.isCompilation == true) VARIOUS_ARTISTS else tags?.artists?.firstOrNull { it.isNotBlank() }
            ?: UNKNOWN_ARTIST
        val artistNames = tags?.artists?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: listOf(albumArtistName)
        val genreNames = tags?.genre?.split(';', '/', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

        val albumId = hashId("album", albumArtistName, albumName)
        val artworkPath = resolveAlbumArt(albumId, tags?.artwork, albumArt)

        return StoredTrack(
            sourceKey = SOURCE_KEY,
            id = hashId("track", f.path),
            path = f.path,
            title = title,
            albumId = albumId,
            album = albumName,
            albumArtist = albumArtistName,
            albumArtistId = hashId("artist", albumArtistName),
            artists = artistNames,
            artistIds = artistNames.map { hashId("artist", it) },
            genres = genreNames,
            genreIds = genreNames.map { hashId("genre", it) },
            trackNumber = tags?.trackNumber,
            discNumber = tags?.discNumber,
            durationMs = tags?.durationMs,
            year = tags?.year,
            artworkPath = artworkPath,
            lastModified = f.lastModified,
            hasMetadata = hasMetadata,
        )
    }

    /** Return the album's cover `file://` URI, extracting [bytes] to [artworkDir] once per album. */
    private fun resolveAlbumArt(albumId: String, bytes: ByteArray?, cache: HashMap<String, String>): String? {
        cache[albumId]?.let { return it }
        val target = File(artworkDir, "$albumId.jpg")
        val uri = when {
            target.exists() -> target.toURI().toString()
            bytes != null -> runCatching { target.writeBytes(bytes); target.toURI().toString() }.getOrNull()
            else -> null
        }
        if (uri != null) cache[albumId] = uri
        return uri
    }

    /** Rebuild the in-memory caches and the public StateFlows from a row set. */
    private fun deriveAndEmit(storedRows: List<StoredTrack>) {
        // Hide metadata-less files when the user has opted in. The index keeps every row, so this is a
        // pure display filter — toggling the setting only re-derives, no rescan needed.
        val allRows = if (AppPreferences.skipTracksWithoutMetadata) storedRows.filter { it.hasMetadata } else storedRows
        rows = allRows
        val favAlbums = AppPreferences.localFavorites(FavoritableKind.Album.name)
        val favArtists = AppPreferences.localFavorites(FavoritableKind.Artist.name)
        val favGenres = AppPreferences.localFavorites(FavoritableKind.Genre.name)

        // Albums: one per albumId; pull the first non-null year/art and union the genres.
        val albums = allRows
            .filter { it.albumId != null }
            .groupBy { it.albumId!! }
            .map { (id, group) ->
                val first = group.first()
                Album(
                    id = id,
                    name = first.album ?: UNKNOWN_ALBUM,
                    albumArtist = first.albumArtist,
                    year = group.firstNotNullOfOrNull { it.year },
                    songCount = group.size,
                    imageUrl = group.firstNotNullOfOrNull { it.artworkPath },
                    imageHash = null,
                    favorite = id in favAlbums,
                    genres = group.flatMap { it.genres }.distinct(),
                )
            }
            .sortedBy { it.name.lowercase() }
        albumsById = albums.associateBy { it.id }

        // Artists: every distinct (artistId -> name) across contributing + album artists. Give an
        // artist the cover of an album they front, for a nicer grid.
        val artLookup = albums.filter { it.albumArtist != null }
            .associateBy({ hashId("artist", it.albumArtist!!) }, { it.imageUrl })
        data class ArtistAcc(var name: String, val genres: MutableSet<String> = mutableSetOf())
        val artistAcc = LinkedHashMap<String, ArtistAcc>()
        val albumArtistIds = HashSet<String>()
        for (row in allRows) {
            row.albumArtistId?.let { id ->
                albumArtistIds += id
                artistAcc.getOrPut(id) { ArtistAcc(row.albumArtist ?: UNKNOWN_ARTIST) }.genres += row.genres
            }
            row.artists.forEachIndexed { i, name ->
                val id = row.artistIds.getOrNull(i) ?: return@forEachIndexed
                artistAcc.getOrPut(id) { ArtistAcc(name) }.genres += row.genres
            }
        }
        val artists = artistAcc.map { (id, acc) ->
            Artist(id = id, name = acc.name, imageUrl = artLookup[id], imageHash = null,
                favorite = id in favArtists, genres = acc.genres.toList())
        }.sortedBy { it.name.lowercase() }

        // Genres: distinct (genreId -> name).
        val genreNames = LinkedHashMap<String, String>()
        for (row in allRows) row.genreIds.forEachIndexed { i, id -> row.genres.getOrNull(i)?.let { genreNames.putIfAbsent(id, it) } }
        val genres = genreNames.map { (id, name) -> Genre(id = id, name = name, imageUrl = null, imageHash = null, favorite = id in favGenres) }
            .sortedBy { it.name.lowercase() }

        _albums.value = albums
        _artists.value = artists
        _albumArtists.value = artists.filter { it.id in albumArtistIds }
        _genres.value = genres
    }

    override suspend fun getTracksForAlbum(albumId: String): List<Track> {
        val favs = trackFavorites()
        return rows.filter { it.albumId == albumId }
            .sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }, { it.title.lowercase() }))
            .map { it.toTrack(favs) }
    }

    override suspend fun getTracksForArtist(artistId: String, limit: Int, offset: Int, sortBy: TrackSortOrder): List<Track> {
        val favs = trackFavorites()
        return rows.filter { artistId in it.artistIds || it.albumArtistId == artistId }
            .sortedForBrowse(sortBy).drop(offset).take(limit).map { it.toTrack(favs) }
    }

    override suspend fun getTracksForGenre(genreId: String, limit: Int, offset: Int, sortBy: TrackSortOrder): List<Track> {
        val favs = trackFavorites()
        return rows.filter { genreId in it.genreIds }
            .sortedForBrowse(sortBy).drop(offset).take(limit).map { it.toTrack(favs) }
    }

    override suspend fun getAllTracks(limit: Int, offset: Int, sortBy: TrackSortOrder, favoritesOnly: Boolean, searchTerm: String?): List<Track> {
        val favs = trackFavorites()
        val term = searchTerm?.takeIf { it.isNotBlank() }
        return rows
            .let { all -> if (favoritesOnly) all.filter { it.id in favs } else all }
            .let { all -> if (term != null) all.filter { it.matchesSearch(term) } else all }
            .sortedForBrowse(sortBy).drop(offset).take(limit).map { it.toTrack(favs) }
    }

    override suspend fun getTracksForPlaylist(playlistId: String): List<Track> = emptyList()

    override suspend fun getAlbumsForArtist(artistId: String): List<Album> {
        val albumIds = rows.filter { artistId in it.artistIds || it.albumArtistId == artistId }
            .mapNotNull { it.albumId }.toSet()
        return albumIds.mapNotNull { albumsById[it] }.sortedByDescending { it.year ?: 0 }
    }

    override suspend fun getAlbumsForGenre(genreId: String): List<Album> {
        val albumIds = rows.filter { genreId in it.genreIds }.mapNotNull { it.albumId }.toSet()
        return albumIds.mapNotNull { albumsById[it] }.sortedBy { it.name.lowercase() }
    }

    override suspend fun getAlbums(sortBy: AlbumSortOrder, limit: Int, offset: Int): List<Album> {
        // The Album model carries no date-added / play-count, but the StoredTrack rows do — so derive
        // RecentlyAdded/MostPlayed ordering by aggregating each album's rows (newest lastModified /
        // summed playCount). The other orderings are expressible straight off the derived Album.
        val byAlbum by lazy { rows.filter { it.albumId != null }.groupBy { it.albumId!! } }
        val ordered = when (sortBy) {
            AlbumSortOrder.Alphabetical  -> albumsById.values.sortedBy { it.name.lowercase() }
            AlbumSortOrder.ReleaseDate   -> albumsById.values.sortedByDescending { it.year ?: 0 }
            AlbumSortOrder.Random        -> albumsById.values.shuffled()
            AlbumSortOrder.RecentlyAdded -> albumsById.values.sortedByDescending { a ->
                byAlbum[a.id]?.maxOfOrNull { it.lastModified } ?: 0L
            }
            AlbumSortOrder.MostPlayed    -> albumsById.values.sortedByDescending { a ->
                byAlbum[a.id]?.sumOf { it.playCount } ?: 0
            }
        }
        return ordered.drop(offset).take(limit)
    }

    override suspend fun getTracksByIds(ids: List<String>): List<Track> =
        ids.mapNotNull { id -> store.get(id)?.toTrack() }

    // Local files are served as-is; there's no transcoding, so [quality] is ignored.
    override suspend fun getStreamUrl(trackId: String, quality: StreamingQuality?): String {
        val row = store.get(trackId) ?: error("Unknown track $trackId")
        // Desktop stores absolute file paths (→ file:// URI); Android stores content:// URIs already
        // playable by ExoPlayer, which we pass through unchanged.
        return if ("://" in row.path) row.path else File(row.path).toURI().toString()
    }

    override suspend fun getArtworkUrl(trackId: String): String? = store.get(trackId)?.artworkPath

    override suspend fun getLyrics(trackId: String): Lyrics {
        val row = store.get(trackId) ?: return Lyrics.None

        // 1. Sidecar .lrc next to the audio file (same basename). Preferred, as a sidecar is usually
        // curated/synced. Platform-agnostic: desktop reads the sibling File, Android the sibling SAF
        // document (see readSidecarLyrics). The text may be timestamped → parseLrc yields Timed.
        val sidecar = runCatching { readSidecarLyrics(row.path) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (sidecar != null) {
            val parsed = parseLrc(sidecar)
            if (parsed != Lyrics.None) return parsed
        }

        // 2. Embedded lyric tag (USLT/LYRICS). The raw text may itself be LRC-formatted, so run it
        // through parseLrc — timestamps → Timed, plain text → Unsynced. Null on platforms without an
        // embedded-lyric reader (Android today).
        val embedded = runCatching { readEmbeddedLyrics(row.path) }.getOrNull()?.takeIf { it.isNotBlank() }
        return embedded?.let { parseLrc(it) } ?: Lyrics.None
    }

    // Local favourites cover tracks, albums, artists and genres (persisted in AppPreferences; the index
    // stays favourite-agnostic). Playlists aren't favouritable here — there are no local playlists yet.
    override fun supportsFavorites(kind: FavoritableKind): Boolean =
        kind == FavoritableKind.Track || kind == FavoritableKind.Album ||
        kind == FavoritableKind.Artist || kind == FavoritableKind.Genre

    override suspend fun isFavorite(kind: FavoritableKind, id: String): Boolean =
        id in AppPreferences.localFavorites(kind.name)

    override suspend fun setFavorite(kind: FavoritableKind, id: String, favorite: Boolean) {
        AppPreferences.setLocalFavorite(kind.name, id, favorite)
        // Album/artist/genre cards read `favorite` off the model, so re-derive to reflect the change in
        // the grids. Track hearts are read per-query via trackFavorites(), so they need no re-derive.
        if (kind != FavoritableKind.Track) deriveAndEmit(rows)
    }

    // --- Folder management (driven by the local-library settings UI) -------------------------------

    fun addFolder(path: String) {
        if (path.isBlank() || path in _folders.value) return
        val updated = _folders.value + path
        _folders.value = updated
        AppPreferences.setLocalFolders(updated)
        scope.launch { runCatching { refresh() } }
    }

    fun removeFolder(path: String) {
        val updated = _folders.value - path
        _folders.value = updated
        AppPreferences.setLocalFolders(updated)
        scope.launch { runCatching { refresh() } }
    }

    /** Manual rescan, wired to the settings "rescan" action and pull-to-refresh. */
    fun rescan() {
        scope.launch { runCatching { refresh(forceAll = false) } }
    }

    /** Full rebuild: wipes the artwork cache and re-reads every file's tags from scratch. */
    fun fullRescan() {
        scope.launch { runCatching { refresh(forceAll = true) } }
    }

    /** Re-apply the "skip tracks without metadata" filter from the full index, with no rescan. Wired
     * to the settings toggle so hiding/showing metadata-less tracks is instant. */
    fun reapplyMetadataFilter() {
        deriveAndEmit(store.all(SOURCE_KEY))
    }

    // Favourites live in preferences (the index stays favourite-agnostic); snapshot once per query
    // rather than re-parsing the preference string for every row.
    private fun trackFavorites(): Set<String> = AppPreferences.localFavorites(FavoritableKind.Track.name)

    private fun StoredTrack.toTrack(favorites: Set<String> = trackFavorites()) = Track(
        id = id,
        name = title,
        albumId = albumId,
        album = album,
        artists = artists,
        // The Track model carries duration as Jellyfin-style 100-ns ticks; LocalPlayer divides back
        // by 10_000 to get ms, so store ms * 10_000 here for a consistent round-trip.
        durationTicks = durationMs?.let { it * 10_000 },
        trackNumber = trackNumber,
        discNumber = discNumber,
        imageUrl = artworkPath,
        favorite = id in favorites,
        playCount = playCount,
    )

    /**
     * Record a completed play of [trackId]: bump the row's play count and last-played time so offline
     * sort-by-plays / sort-by-recently-played reflect it. Updates the store and the in-memory mirror so
     * browse queries see it without a rescan.
     */
    fun recordPlay(trackId: String) {
        val row = store.get(SOURCE_KEY, trackId) ?: return
        val updated = row.copy(playCount = row.playCount + 1, lastPlayedAt = System.currentTimeMillis())
        store.put(updated)
        rows = rows.map { if (it.id == trackId) updated else it }
    }

    companion object {
        const val SOURCE_KEY = "local"
        private const val UNKNOWN_ALBUM = "Unknown Album"
        private const val UNKNOWN_ARTIST = "Unknown Artist"
        private const val VARIOUS_ARTISTS = "Various Artists"

        /** Build a [LocalSource] over [store] (the shared SQL index) with an artwork cache under the app
         *  data dir. The store is supplied by [net.mhanak.yama.AppContainer] so the local scan and
         *  downloads share one database, partitioned by `sourceKey`. */
        fun create(store: LocalLibraryStore): LocalSource {
            // Path.toString() + File(String) avoids Path.toFile() (API 26+) for parity with minSdk 24.
            val dataDir = File(getAppDataDir().toString())
            return LocalSource(
                store = store,
                artworkDir = File(dataDir, "local_artwork"),
            )
        }
    }
}

/** In-source track search: match the term against the title, album name, or any credited artist —
 *  parity with Subsonic's `search3`, which spans the same fields (the old filter matched title only). */
private fun StoredTrack.matchesSearch(term: String): Boolean =
    title.contains(term, ignoreCase = true) ||
        album?.contains(term, ignoreCase = true) == true ||
        artists.any { it.contains(term, ignoreCase = true) }

private fun List<StoredTrack>.sortedForBrowse(sortBy: TrackSortOrder): List<StoredTrack> = when (sortBy) {
    TrackSortOrder.Alphabetical -> sortedBy { it.title.lowercase() }
    TrackSortOrder.ReleaseDate -> sortedWith(compareByDescending<StoredTrack> { it.year ?: 0 }.thenBy { it.title.lowercase() })
    TrackSortOrder.RecentlyAdded -> sortedByDescending { it.lastModified }
    // Play count + last-played are tracked on the row (bumped on each completed play via recordPlay).
    TrackSortOrder.PlayCount -> sortedWith(compareByDescending<StoredTrack> { it.playCount }.thenBy { it.title.lowercase() })
    TrackSortOrder.RecentlyPlayed -> sortedByDescending { it.lastPlayedAt ?: 0L }
    TrackSortOrder.Random -> shuffled()
}

/** Stable, collision-resistant ID from [parts] (SHA-256, truncated to 32 hex chars). */
private fun hashId(vararg parts: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString(" ").encodeToByteArray())
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        .take(32)
