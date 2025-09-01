package com.example.kotlintexteditor

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kotlintexteditor.ui.theme.KotlinTextEditorTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import androidx.compose.ui.text.buildAnnotatedString
import android.content.Intent


// --------------------------- Data models ---------------------------
data class CommentConfig(val line: String?, val blockStart: String?, val blockEnd: String?)
data class SyntaxConfig(
    val keywords: List<String>,
    val types: List<String>,
    val comments: CommentConfig?,
    val strings: String?
)

// --------------------------- Load syntax config from assets ---------------------------
fun loadSyntaxConfigFromAssets(context: Context, fileName: String): SyntaxConfig? {
    return try {
        val input = context.assets.open(fileName)
        val json = BufferedReader(InputStreamReader(input)).use { it.readText() }
        val obj = JSONObject(json)

        val keywords = obj.optJSONArray("keywords")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
        val types = obj.optJSONArray("types")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
        val commentsObj = obj.optJSONObject("comments")
        val comments = commentsObj?.let {
            CommentConfig(
                line = it.optString("line", null),
                blockStart = it.optString("blockStart", null),
                blockEnd = it.optString("blockEnd", null)
            )
        }
        val strings = obj.optString("strings", null)
        SyntaxConfig(keywords, types, comments, strings)
    } catch (_: Exception) {
        null
    }
}

// --------------------------- Built-in Kotlin highlighting ---------------------------
fun highlightKotlinBuiltIn(code: String): AnnotatedString {
    val keywords = setOf(
        "fun","class","object","val","var","if","else","when","for","while","do","return",
        "null","true","false","in","is","interface","sealed","data","enum","try","catch",
        "finally","throw","super","this","as","typealias","package","import"
    )
    val types = setOf("Int","String","Float","Double","Boolean","Char","Long","Short","Any","Unit","List","Map","Set")

    return buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            when {
                // Block comment
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", startIndex = i + 2).let { if (it == -1) code.length else it + 2 }
                    pushStyle(SpanStyle(color = Color(0xFF6A9955))); append(code.substring(i, end)); pop(); i = end
                }
                // Line comment
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', startIndex = i).let { if (it == -1) code.length else it }
                    pushStyle(SpanStyle(color = Color(0xFF6A9955))); append(code.substring(i, end)); pop(); i = end
                }
                // String
                code[i] == '"' -> {
                    val start = i; i++
                    while (i < code.length && code[i] != '"') {
                        if (code[i] == '\\' && i + 1 < code.length) i++
                        i++
                    }
                    if (i < code.length) i++
                    pushStyle(SpanStyle(color = Color(0xFFD69D85))); append(code.substring(start, i)); pop()
                }
                // Numbers
                code[i].isDigit() -> {
                    val start = i
                    while (i < code.length && (code[i].isDigit() || code[i] == '.')) i++
                    pushStyle(SpanStyle(color = Color(0xFFB5CEA8))); append(code.substring(start, i)); pop()
                }
                // Identifiers
                code[i].isLetter() || code[i] == '_' -> {
                    val start = i
                    while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    val token = code.substring(start, i)
                    when {
                        keywords.contains(token) -> { pushStyle(SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.SemiBold)); append(token); pop() }
                        types.contains(token) -> { pushStyle(SpanStyle(color = Color(0xFF4EC9B0))); append(token); pop() }
                        else -> append(token)
                    }
                }
                else -> { append(code[i]); i++ }
            }
        }
    }
}

