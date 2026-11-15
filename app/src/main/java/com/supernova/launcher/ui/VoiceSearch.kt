package com.supernova.launcher.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.speech.RecognizerIntent
import com.supernova.launcher.runLogged

// A search bar's mic. The speech recognizer only answers a caller waiting
// for a result (started plainly, as the mic used to be, it showed nothing),
// so the launcher listens and types what was said into its own search. Where
// nothing can take the words back, the phone's voice web search opens.
object VoiceSearch {

    // The launcher: it takes the recognizer's answer back and searches it.
    interface Host {
        fun startVoiceSearch()
    }

    fun start(context: Context) {
        host(context)?.startVoiceSearch() ?: webSearch(context)
    }

    // The voice web search of whichever app offers one.
    fun webSearch(context: Context) = runLogged("start voice search") {
        context.startActivity(Intent(RecognizerIntent.ACTION_WEB_SEARCH))
    }

    // What the launcher asks the recognizer for: free speech.
    fun listenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    // The first thing heard, if anything was.
    fun heard(data: Intent?): String? =
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun host(context: Context): Host? {
        var current: Context? = context
        while (current != null) {
            if (current is Host) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }
}
