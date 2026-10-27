package com.v2ray.ang.dash

import android.content.Context
import com.v2ray.ang.AngApplication
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Режим админа + синхронизация списка серверов с kvdb.io.
 *
 *  - обычный пользователь: при старте приложения список скачивается и ПЕРЕЗАПИСЫВАЕТ локальные профили;
 *  - админ: добавляет / удаляет серверы, список отправляется POST-ом в облако для всех.
 */
object DashManager {
    private const val PREFS = "dash_prefs"
    private const val KEY_ADMIN = "is_admin"

    private val prefs by lazy {
        AngApplication.application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val _isAdmin by lazy { MutableStateFlow(prefs.getBoolean(KEY_ADMIN, false)) }
    val isAdmin: StateFlow<Boolean> get() = _isAdmin.asStateFlow()

    private val mutex = Mutex()

    fun login(password: String): Boolean {
        if (password != DashConfig.ADMIN_PASSWORD) return false
        prefs.edit().putBoolean(KEY_ADMIN, true).apply()
        _isAdmin.value = true
        return true
    }

    fun logout() {
        prefs.edit().putBoolean(KEY_ADMIN, false).apply()
        _isAdmin.value = false
    }

    /** Текущий список из облака (для панели админа). */
    suspend fun fetchCloud(): List<DashServer> = withContext(Dispatchers.IO) { DashApi.fetch() }

    /**
     * Запуск приложения: скачать список и перезаписать локальные профили.
     * Если облако пустое или недоступно (исключение) — локальные профили не трогаются.
     * @return сколько серверов в облачном списке
     */
    suspend fun syncFromCloud(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val servers = DashApi.fetch()
            if (servers.isNotEmpty()) applyLocally(servers)
            servers.size
        }
    }

    /**
     * Админ: добавить сервер. Сначала ключ проверяется ядром приложения (через локальный импорт),
     * и только если он распознан — список уходит в облако.
     * @return новый список
     */
    suspend fun addServer(server: DashServer): List<DashServer> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val key = server.key.trim()
            require(key.contains("://")) { "Ключ должен начинаться с vless://, vmess://, hysteria2:// и т.п." }
            require(!key.startsWith("http://", true) && !key.startsWith("https://", true)) {
                "Ссылки http(s) (подписки) не поддерживаются, вставьте сам ключ сервера"
            }
            val next = DashApi.fetch().filter { it.key != key } + server.copy(key = key)
            val failed = applyLocally(next)
            require(key !in failed) { "Приложение не распознало этот ключ. Проверьте ссылку." }
            DashApi.push(next)
            next
        }
    }

    /** Админ: удалить сервер из облака (и локально). @return новый список */
    suspend fun removeServer(server: DashServer): List<DashServer> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = DashApi.fetch().filter { it.key != server.key }
            DashApi.push(next)
            if (next.isNotEmpty()) applyLocally(next) else MmkvManager.decodeAllServerList().toList().forEach { MmkvManager.removeServer(it) }
            next
        }
    }

    /**
     * Создаёт профили из [servers] и только после этого удаляет все старые
     * (если ни один ключ не импортировался, старые профили остаются).
     * Прежний выбранный сервер сохраняется, если он есть в новом списке.
     * @return ключи, которые приложение не смогло импортировать
     */
    private fun applyLocally(servers: List<DashServer>): List<String> {
        val old = MmkvManager.decodeAllServerList().toList()
        val previousRemarks = MmkvManager.getSelectServer()
            ?.let { MmkvManager.decodeServerConfig(it)?.remarks }

        val created = mutableListOf<Pair<String, DashServer>>()
        val failed = mutableListOf<String>()

        for (s in servers) {
            val key = s.key.trim()
            val before = MmkvManager.decodeAllServerList().toSet()
            val count = try {
                AngConfigManager.importBatchConfig(key, "", true).first
            } catch (e: Exception) {
                0
            }
            val guid = MmkvManager.decodeAllServerList().firstOrNull { it !in before }
            if (count > 0 && guid != null) {
                MmkvManager.decodeServerConfig(guid)?.let { profile ->
                    profile.remarks = s.displayName()
                    MmkvManager.encodeServerConfig(guid, profile)
                }
                created += guid to s
            } else {
                failed += s.key
            }
        }

        if (created.isEmpty()) return failed

        old.forEach { MmkvManager.removeServer(it) }
        val target = created.firstOrNull { it.second.displayName() == previousRemarks } ?: created.first()
        MmkvManager.setSelectServer(target.first)
        return failed
    }
}
