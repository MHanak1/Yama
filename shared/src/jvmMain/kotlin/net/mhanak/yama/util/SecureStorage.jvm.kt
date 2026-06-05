package net.mhanak.yama.util

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.platform.win32.Crypt32
import com.sun.jna.platform.win32.WinCrypt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import kotlin.io.path.*

actual class SecureStorage actual constructor(name: String) {
    private val backend: Backend = buildBackend(name)

    actual fun getString(key: String): String? = backend.getString(key)
    actual fun putString(key: String, value: String) = backend.putString(key, value)
    actual fun remove(key: String) = backend.remove(key)
}

// ---------------------------------------------------------------------------
// Backend interface
// ---------------------------------------------------------------------------

private interface Backend {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

private fun buildBackend(name: String): Backend {
    val os = System.getProperty("os.name", "").lowercase()
    return when {
        os.contains("linux") ->
            LinuxLibsecretBackend(name).takeIf { it.isAvailable() }
                ?: PlainFileBackend(name)
        os.contains("windows") ->
            WindowsDpapiBackend(name)
        else ->
            PlainFileBackend(name)
    }
}

// ---------------------------------------------------------------------------
// Linux — libsecret (Secret Service / GNOME Keyring / KDE Wallet)
// ---------------------------------------------------------------------------

private interface LibSecret : Library {
    fun secret_schema_new(
        name: String, flags: Int,
        attrName: String, attrType: Int,
        terminator: Pointer?,
    ): Pointer

    fun secret_password_store_sync(
        schema: Pointer, collection: String?, label: String, password: String,
        cancellable: Pointer?, error: PointerByReference?,
        attrName: String, attrValue: String, terminator: Pointer?,
    ): Boolean

    fun secret_password_lookup_sync(
        schema: Pointer,
        cancellable: Pointer?, error: PointerByReference?,
        attrName: String, attrValue: String, terminator: Pointer?,
    ): Pointer?

    fun secret_password_clear_sync(
        schema: Pointer,
        cancellable: Pointer?, error: PointerByReference?,
        attrName: String, attrValue: String, terminator: Pointer?,
    ): Boolean

    fun secret_password_free(password: Pointer?)
}

private class LinuxLibsecretBackend(private val namespace: String) : Backend {
    private val lib: LibSecret? = runCatching {
        Native.load("secret-1", LibSecret::class.java)
    }.getOrNull()

    // SECRET_SCHEMA_NONE = 0, SECRET_SCHEMA_ATTRIBUTE_STRING = 0
    private val schema: Pointer? = lib?.runCatching {
        secret_schema_new("net.mhanak.yama", 0, "key", 0, null)
    }?.getOrNull()

    fun isAvailable(): Boolean = lib != null && schema != null

    override fun getString(key: String): String? {
        val lib = lib ?: return null
        val schema = schema ?: return null
        val ptr = lib.secret_password_lookup_sync(schema, null, null, "key", "$namespace/$key", null)
            ?: return null
        return try {
            ptr.getString(0)
        } finally {
            lib.secret_password_free(ptr)
        }
    }

    override fun putString(key: String, value: String) {
        val lib = lib ?: return
        val schema = schema ?: return
        lib.secret_password_store_sync(
            schema, null, "Yama: $namespace/$key", value,
            null, null,
            "key", "$namespace/$key", null,
        )
    }

    override fun remove(key: String) {
        val lib = lib ?: return
        val schema = schema ?: return
        lib.secret_password_clear_sync(schema, null, null, "key", "$namespace/$key", null)
    }
}

// ---------------------------------------------------------------------------
// Windows — DPAPI (user-session-bound encryption) + JSON file
// Any process running as the same Windows user can call CryptUnprotectData;
// that is the documented DPAPI security boundary, not a limitation of this impl.
// ---------------------------------------------------------------------------

private interface KernelLocalFree : Library {
    fun LocalFree(hMem: Pointer?): Pointer?
}

private class WindowsDpapiBackend(private val namespace: String) : Backend {
    private val configDir = Path.of(
        System.getenv("APPDATA") ?: System.getProperty("user.home"), "yama"
    )
    private val dataFile = configDir.resolve("$namespace.json")
    private val localFree: KernelLocalFree = Native.load("kernel32", KernelLocalFree::class.java)