// --------------------------- Generic highlighting from config ---------------------------
fun highlightWithConfig(code: String, cfg: SyntaxConfig?): AnnotatedString {
    if (cfg == null) return AnnotatedString(code)
    return buildAnnotatedString {
        append(code)

        // Keywords
        for (kw in cfg.keywords) {
            try {
                val rx = Regex("\\b${Regex.escape(kw)}\\b")
                rx.findAll(code).forEach { m ->
                    addStyle(SpanStyle(color = Color(0xFF1565C0), fontWeight = FontWeight.Bold), m.range.first, m.range.last + 1)
                }
            } catch (_: Exception) { }
        }
        // Types
        for (tp in cfg.types) {
            try {
                val rx = Regex("\\b${Regex.escape(tp)}\\b")
                rx.findAll(code).forEach { m ->
                    addStyle(SpanStyle(color = Color(0xFF4EC9B0)), m.range.first, m.range.last + 1)
                }
            } catch (_: Exception) { }
        }
        // Comments
        try {
            cfg.comments?.line?.let { line ->
                val rx = Regex(Regex.escape(line) + ".*")
                rx.findAll(code).forEach { m -> addStyle(SpanStyle(color = Color(0xFF6A9955), fontStyle = FontStyle.Italic), m.range.first, m.range.last + 1) }
            }
            if (cfg.comments?.blockStart != null && cfg.comments.blockEnd != null) {
                val rx = Regex(
                    Regex.escape(cfg.comments.blockStart) + ".*?" + Regex.escape(cfg.comments.blockEnd),
                    RegexOption.DOT_MATCHES_ALL
                )
                rx.findAll(code).forEach { m -> addStyle(SpanStyle(color = Color(0xFF6A9955), fontStyle = FontStyle.Italic), m.range.first, m.range.last + 1) }
            }
        } catch (_: Exception) { }
        // Strings
        cfg.strings?.let { quote ->
            try {
                val rx = Regex(Regex.escape(quote) + ".*?" + Regex.escape(quote))
                rx.findAll(code).forEach { m -> addStyle(SpanStyle(color = Color(0xFFD69D85)), m.range.first, m.range.last + 1) }
            } catch (_: Exception) { }
        }
    }
}

// --------------------------- Search overlay ---------------------------
fun overlaySearchHighlights(
    base: AnnotatedString,
    fullText: String,
    query: String,
    caseSensitive: Boolean,
    wholeWord: Boolean
): AnnotatedString {
    if (query.isBlank()) return base
    val pattern = if (wholeWord) "\\b${Regex.escape(query)}\\b" else Regex.escape(query)
    val rx = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)

    return buildAnnotatedString {
        append(base)
        rx.findAll(fullText).forEach { m ->
            addStyle(SpanStyle(background = Color.Yellow, color = Color.Black), m.range.first, m.range.last + 1)
        }
    }
}

// --------------------------- Error overlay (line highlighting) ---------------------------
fun overlayErrorHighlights(
    base: AnnotatedString,
    fullText: String,
    errorLines: Set<Int>
): AnnotatedString {
    if (errorLines.isEmpty()) return base
    val lineStartIdx = mutableListOf(0)
    fullText.forEachIndexed { idx, c -> if (c == '\n') lineStartIdx.add(idx + 1) }

    return buildAnnotatedString {
        append(base)
        errorLines.forEach { lineOneBased ->
            val ln = lineOneBased.coerceAtLeast(1)
            val start = lineStartIdx.getOrNull(ln - 1) ?: return@forEach
            val end = lineStartIdx.getOrNull(ln) ?: fullText.length
            addStyle(
                SpanStyle(background = Color(0x33FF0000), textDecoration = TextDecoration.Underline),
                start, end
            )
        }
    }
}

// --------------------------- ADB bridge (desktop compiler) ---------------------------
private const val BRIDGE_HOST = "127.0.0.1"
private const val BRIDGE_PORT = 8177

