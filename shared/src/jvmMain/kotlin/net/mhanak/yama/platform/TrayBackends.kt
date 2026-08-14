package net.mhanak.yama.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import net.mhanak.yama.util.logger
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

// Menu item ids used across the dbusmenu layout and Event dispatch.
private const val ID_ROOT = 0
private const val ID_SHOW_HIDE = 1
private const val ID_QUIT = 2
private const val ID_PREVIOUS = 3
private const val ID_PLAYPAUSE = 4
private const val ID_NEXT = 5
private const val ID_SEP_TRANSPORT = 10
private const val ID_SEP_QUIT = 11

private const val ITEM_PATH = "/StatusNotifierItem"
private const val MENU_PATH = "/MenuBar"

/**
 * StatusNotifierItem tray backend. Exports the item and its dbusmenu on a dedicated session-bus
 * connection and registers with the host's watcher. This is what makes a tray icon appear under
 * Wayland/Hyprland (Waybar), KDE, and GNOME-with-extension — where AWT's X11 tray is unavailable.
 */
internal class SniTray(
    title: String,
    iconResource: String,
    private val _events: MutableSharedFlow<TrayEvent>,
) : TrayHandle {

    private val log = logger("SniTray")
    private val conn: DBusConnection = DBusConnectionBuilder.forSessionBus().build()
    private val revision = AtomicInteger(1)
    @Volatile private var windowVisible = true
    @Volatile private var playback = TrayPlaybackState()

    override val events: SharedFlow<TrayEvent> = _events

    private val pixmaps: List<IconPixmap> = loadPixmaps(iconResource)
    // With a bitmap present we leave IconName empty; otherwise fall back to a themed generic icon.
    private val iconName: String = if (pixmaps.isEmpty()) "audio-x-generic" else ""

    private val itemHandler = ItemHandler(title)
    private val menuHandler = MenuHandler()

    init {
        val busName = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
        conn.requestBusName(busName)
        conn.exportObject(ITEM_PATH, itemHandler)
        conn.exportObject(MENU_PATH, menuHandler)
        val watcher = conn.getRemoteObject(
            "org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher", StatusNotifierWatcher::class.java,
        )
        watcher.RegisterStatusNotifierItem(busName)
        log.info("Registered StatusNotifierItem as $busName")
    }

    override fun setVisibleState(visible: Boolean) {
        if (visible == windowVisible) return
        windowVisible = visible
        signalMenuChanged()
    }

    override fun setPlaybackState(state: TrayPlaybackState) {
        if (state == playback) return
        playback = state
        signalMenuChanged()
    }

    /** Bump the revision and tell the host to re-read the layout so labels/enablement refresh. */
    private fun signalMenuChanged() {
        runCatching {
            conn.sendMessage(DBusMenu.LayoutUpdated(MENU_PATH, UInt32(revision.incrementAndGet().toLong()), ID_ROOT))
        }.onFailure { log.warn("Failed to signal LayoutUpdated", it) }
    }

    override fun dispose() {
        runCatching { conn.close() }
    }

    private fun showHideLabel() = if (windowVisible) "Hide Yama" else "Show Yama"

    private fun item(label: String, enabled: Boolean = true) = mapOf(
        "label" to Variant(label),
        "enabled" to Variant(enabled),
        "visible" to Variant(true),
    )

    private fun separator() = mapOf<String, Variant<*>>(
        "type" to Variant("separator"),
        "visible" to Variant(true),
    )

    private fun menuItemProps(id: Int): Map<String, Variant<*>> = when (id) {
        ID_SHOW_HIDE -> item(showHideLabel())
        ID_PREVIOUS -> item("Previous", enabled = playback.canGoPrevious)
        ID_PLAYPAUSE -> item(if (playback.isPlaying) "Pause" else "Play", enabled = playback.hasTrack)
        ID_NEXT -> item("Next", enabled = playback.canGoNext)
        ID_QUIT -> item("Quit")
        ID_SEP_TRANSPORT, ID_SEP_QUIT -> separator()
        else -> mapOf("children-display" to Variant("submenu"))
    }

    // Order shown in the tray menu.
    private val childIds = listOf(
        ID_SHOW_HIDE, ID_SEP_TRANSPORT, ID_PREVIOUS, ID_PLAYPAUSE, ID_NEXT, ID_SEP_QUIT, ID_QUIT,
    )

    private fun leaf(id: Int) = MenuLayout(id, menuItemProps(id), emptyList())

    private fun layoutFor(parentId: Int): MenuLayout = when (parentId) {
        ID_ROOT -> MenuLayout(
            ID_ROOT,
            menuItemProps(ID_ROOT),
            childIds.map { Variant(leaf(it), "(ia{sv}av)") },
        )
        else -> leaf(parentId)
    }

    // ── org.kde.StatusNotifierItem (+ Properties) at /StatusNotifierItem ──
    private inner class ItemHandler(private val title: String) : StatusNotifierItem, Properties {
        override fun isRemote() = false
        override fun getObjectPath() = ITEM_PATH

        override fun ContextMenu(x: Int, y: Int) {}                // host renders the dbusmenu itself
        override fun Activate(x: Int, y: Int) { _events.tryEmit(TrayEvent.ToggleVisibility) }
        override fun SecondaryActivate(x: Int, y: Int) { _events.tryEmit(TrayEvent.ToggleVisibility) }
        override fun Scroll(delta: Int, orientation: String) {}

        private fun props(): Map<String, Variant<*>> = mapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("yama"),
            "Title" to Variant(title),
            "Status" to Variant("Active"),
            "WindowId" to Variant(UInt32(0)),
            "IconName" to Variant(iconName),
            "IconPixmap" to Variant(pixmaps, "a(iiay)"),
            "OverlayIconName" to Variant(""),
            "AttentionIconName" to Variant(""),
            "AttentionMovieName" to Variant(""),
            "ToolTip" to Variant(ToolTip("", emptyList(), title, ""), "(sa(iiay)ss)"),
            "ItemIsMenu" to Variant(false),
            "Menu" to Variant(objectPath(MENU_PATH), "o"),
        )

        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A =
            (props()[propertyName] ?: Variant("")) as A

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = props()

        override fun <A> Set(interfaceName: String, propertyName: String, value: A) {}
    }

    // ── com.canonical.dbusmenu (+ Properties) at /MenuBar ──
    private inner class MenuHandler : DBusMenu, Properties {
        override fun isRemote() = false
        override fun getObjectPath() = MENU_PATH

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): GetLayoutTuple<UInt32, MenuLayout> =
            GetLayoutTuple(UInt32(revision.get().toLong()), layoutFor(parentId))

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProps> =
            ids.map { MenuItemProps(it, menuItemProps(it)) }

        @Suppress("UNCHECKED_CAST")
        override fun GetProperty(id: Int, name: String): Variant<*> =
            menuItemProps(id)[name] ?: Variant("")

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            when (id) {
                ID_SHOW_HIDE -> _events.tryEmit(TrayEvent.ToggleVisibility)
                ID_PREVIOUS -> _events.tryEmit(TrayEvent.Previous)
                ID_PLAYPAUSE -> _events.tryEmit(TrayEvent.PlayPause)
                ID_NEXT -> _events.tryEmit(TrayEvent.Next)
                ID_QUIT -> _events.tryEmit(TrayEvent.Quit)
            }
        }

        override fun EventGroup(events: List<MenuEventStruct>): List<Int> {
            events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            return emptyList()
        }

        override fun AboutToShow(id: Int): Boolean = false

        override fun AboutToShowGroup(ids: List<Int>): AboutToShowGroupTuple<List<Int>, List<Int>> =
            AboutToShowGroupTuple(emptyList(), emptyList())

        private fun props(): Map<String, Variant<*>> = mapOf(
            "Version" to Variant(UInt32(3)),
            "Status" to Variant("normal"),
            "TextDirection" to Variant("ltr"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )

        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A =
            (props()[propertyName] ?: Variant("")) as A

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = props()

        override fun <A> Set(interfaceName: String, propertyName: String, value: A) {}
    }
}

