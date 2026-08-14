package net.mhanak.yama.platform

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

// ── D-Bus wire types ──────────────────────────────────────────────────────────────
// dbus-java reads @Position-annotated *fields* by reflection (Container.setup), so on Kotlin
// constructor properties the annotation must target the backing field via @field:Position.

/** One tray-icon bitmap: width, height, ARGB32 bytes (network byte order). D-Bus: `(iiay)`. */
class IconPixmap(
    @field:Position(0) val width: Int,
    @field:Position(1) val height: Int,
    @field:Position(2) val data: ByteArray,
) : Struct()

/** SNI `ToolTip` property. D-Bus: `(sa(iiay)ss)` = iconName, iconPixmaps, title, description. */
class ToolTip(
    @field:Position(0) val iconName: String,
    @field:Position(1) val iconPixmap: List<IconPixmap>,
    @field:Position(2) val title: String,
    @field:Position(3) val description: String,
) : Struct()

/** A dbusmenu node: id, properties, children (each child is a `v` wrapping another MenuLayout).
 *  D-Bus: `(ia{sv}av)`. */
class MenuLayout(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
    @field:Position(2) val children: List<Variant<*>>,
) : Struct()

/** dbusmenu `GetLayout` return: revision + root layout. D-Bus out args: `u (ia{sv}av)`.
 *  Must be generic: dbus-java reads the *type parameters* of a Tuple return to build the signature. */
class GetLayoutTuple<A, B>(
    @field:Position(0) val revision: A,
    @field:Position(1) val layout: B,
) : Tuple()

/** dbusmenu `GetGroupProperties` element: id + properties. D-Bus: `(ia{sv})`. */
class MenuItemProps(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
) : Struct()

/** dbusmenu `EventGroup` element: id, eventId, data, timestamp. D-Bus: `(isvu)`. */
class MenuEventStruct(
    @field:Position(0) val id: Int,
    @field:Position(1) val eventId: String,
    @field:Position(2) val data: Variant<*>,
    @field:Position(3) val timestamp: UInt32,
) : Struct()

/** dbusmenu `AboutToShowGroup` return: updatesNeeded, idErrors. D-Bus out args: `ai ai`. */
class AboutToShowGroupTuple<A, B>(
    @field:Position(0) val updatesNeeded: A,
    @field:Position(1) val idErrors: B,
) : Tuple()

// ── Remote interface (the host's watcher) ──────────────────────────────────────────

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

// ── Exported interfaces (implemented by us) ────────────────────────────────────────

@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    fun ContextMenu(x: Int, y: Int)
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)

    /** Emitted so a host re-reads our (static) icon; declared for completeness. */
    class NewIcon(path: String) : DBusSignal(path)
    class NewStatus(path: String, status: String) : DBusSignal(path, status)
}

@DBusInterfaceName("com.canonical.dbusmenu")
interface DBusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): GetLayoutTuple<UInt32, MenuLayout>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProps>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEventStruct>): List<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: List<Int>): AboutToShowGroupTuple<List<Int>, List<Int>>

    /** Tells the host our menu changed (id labels), so it re-fetches the layout. */
    class LayoutUpdated(path: String, revision: UInt32, parent: Int) : DBusSignal(path, revision, parent)
}

// Small helpers for building menu object paths without leaking DBusPath everywhere.
internal fun objectPath(path: String) = DBusPath(path)