    private fun dpApiEncrypt(plaintext: ByteArray): ByteArray {
        val inputData = Memory(plaintext.size.toLong())
        inputData.write(0, plaintext, 0, plaintext.size)
        val input = WinCrypt.DATA_BLOB().also {
            it.cbData = plaintext.size
            it.pbData = inputData
            it.write()
        }
        val output = WinCrypt.DATA_BLOB()
        check(Crypt32.INSTANCE.CryptProtectData(input, null, null, null, null, 0, output)) {
            "CryptProtectData failed"
        }
        output.read()
        return output.pbData.getByteArray(0, output.cbData).also { localFree.LocalFree(output.pbData) }
    }

    private fun dpApiDecrypt(ciphertext: ByteArray): ByteArray? {
        val inputData = Memory(ciphertext.size.toLong())
        inputData.write(0, ciphertext, 0, ciphertext.size)
        val input = WinCrypt.DATA_BLOB().also {
            it.cbData = ciphertext.size
            it.pbData = inputData
            it.write()
        }
        val output = WinCrypt.DATA_BLOB()
        if (!Crypt32.INSTANCE.CryptUnprotectData(input, null, null, null, null, 0, output)) return null
        output.read()
        return output.pbData.getByteArray(0, output.cbData).also { localFree.LocalFree(output.pbData) }
    }

    private fun loadStore(): MutableMap<String, String> {
        if (!dataFile.exists()) return mutableMapOf()
        return runCatching {
            Json.decodeFromString<Map<String, String>>(dataFile.readText()).toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun saveStore(store: Map<String, String>) {
        configDir.createDirectories()
        dataFile.writeText(Json.encodeToString(store))
    }

    override fun getString(key: String): String? {
        val encoded = loadStore()[key] ?: return null
        val ciphertext = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        val plaintext = dpApiDecrypt(ciphertext) ?: return null
        return String(plaintext, Charsets.UTF_8)
    }

    override fun putString(key: String, value: String) {
        val store = loadStore()
        store[key] = Base64.getEncoder().encodeToString(dpApiEncrypt(value.toByteArray(Charsets.UTF_8)))
        saveStore(store)
    }

    override fun remove(key: String) {
        val store = loadStore()
        store.remove(key)
        saveStore(store)
    }
}

// ---------------------------------------------------------------------------
// Plain-text fallback — used on Linux when libsecret is unavailable (headless)
// Tokens are stored unencrypted. File permissions are set to 600 where possible.
// ---------------------------------------------------------------------------

private class PlainFileBackend(private val namespace: String) : Backend {
    private val log = LoggerFactory.getLogger("net.mhanak.yama.SecureStorage")

    private val configDir: Path = run {
        val os = System.getProperty("os.name", "").lowercase()
        when {
            os.contains("linux") -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                    ?: "${System.getProperty("user.home")}/.config"
                Path.of(xdg, "yama")
            }
            else -> Path.of(System.getProperty("user.home"), ".yama")
        }
    }
    private val dataFile = configDir.resolve("$namespace.json")

    init {
        log.warn(
            "No secure storage backend available — '{}' tokens will be stored in plain text at '{}'." +
                " Install libsecret (e.g. libsecret-1-0) to enable keychain storage.",
            namespace,
            dataFile.toAbsolutePath(),
        )
    }

    private fun loadStore(): MutableMap<String, String> {
        if (!dataFile.exists()) return mutableMapOf()
        return runCatching {
            Json.decodeFromString<Map<String, String>>(dataFile.readText()).toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun saveStore(store: Map<String, String>) {
        configDir.createDirectories()
        dataFile.writeText(Json.encodeToString(store))
        // Restrict to owner read/write where the OS supports POSIX permissions.
        runCatching {
            dataFile.setPosixFilePermissions(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }
    }

    override fun getString(key: String): String? = loadStore()[key]

    override fun putString(key: String, value: String) {
        val store = loadStore()
        store[key] = value
        saveStore(store)
    }

    override fun remove(key: String) {
        val store = loadStore()
        store.remove(key)
        saveStore(store)
    }
}