/** AWT `SystemTray` backend — the fallback for Windows, macOS and X11 (where AWT's tray works). */
internal class AwtTray(
    title: String,
    iconResource: String,
    private val _events: MutableSharedFlow<TrayEvent>,
) : TrayHandle {

    override val events: SharedFlow<TrayEvent> = _events

    private val tray = SystemTray.getSystemTray()
    private val showHide = MenuItem("Hide Yama")
    private val previous = MenuItem("Previous")
    private val playPause = MenuItem("Play")
    private val next = MenuItem("Next")
    private val trayIcon: TrayIcon

    init {
        val quit = MenuItem("Quit")
        val popup = PopupMenu().apply {
            add(showHide)
            addSeparator()
            add(previous); add(playPause); add(next)
            addSeparator()
            add(quit)
        }
        showHide.addActionListener { _events.tryEmit(TrayEvent.ToggleVisibility) }
        previous.addActionListener { _events.tryEmit(TrayEvent.Previous) }
        playPause.addActionListener { _events.tryEmit(TrayEvent.PlayPause) }
        next.addActionListener { _events.tryEmit(TrayEvent.Next) }
        quit.addActionListener { _events.tryEmit(TrayEvent.Quit) }
        trayIcon = TrayIcon(loadAwtImage(iconResource), title, popup).apply {
            isImageAutoSize = true
            addActionListener { _events.tryEmit(TrayEvent.ToggleVisibility) }  // primary click
        }
        tray.add(trayIcon)
    }

    override fun setVisibleState(visible: Boolean) {
        showHide.label = if (visible) "Hide Yama" else "Show Yama"
    }

    override fun setPlaybackState(state: TrayPlaybackState) {
        playPause.label = if (state.isPlaying) "Pause" else "Play"
        playPause.isEnabled = state.hasTrack
        previous.isEnabled = state.canGoPrevious
        next.isEnabled = state.canGoNext
    }

    override fun dispose() {
        runCatching { tray.remove(trayIcon) }
    }
}

// ── Icon helpers ────────────────────────────────────────────────────────────────────

private fun loadImage(resource: String): BufferedImage? =
    DesktopTray::class.java.classLoader.getResourceAsStream(resource)?.use { ImageIO.read(it) }

private fun loadAwtImage(resource: String): java.awt.Image =
    loadImage(resource) ?: BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)

/** Load the app icon and convert to a single ARGB32 pixmap (network byte order) for SNI. */
private fun loadPixmaps(resource: String): List<IconPixmap> {
    val src = loadImage(resource) ?: return emptyList()
    val size = 32
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    img.createGraphics().apply {
        setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        drawImage(src, 0, 0, size, size, null)
        dispose()
    }
    val bytes = ByteArray(size * size * 4)
    var i = 0
    for (y in 0 until size) {
        for (x in 0 until size) {
            val argb = img.getRGB(x, y)
            bytes[i++] = ((argb ushr 24) and 0xff).toByte()  // A
            bytes[i++] = ((argb ushr 16) and 0xff).toByte()  // R
            bytes[i++] = ((argb ushr 8) and 0xff).toByte()   // G
            bytes[i++] = (argb and 0xff).toByte()            // B
        }
    }
    return listOf(IconPixmap(size, size, bytes))
}
