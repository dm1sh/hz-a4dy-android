// SPDX-License-Identifier: MIT
package app.a4dy.print

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.a4dy.print.ui.*

class MainActivity : ComponentActivity() {
    private val model by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if (savedInstanceState == null) accept(intent)
        setContent {
            val dynamic = runtime.store.dynamicColors.collectAsStateWithLifecycle()
            A4Theme(dynamic.value) { A4App(model) }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); accept(intent) }
    @Suppress("DEPRECATION") private fun accept(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) model.import(listOf(uri))
                else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { model.text(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { model.import(it) }
            Intent.ACTION_VIEW -> intent.data?.let { model.import(listOf(it)) }
        }
    }
}
