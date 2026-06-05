package net.mhanak.yama.util

expect class SecureStorage(name: String) {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}
