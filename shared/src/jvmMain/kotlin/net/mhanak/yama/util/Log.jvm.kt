package net.mhanak.yama.util

import org.slf4j.LoggerFactory

actual fun logger(name: String): Logger {
    // Prefix with the app package so logback.xml's `net.mhanak.yama` root logger picks it up and
    // package-level filters (e.g. `<logger name="net.mhanak.yama.Playback" level="DEBUG"/>`) work.
    val delegate = LoggerFactory.getLogger("net.mhanak.yama.$name")
    return object : Logger {
        override fun debug(message: String, throwable: Throwable?) =
            if (throwable != null) delegate.debug(message, throwable) else delegate.debug(message)
        override fun info(message: String, throwable: Throwable?) =
            if (throwable != null) delegate.info(message, throwable) else delegate.info(message)
        override fun warn(message: String, throwable: Throwable?) =
            if (throwable != null) delegate.warn(message, throwable) else delegate.warn(message)
        override fun error(message: String, throwable: Throwable?) =
            if (throwable != null) delegate.error(message, throwable) else delegate.error(message)
    }
}
