package com.rekzbanz.livetranscribe

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the stack trace of any crash so the app can show it on the next launch. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE_NAME).writeText("Thread: ${thread.name}\n$sw")
            } catch (e: Throwable) {
                // nothing more we can do
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the saved crash report (and deletes it), or null if there is none. */
    fun take(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            null
        }
        file.delete()
        return text
    }
}
