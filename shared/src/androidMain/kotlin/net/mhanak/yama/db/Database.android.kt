package net.mhanak.yama.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.mhanak.yama.MyApplication

actual fun createDatabaseDriver(): SqlDriver =
    AndroidSqliteDriver(YamaDatabase.Schema, MyApplication.appContext, "yama.db")
