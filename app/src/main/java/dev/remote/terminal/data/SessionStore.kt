package dev.remote.terminal.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Stores session references only; credentials remain in LocalStore's encrypted vault. */
class SessionStore(context: Context) : SQLiteOpenHelper(context, "terminal_sessions.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, host_id TEXT NOT NULL, directory_id TEXT NOT NULL, agent_id TEXT NOT NULL, display_name TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun save(task: TerminalTask) {
        writableDatabase.insertWithOnConflict("sessions", null, ContentValues().apply {
            put("id", task.id); put("host_id", task.host.id); put("directory_id", task.directory.id)
            put("agent_id", task.agent.id); put("display_name", task.displayName)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(id: String) { writableDatabase.delete("sessions", "id = ?", arrayOf(id)) }

    fun load(hosts: List<HostProfile>, agents: List<AgentPreset>): List<TerminalTask> {
        val result = mutableListOf<TerminalTask>()
        readableDatabase.query("sessions", arrayOf("id", "host_id", "directory_id", "agent_id", "display_name"), null, null, null, null, "rowid ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val host = hosts.firstOrNull { it.id == cursor.getString(1) } ?: continue
                val directory = host.directories.firstOrNull { it.id == cursor.getString(2) } ?: continue
                val agent = agents.firstOrNull { it.id == cursor.getString(3) } ?: continue
                result.add(TerminalTask(cursor.getString(0), host, directory, agent, cursor.getString(4)))
            }
        }
        return result
    }
}
