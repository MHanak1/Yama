package net.mhanak.yama.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.mhanak.yama.getAppDataDir
import java.io.File

actual fun createDatabaseDriver(): SqlDriver {
    val dbFile = File(getAppDataDir().toString(), "yama.db")
    dbFile.parentFile?.mkdirs()
    // JdbcSqliteDriver creates the file on connect, so capture existence first to know whether the
    // schema still needs creating (Android's driver does this from the Schema automatically).
    val existed = dbFile.exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    val schema = YamaDatabase.Schema
    if (!existed) {
        schema.create(driver)
        driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
    } else {
        // Android's driver tracks the applied version automatically; JdbcSqliteDriver doesn't, so we
        // run pending .sqm migrations by hand. Databases created before user_version was tracked report
        // 0 yet already hold the v1 schema, so floor the start at 1 (never re-create over them).
        val current = driver.executeQuery(
            null, "PRAGMA user_version", { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0,
        ).value
        val from = current.coerceAtLeast(1L)
        if (from < schema.version) {
            schema.migrate(driver, from, schema.version)
            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
        }
    }
    return driver
}