suspend fun checkBridge(): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val url = URL("http://$BRIDGE_HOST:$BRIDGE_PORT/health")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2000
            readTimeout = 2000
        }
        if (conn.responseCode == 200) {
            conn.inputStream.close()
            Result.success(Unit)
        } else {
            Result.failure(Exception("HTTP ${conn.responseCode}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun streamCompile(
    filename: String,
    code: String,
    onLine: suspend (String) -> Unit
): Result<Boolean> = withContext(Dispatchers.IO) {
    val url = URL("http://$BRIDGE_HOST:$BRIDGE_PORT/compile")
    val conn = (url.openConnection() as HttpURLConnection)
    return@withContext try {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setChunkedStreamingMode(0)
        val body = JSONObject()
            .put("filename", filename)
            .put("code", code)
            .toString()
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            var line: String?
            var ok = false
            while (true) {
                line = reader.readLine() ?: break
                onLine(line!!)
                if (line!!.startsWith("{") && line!!.endsWith("}")) {
                    try {
                        val obj = JSONObject(line)
                        ok = obj.optBoolean("ok", false)
                    } catch (_: Exception) {}
                }
            }
            Result.success(ok)
        }
    } catch (e: Exception) {
        val errText = try {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: e.message.orEmpty()
        } catch (_: Exception) { e.message.orEmpty() }
        Result.failure(Exception(errText.ifBlank { e.message.orEmpty() }))
    } finally {
        conn.disconnect()
    }
}

// --------------------------- Activity ---------------------------
class MainActivity : ComponentActivity() {
    private val draftFileName = "autosave_draft.txt"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KotlinTextEditorTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TextEditorScreen(draftFileName = draftFileName)
                }
            }
        }
    }
}

