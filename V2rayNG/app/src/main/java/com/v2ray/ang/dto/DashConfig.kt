package com.v2ray.ang.dto

/** Настройки облачной синхронизации списка серверов. */
object DashConfig {
    /**
     * kvdb.io хранит данные по схеме bucket/key. Запрос на сам bucket
     * (…/VE7aHLba4AZQnZmNKCq5Vz/) вернёт список ключей, а не данные, поэтому
     * список серверов лежит под ключом "servers".
     * GET  -> скачать JSON, POST -> перезаписать JSON.
     */
    const val SERVERS_URL = "https://kvdb.io/VE7aHLba4AZQnZmNKCq5Vz/servers"

    /**
     * Пароль админа. ВНИМАНИЕ: он зашит в приложение, любой может достать его из APK.
     * Это защита от случайных нажатий, а не настоящая защита.
     */
    const val ADMIN_PASSWORD = "1234"

    const val TIMEOUT_MS = 15_000
}
