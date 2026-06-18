package net.mhanak.yama.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform SQLite driver for the offline index. Android = `AndroidSqliteDriver` (schema create/migrate
 * handled by the framework); JVM desktop = `JdbcSqliteDriver` at `<appData>/yama.db`. Mirrors the
 * existing `getAppDataDir` / `isNetworkUnmetered` expect/actual pattern.
 */
expect fun createDatabaseDriver(): SqlDriver

/** The shared database handle, built once and passed to the SQLDelight-backed stores. */
fun createYamaDatabase(): YamaDatabase = YamaDatabase(createDatabaseDriver())
