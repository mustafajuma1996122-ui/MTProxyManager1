package com.example.mtproxy

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class VM(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    var proxies by mutableStateOf(store.load()); private set
    var busy by mutableStateOf(false); private set
    var msg by mutableStateOf<String?>(null)

    private fun commit(l: List<Proxy>) { proxies = l; store.save(l) }

    private fun sorted(l: List<Proxy>) =
        l.sortedWith(compareBy<Proxy>({ it.ping == null }, { it.failed }, { it.ping ?: 0 }))

    fun fetchAll() = viewModelScope.launch {
        busy = true
        val found = Net.fetchAll()
        val have = proxies.map { it.key }.toSet()
        val fresh = found.filter { it.key !in have }
        commit(proxies + fresh)
        msg = if (found.isEmpty()) "تعذّر الجلب من المصادر" else "أُضيف ${fresh.size} بروكسي جديد"
        busy = false
    }

    fun checkAll() = viewModelScope.launch {
        if (proxies.isEmpty()) return@launch
        busy = true
        val sem = Semaphore(25)
        val res = proxies.map { p -> async { sem.withPermit { Net.ping(p) } } }.awaitAll()
        commit(sorted(res))
        msg = "تم الفحص: ${res.count { it.ping != null }} يعمل من ${res.size}"
        busy = false
    }

    fun add(text: String): Boolean {
        val list = Net.parseManual(text)
        if (list.isEmpty()) return false
        val have = proxies.map { it.key }.toSet()
        commit(proxies + list.filter { it.key !in have })
        return true
    }

    fun remove(p: Proxy) = commit(proxies.filter { it.key != p.key })
    fun removeFailed() = commit(proxies.filter { !it.failed })
    fun clearAll() = commit(emptyList())
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("proxy", text))
}

fun openInTelegram(ctx: Context, p: Proxy) {
    fun view(u: String) = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(view(p.link))
    } catch (e: ActivityNotFoundException) {
        try { ctx.startActivity(view(p.webLink)) } catch (e2: Exception) { /* no handler */ }
    }
}

fun exportText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(i, "تصدير البروكسيات").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: VM = viewModel()) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(vm.msg) { vm.msg?.let { snack.showSnackbar(it); vm.msg = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("بروكسي MTProto (${vm.proxies.size})") },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("نسخ كل الروابط") }, onClick = {
                            menu = false
                            copyText(ctx, vm.proxies.joinToString("\n") { it.link })
                            vm.msg = "تم نسخ ${vm.proxies.size} رابط"
                        })
                        DropdownMenuItem(text = { Text("تصدير / مشاركة") }, onClick = {
                            menu = false
                            exportText(ctx, vm.proxies.joinToString("\n") { it.link })
                        })
                        DropdownMenuItem(text = { Text("حذف غير العاملة") }, onClick = {
                            menu = false; vm.removeFailed()
                        })
                        DropdownMenuItem(text = { Text("حذف الكل") }, onClick = {
                            menu = false; vm.clearAll()
                        })
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, null) }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.fetchAll() }, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                    Text("جلب من المصادر")
                }
                Button(onClick = { vm.checkAll() }, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                    Text("فحص وترتيب")
                }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.proxies.isEmpty() && !vm.busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("لا توجد بروكسيات — اضغط «جلب من المصادر» أو +")
                }
            }
            LazyColumn {
                items(vm.proxies, key = { it.key }) { p -> ProxyItem(p, vm) }
            }
        }
    }

    if (showAdd) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("إضافة بروكسي") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("رابط tg://proxy أو server:port:secret") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (vm.add(text)) { showAdd = false } else vm.msg = "صيغة غير صحيحة"
                }) { Text("إضافة") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun ProxyItem(p: Proxy, vm: VM) {
    val ctx = LocalContext.current
    val (status, color) = when {
        p.ping != null -> "${p.ping} ms" to (if (p.ping < 300) Color(0xFF2E7D32) else Color(0xFFEF6C00))
        p.failed -> "لا يستجيب" to Color(0xFFC62828)
        else -> "لم يُفحص" to Color.Gray
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${p.server}:${p.port}", fontWeight = FontWeight.Bold)
                    Text(status, color = color)
                }
                IconButton(onClick = { vm.remove(p) }) { Icon(Icons.Default.Delete, null) }
            }
            Row {
                TextButton(onClick = { openInTelegram(ctx, p) }) { Text("فتح في Telegram") }
                TextButton(onClick = { copyText(ctx, p.link); vm.msg = "تم نسخ الرابط" }) { Text("نسخ الرابط") }
            }
        }
    }
}
