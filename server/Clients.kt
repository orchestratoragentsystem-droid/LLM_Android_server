package com.example.llmserver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** priority: 0 — высокий, 1 — обычный, 2 — низкий. Клиент может только понижать свой приоритет. */
class Client(val name: String, val token: String, val priority: Int)

object Clients {
    private fun sp(c: Context) = c.getSharedPreferences("server", Context.MODE_PRIVATE)

    fun all(c: Context): List<Client> {
        val raw = sp(c).getString("clients", null)
        val list = ArrayList<Client>()
        if (raw != null) {
            try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    list += Client(o.getString("name"), o.getString("token"), o.optInt("priority", 1))
                }
            } catch (_: Throwable) {}
        }
        if (list.isEmpty()) {
            val first = Client("default", newToken(), 1)
            save(c, listOf(first))
            return listOf(first)
        }
        return list
    }

    fun add(c: Context, name: String): Client? {
        val n = name.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_").take(32)
        if (n.isEmpty()) return null
        val cur = all(c)
        if (cur.any { it.name == n }) return null
        val cl = Client(n, newToken(), 1)
        save(c, cur + cl)
        return cl
    }

    fun remove(c: Context, name: String) {
        val cur = all(c).filter { it.name != name }
        save(c, if (cur.isEmpty()) listOf(Client("default", newToken(), 1)) else cur)
    }

    fun cyclePriority(c: Context, name: String) {
        save(c, all(c).map { if (it.name == name) Client(it.name, it.token, (it.priority + 1) % 3) else it })
    }

    fun regenerate(c: Context, name: String) {
        save(c, all(c).map { if (it.name == name) Client(it.name, newToken(), it.priority) else it })
    }

    /** Поиск клиента по ключу: сравнение за постоянное время. */
    fun byToken(c: Context, token: String): Client? {
        val t = token.toByteArray()
        var found: Client? = null
        for (cl in all(c)) if (MessageDigest.isEqual(cl.token.toByteArray(), t)) found = cl
        return found
    }

    fun priorityName(p: Int) = when (p) { 0 -> "высокий"; 2 -> "низкий"; else -> "обычный" }

    private fun newToken(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun save(c: Context, list: List<Client>) {
        val a = JSONArray()
        for (cl in list) a.put(JSONObject().put("name", cl.name).put("token", cl.token).put("priority", cl.priority))
        sp(c).edit().putString("clients", a.toString()).apply()
    }
}