// --------------------------- UI ---------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(draftFileName: String = "draft.txt") {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // ---------- editor & file state ----------
    var textState by remember { mutableStateOf(TextFieldValue("")) }
    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }

    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var currentFileName by remember { mutableStateOf("Untitled.kt") }
    var newFileExtension by remember { mutableStateOf(".kt") }

    // ---------- find/replace ----------
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }

    // ---------- highlighting ----------
    var selectedLanguage by remember { mutableStateOf("Kotlin (built-in)") }
    var syntaxConfig by remember { mutableStateOf<SyntaxConfig?>(null) }

    // ---------- compile / errors ----------
    var compilerConnected by remember { mutableStateOf(false) }
    var compileOutput by remember { mutableStateOf("") }
    var compilationStatus by remember { mutableStateOf("") }
    var errorLines by remember { mutableStateOf(setOf<Int>()) }

    // ---------- Autosave ----------
    LaunchedEffect(textState.text) {
        context.openFileOutput(draftFileName, Context.MODE_PRIVATE).use {
            it.write(textState.text.toByteArray())
        }
    }
    LaunchedEffect(Unit) {
        try {
            val text = context.openFileInput(draftFileName).bufferedReader().use { it.readText() }
            textState = TextFieldValue(text, TextRange(text.length))
        } catch (_: Exception) { /* first run no draft */ }
    }

    // ---------- SAF Launchers ----------
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        currentUri = uri
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        }.onSuccess { content ->
            textState = TextFieldValue(content, TextRange(content.length))
            currentFileName = guessNameFromUri(uri) ?: currentFileName
        }
    }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        currentUri = uri
        saveToUri(context, uri, textState.text)
        currentFileName = guessNameFromUri(uri) ?: currentFileName
    }

    // ---------- Highlighted text (syntax + search + errors) ----------
    val highlighted = remember(textState.text, findText, caseSensitive, wholeWord, syntaxConfig, selectedLanguage, errorLines) {
        var base: AnnotatedString = if (selectedLanguage == "Kotlin (built-in)") {
            highlightKotlinBuiltIn(textState.text)
        } else {
            highlightWithConfig(textState.text, syntaxConfig)
        }
        base = overlaySearchHighlights(base, textState.text, findText, caseSensitive, wholeWord)
        base = overlayErrorHighlights(base, textState.text, errorLines)
        base
    }

    // ---------- UI ----------
    Column(Modifier.fillMaxSize()) {

        // Top bar (file + edit + compile)
        TopAppBar(
            title = { Text(currentFileName) },
            actions = {
                // New
                TextButton(onClick = {
                    undoStack.clear(); redoStack.clear()
                    textState = TextFieldValue("")
                    currentUri = null
                    currentFileName = "Untitled${newFileExtension}"
                }) { Text("New") }

                // Open
                TextButton(onClick = {
                    openLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                }) { Text("Open") }

                // Save
                TextButton(onClick = {
                    if (currentUri != null) {
                        saveToUri(context, currentUri!!, textState.text)
                    } else {
                        val name = if (currentFileName.endsWith(newFileExtension)) currentFileName else "Untitled$newFileExtension"
                        createLauncher.launch(name)
                    }
                }) { Text("Save") }

                Spacer(Modifier.width(12.dp))

                // Clipboard / Undo-Redo
                TextButton(onClick = {
                    val sel = textState.selection
                    if (!sel.collapsed) {
                        val selected = textState.text.substring(sel.start, sel.end)
                        clipboard.setText(AnnotatedString(selected))
                    }
                }) { Text("Copy") }
                TextButton(onClick = {
                    val sel = textState.selection
                    if (!sel.collapsed) {
                        val selected = textState.text.substring(sel.start, sel.end)
                        clipboard.setText(AnnotatedString(selected))
                        textState = textState.copy(
                            text = textState.text.removeRange(sel.start, sel.end),
                            selection = TextRange(sel.start)
                        )
                    }
                }) { Text("Cut") }
                TextButton(onClick = {
                    val clip = clipboard.getText()?.text ?: ""
                    val sel = textState.selection
                    textState = textState.copy(
                        text = textState.text.replaceRange(sel.start, sel.end, clip),
                        selection = TextRange(sel.start + clip.length)
                    )
                }) { Text("Paste") }

                TextButton(onClick = {
                    if (undoStack.isNotEmpty()) {
                        val prev = undoStack.removeAt(undoStack.lastIndex)
                        redoStack.add(textState)
                        textState = prev
                    }
                }) { Text("Undo") }
                TextButton(onClick = {
                    if (redoStack.isNotEmpty()) {
                        val next = redoStack.removeAt(redoStack.lastIndex)
                        undoStack.add(textState)
                        textState = next
                    }
                }) { Text("Redo") }

                Spacer(Modifier.width(12.dp))

                // Language selector (built-in or config)
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) { Text(selectedLanguage) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("Kotlin (built-in)") }, onClick = {
                            selectedLanguage = "Kotlin (built-in)"
                            syntaxConfig = null
                            expanded = false
                            newFileExtension = ".kt"
                        })
                        DropdownMenuItem(text = { Text("Load config: python.json") }, onClick = {
                            syntaxConfig = loadSyntaxConfigFromAssets(context, "python.json")
                            selectedLanguage = "Python (config)"
                            expanded = false
                            newFileExtension = ".py"
                        })
                        DropdownMenuItem(text = { Text("Load config: java.json") }, onClick = {
                            syntaxConfig = loadSyntaxConfigFromAssets(context, "java.json")
                            selectedLanguage = "Java (config)"
                            expanded = false
                            newFileExtension = ".java"
                        })
                    }
                }

                // Extension chooser for new file
                var extExpanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { extExpanded = true }) { Text("Ext: $newFileExtension") }
                    DropdownMenu(expanded = extExpanded, onDismissRequest = { extExpanded = false }) {
                        listOf(".txt",".kt",".java",".py").forEach { ext ->
                            DropdownMenuItem(text = { Text(ext) }, onClick = {
                                newFileExtension = ext
                                extExpanded = false
                            })
                        }
                    }
                }

                // ADB connect / compile
                TextButton(onClick = {
                    scope.launch {
                        compilationStatus = "Connecting…"
                        compilerConnected = checkBridge().isSuccess
                        compilationStatus = if (compilerConnected) "Connected" else "Not connected"
                    }
                }) { Text(if (compilerConnected) "Connected" else "Connect") }

                TextButton(onClick = {
                    scope.launch {
                        compileOutput = ""
                        errorLines = emptySet()
                        compilationStatus = "Compiling…"
                        val ok = streamCompile(currentFileName, textState.text) { line ->
                            withContext(Dispatchers.Main) {
                                compileOutput += line + "\n"
                                // try to extract ":<line>:" patterns (kotlinc style)
                                Regex(":(\\d+):").findAll(line).forEach { m ->
                                    errorLines = errorLines + (m.groupValues[1].toInt())
                                }
                            }
                        }
                        compilationStatus = if (ok.getOrNull() == true) "Success" else "Failed"
                    }
                }) { Text("Compile") }
            }
        )

        // Find / Replace bar
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = findText,
                    onValueChange = { findText = it },
                    label = { Text("Find") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = replaceText,
                    onValueChange = { replaceText = it },
                    label = { Text("Replace") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
                        Text("Case sensitive")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wholeWord, onCheckedChange = { wholeWord = it })
                        Text("Whole word")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (findText.isNotEmpty()) {
                        val pattern = if (wholeWord) "\\b${Regex.escape(findText)}\\b" else Regex.escape(findText)
                        val rx = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
                        val start = textState.selection.end.coerceAtMost(textState.text.length)
                        val m = rx.find(textState.text, startIndex = start) ?: rx.find(textState.text)
                        m?.let { match ->
                            textState = textState.copy(selection = TextRange(match.range.first, match.range.last + 1))
                        }
                    }
                }) { Text("Find Next") }

                Button(onClick = {
                    val pattern = if (wholeWord) "\\b${Regex.escape(findText)}\\b" else Regex.escape(findText)
                    val rx = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
                    val sel = textState.selection
                    val cur = textState.text
                    val selected = if (!sel.collapsed && sel.end <= cur.length) cur.substring(sel.start, sel.end) else null

                    if (!findText.isEmpty()) {
                        if (selected != null && rx.matches(selected)) {
                            val new = cur.replaceRange(sel.start, sel.end, replaceText)
                            textState = textState.copy(text = new, selection = TextRange(sel.start, sel.start + replaceText.length))
                        } else {
                            val m = rx.find(cur, startIndex = sel.end) ?: rx.find(cur)
                            m?.let {
                                val new = cur.replaceRange(it.range, replaceText)
                                textState = textState.copy(
                                    text = new,
                                    selection = TextRange(it.range.first, it.range.first + replaceText.length)
                                )
                            }
                        }
                    }
                }) { Text("Replace") }

                Button(onClick = {
                    if (findText.isNotEmpty()) {
                        val pattern = if (wholeWord) "\\b${Regex.escape(findText)}\\b" else Regex.escape(findText)
                        val rx = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
                        val newText = rx.replace(textState.text, replaceText)
                        textState = textState.copy(text = newText, selection = TextRange(0, 0))
                    }
                }) { Text("Replace All") }
            }
        }

        // Editor
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                .shadow(2.dp, RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
        ) {
            val scroll = rememberScrollState()
            // Render highlights as background text; the real field is transparent so user can edit
            Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
                Text(highlighted, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            }
            BasicTextField(
                value = textState,
                onValueChange = {
                    undoStack.add(textState); redoStack.clear()
                    textState = it
                },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(12.dp),
                textStyle = TextStyle( // invisible ink on top; cursor still visible
                    color = Color.Transparent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(Color.Black)
            )
        }

        // Status + compiler output
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val words = remember(textState.text) {
                textState.text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            }
            Text("Chars: ${textState.text.length}   Words: $words")
            Text("Compile: $compilationStatus")
        }

        if (compileOutput.isNotBlank()) {
            Text(
                compileOutput,
                color = if (compilationStatus == "Success") Color(0xFF2E7D32) else Color(0xFFB00020),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(
                        if (compilationStatus == "Success") Color(0x1422C55E) else Color(0x14B00020),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
    }
}

// --------------------------- Helpers ------------------------------
private fun guessNameFromUri(uri: Uri): String? = uri.lastPathSegment?.substringAfterLast('/')

private fun saveToUri(context: Context, uri: Uri, text: String) {
    runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            OutputStreamWriter(out).use { it.write(text) }
        }
    }
}
