package com.example.kotlintexteditor

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.kotlintexteditor.ui.theme.KotlinTextEditorTheme
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    private val draftFileName = "autosave_draft.txt"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KotlinTextEditorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TextEditorScreen(this, draftFileName)
                }
            }
        }
    }
}

// ==================== Syntax Highlighting ====================
fun highlightKotlinCodeAdvanced(code: String): AnnotatedString {
    val keywords = setOf(
        "fun", "class", "object", "val", "var", "if", "else", "when",
        "for", "while", "do", "return", "null", "true", "false", "in", "is",
        "interface", "sealed", "data", "enum", "try", "catch", "finally",
        "throw", "super", "this", "as", "typealias"
    )
    val types = setOf(
        "Int", "String", "Float", "Double", "Boolean", "Char", "Long", "Short", "Any", "Unit"
    )
    val annotations = Regex("@\\w+")

    return buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            when {
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2).let { if (it == -1) code.length else it + 2 }
                    pushStyle(SpanStyle(Color(0xFF388E3C))); append(code.substring(i, end)); pop()
                    i = end
                }
                code.startsWith("//", i) -> {
                    val end = code.indexOf("\n", i).let { if (it == -1) code.length else it }
                    pushStyle(SpanStyle(Color(0xFF388E3C))); append(code.substring(i, end)); pop()
                    i = end
                }
                code[i] == '"' -> {
                    val start = i
                    i++
                    while (i < code.length && code[i] != '"') {
                        if (code[i] == '\\' && i + 1 < code.length) i++
                        i++
                    }
                    if (i < code.length) i++
                    pushStyle(SpanStyle(Color(0xFFD32F2F))); append(code.substring(start, i)); pop()
                }
                code[i].isDigit() -> {
                    val start = i
                    while (i < code.length && (code[i].isDigit() || code[i] == '.')) i++
                    pushStyle(SpanStyle(Color(0xFF6A1B9A))); append(code.substring(start, i)); pop()
                }
                code[i] == '@' -> {
                    val match = annotations.find(code, i)
                    if (match != null && match.range.first == i) {
                        pushStyle(SpanStyle(Color(0xFFF57C00))); append(match.value); pop()
                        i += match.value.length
                    } else {
                        append(code[i]); i++
                    }
                }
                code[i].isLetter() || code[i] == '_' -> {
                    val start = i
                    while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    val token = code.substring(start, i)
                    when {
                        keywords.contains(token) -> { pushStyle(SpanStyle(Color(0xFF1565C0))); append(token); pop() }
                        types.contains(token) -> { pushStyle(SpanStyle(Color(0xFF00838F))); append(token); pop() }
                        else -> append(token)
                    }
                }
                else -> {
                    append(code[i])
                    i++
                }
            }
        }
    }
}

// ==================== Highlight with Search ====================
fun highlightWithSearch(code: String, findText: String, caseSensitive: Boolean, wholeWord: Boolean): AnnotatedString {
    val baseHighlight = highlightKotlinCodeAdvanced(code)

    if (findText.isEmpty()) return baseHighlight

    val pattern = if (wholeWord) "\\b${Regex.escape(findText)}\\b" else Regex.escape(findText)
    val regex = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)

    return buildAnnotatedString {
        append(baseHighlight)
        regex.findAll(code).forEach { match ->
            addStyle(
                SpanStyle(background = Color.Yellow),
                match.range.first,
                match.range.last + 1
            )
        }
    }
}

// ==================== Editor ====================
@Composable
fun TextEditorScreen(context: Context, draftFileName: String) {
    var textState by remember { mutableStateOf(TextFieldValue("")) }
    var currentFileUri by remember { mutableStateOf<Uri?>(null) }
    val undoStack = remember { mutableStateListOf<String>() }
    val redoStack = remember { mutableStateListOf<String>() }
    val clipboardManager = LocalClipboardManager.current

    // Search state
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }

    // Auto-load draft
    LaunchedEffect(Unit) {
        val draft = File(context.filesDir, draftFileName)
        if (draft.exists()) textState = TextFieldValue(draft.readText())
    }

    // Auto-save after typing
    LaunchedEffect(textState.text) {
        delay(2000)
        val draftFile = File(context.filesDir, draftFileName)
        draftFile.writeText(textState.text)
    }

    // Undo / Redo helpers
    fun onTextChanged(newValue: TextFieldValue) {
        undoStack.add(textState.text)
        redoStack.clear()
        textState = newValue
    }
    fun undo() { if (undoStack.isNotEmpty()) { val last = undoStack.removeAt(undoStack.lastIndex); redoStack.add(textState.text); textState = TextFieldValue(last) } }
    fun redo() { if (redoStack.isNotEmpty()) { val next = redoStack.removeAt(redoStack.lastIndex); undoStack.add(textState.text); textState = TextFieldValue(next) } }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                onTextChanged(TextFieldValue(reader.readText()))
                currentFileUri = it
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer ->
                writer.write(textState.text)
            }
            currentFileUri = uri
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        Text("Kotlin Text Editor", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        // File controls
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openLauncher.launch(arrayOf("text/plain", "text/x-kotlin", "text/x-java-source")) }) { Text("Open") }
            Button(onClick = {
                if (currentFileUri != null) {
                    context.contentResolver.openOutputStream(currentFileUri!!)?.bufferedWriter()?.use { writer ->
                        writer.write(textState.text)
                    }
                } else saveLauncher.launch("new_file.txt")
            }) { Text("Save") }
            Button(onClick = { onTextChanged(TextFieldValue("")) }) { Text("New") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Find & Replace
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = findText,
                    onValueChange = { findText = it },
                    placeholder = { Text("Find") }
                )
                TextField(
                    value = replaceText,
                    onValueChange = { replaceText = it },
                    placeholder = { Text("Replace") }
                )
                Button(onClick = {
                    if (findText.isNotEmpty()) {
                        val pattern = if (wholeWord) "\\b${Regex.escape(findText)}\\b" else Regex.escape(findText)
                        val regex = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
                        val newText = regex.replace(textState.text, replaceText)
                        textState = textState.copy(text = newText)
                    }
                }) {
                    Text("Replace All")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
                Text("Case Sensitive")

                Checkbox(checked = wholeWord, onCheckedChange = { wholeWord = it })
                Text("Whole Word")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Editor
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .padding(8.dp)
        ) {
            // Highlighted text with search
            Text(
                text = highlightWithSearch(textState.text, findText, caseSensitive, wholeWord),
                style = LocalTextStyle.current.copy(color = Color.Black)
            )

            // Transparent editable field on top
            BasicTextField(
                value = textState,
                onValueChange = { onTextChanged(it) },
                textStyle = LocalTextStyle.current.copy(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Black),
                modifier = Modifier.matchParentSize()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Undo / Redo / Clipboard
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { undo() }) { Text("Undo") }
            Button(onClick = { redo() }) { Text("Redo") }
            Button(onClick = { clipboardManager.setText(AnnotatedString(textState.text)) }) { Text("Copy") }
            Button(onClick = {
                textState = TextFieldValue("")
                clipboardManager.getText()?.let { textState = TextFieldValue(it.text) }
            }) { Text("Paste") }
            Button(onClick = {
                clipboardManager.setText(AnnotatedString(textState.text))
                textState = TextFieldValue("")
            }) { Text("Cut") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status bar with counts
        val charCount = textState.text.length
        val wordCount = textState.text.trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .size
        val lineCount = if (textState.text.isEmpty()) 0 else textState.text.lines().size

        Text(
            "Characters: $charCount   Words: $wordCount   Lines: $lineCount",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}


