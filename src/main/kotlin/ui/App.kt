package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import converter.SngToSongConverter
import converter.SpsToSongConverter
import converter.XmlToSpbConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun App() {
    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf("Songs", "Bibles")

        Scaffold { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                            icon = {
                                Icon(
                                    if (index == 0) Icons.Default.MusicNote else Icons.Default.Book,
                                    contentDescription = title
                                )
                            }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> SongsTab()
                    1 -> BibleConverterTab()
                }
            }
        }
    }
}

enum class ConvertState { SELECT, PREVIEW, CONVERTING, DONE }

// =============================================================================
// Songs Tab — unified SNG + SPS
// =============================================================================

@Composable
fun SongsTab() {
    // SNG state
    var sngFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var sngOutputDir by remember { mutableStateOf<File?>(null) }
    var sngPreview by remember { mutableStateOf<List<PreviewItem>>(emptyList()) }
    var sngLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var sngState by remember { mutableStateOf(ConvertState.SELECT) }

    // SPS state
    var spsFile by remember { mutableStateOf<File?>(null) }
    var spsOutputDir by remember { mutableStateOf<File?>(null) }
    var spsPreview by remember { mutableStateOf<SpsPreviewData?>(null) }
    var spsLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var spsState by remember { mutableStateOf(ConvertState.SELECT) }

    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── SNG Section ──────────────────────────────────────────────────
        item {
            Text("SNG to SONG", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Convert SongBeamer .sng files to .song format",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val files = pickFiles("SNG Files", "sng", multiSelection = true)
                    if (files.isNotEmpty()) {
                        sngFiles = files; sngState = ConvertState.SELECT; sngPreview = emptyList(); sngLog = emptyList()
                    }
                }) {
                    Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Select .sng Files")
                }
                Button(onClick = {
                    val dir = pickDirectory()
                    if (dir != null) {
                        val files = dir.listFiles { f -> f.extension.equals("sng", ignoreCase = true) }?.toList() ?: emptyList()
                        sngFiles = files; sngState = ConvertState.SELECT; sngPreview = emptyList(); sngLog = emptyList()
                    }
                }) {
                    Icon(Icons.Default.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Select Folder")
                }
                if (sngFiles.isNotEmpty()) {
                    Text("${sngFiles.size} file(s)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val dir = pickDirectory(); if (dir != null) { sngOutputDir = dir }
                }) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Output Folder")
                }
                Text(
                    sngOutputDir?.absolutePath ?: "Same as input (default)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (sngState) {
                    ConvertState.SELECT -> {
                        OutlinedButton(onClick = {
                            sngPreview = buildSongPreview(sngFiles, sngOutputDir); sngState = ConvertState.PREVIEW
                        }, enabled = sngFiles.isNotEmpty()) {
                            Icon(Icons.Default.Preview, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Preview")
                        }
                        Button(onClick = {
                            sngState = ConvertState.CONVERTING
                            scope.launch {
                                sngLog = withContext(Dispatchers.IO) {
                                    sngFiles.map { file ->
                                        try {
                                            val outDir = sngOutputDir ?: file.parentFile
                                            val outFile = File(outDir, file.nameWithoutExtension + ".song")
                                            SngToSongConverter.convert(file, outFile)
                                            "OK: ${file.name} -> ${outFile.name}"
                                        } catch (e: Exception) { "ERROR: ${file.name} - ${e.message}" }
                                    }
                                }
                                sngState = ConvertState.DONE
                            }
                        }, enabled = sngFiles.isNotEmpty()) {
                            Icon(Icons.Default.Transform, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Convert")
                        }
                    }
                    ConvertState.PREVIEW -> {
                        Button(onClick = {
                            sngState = ConvertState.CONVERTING
                            scope.launch {
                                sngLog = withContext(Dispatchers.IO) {
                                    sngFiles.map { file ->
                                        try {
                                            val outDir = sngOutputDir ?: file.parentFile
                                            val outFile = File(outDir, file.nameWithoutExtension + ".song")
                                            SngToSongConverter.convert(file, outFile)
                                            "OK: ${file.name} -> ${outFile.name}"
                                        } catch (e: Exception) { "ERROR: ${file.name} - ${e.message}" }
                                    }
                                }
                                sngState = ConvertState.DONE
                            }
                        }) {
                            Icon(Icons.Default.Transform, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Convert ${sngFiles.size} file(s)")
                        }
                        OutlinedButton(onClick = { sngState = ConvertState.SELECT; sngPreview = emptyList() }) { Text("Back") }
                    }
                    ConvertState.CONVERTING -> {
                        Button(enabled = false, onClick = {}) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text("Converting...")
                        }
                    }
                    ConvertState.DONE -> {
                        OutlinedButton(onClick = {
                            sngState = ConvertState.SELECT; sngFiles = emptyList(); sngPreview = emptyList(); sngLog = emptyList()
                        }) { Text("Start Over") }
                    }
                }
            }
        }

        // SNG preview/results
        if (sngState == ConvertState.PREVIEW && sngPreview.isNotEmpty()) {
            item { Text("Preview:", style = MaterialTheme.typography.titleSmall) }
            items(sngPreview) { item -> PreviewRow(item) }
        }
        if (sngState == ConvertState.DONE && sngLog.isNotEmpty()) {
            item {
                val ok = sngLog.count { it.startsWith("OK") }; val err = sngLog.count { it.startsWith("ERROR") }
                Text("Done: $ok converted, $err failed", style = MaterialTheme.typography.titleSmall,
                    color = if (err > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            items(sngLog) { msg -> LogLine(msg) }
        }

        // ── Divider ──────────────────────────────────────────────────────
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }

        // ── SPS Section ──────────────────────────────────────────────────
        item {
            Text("SPS to SONG", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Convert SongPresenter .sps songbook to individual .song files",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val files = pickFiles("SPS Files", "sps", multiSelection = false)
                    if (files.isNotEmpty()) {
                        spsFile = files.first(); spsState = ConvertState.SELECT; spsPreview = null; spsLog = emptyList()
                    }
                }) {
                    Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Select .sps File")
                }
                if (spsFile != null) {
                    Text(spsFile!!.name, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val dir = pickDirectory(); if (dir != null) { spsOutputDir = dir }
                }) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Output Folder")
                }
                Text(
                    spsOutputDir?.absolutePath ?: "Must select output folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (spsOutputDir == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (spsState) {
                    ConvertState.SELECT -> {
                        OutlinedButton(onClick = {
                            spsPreview = buildSpsPreview(spsFile!!, spsOutputDir!!); spsState = ConvertState.PREVIEW
                        }, enabled = spsFile != null && spsOutputDir != null) {
                            Icon(Icons.Default.Preview, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Preview")
                        }
                        Button(onClick = {
                            spsState = ConvertState.CONVERTING
                            scope.launch {
                                spsLog = withContext(Dispatchers.IO) {
                                    try {
                                        val result = SpsToSongConverter.convert(spsFile!!, spsOutputDir!!)
                                        val msgs = mutableListOf(
                                            "Songbook: ${result.songbookFolder.substringAfterLast('/').substringAfterLast('\\')}",
                                            "Songs converted: ${result.songsConverted}",
                                            "Output: ${result.songbookFolder}"
                                        )
                                        result.errors.forEach { msgs.add("ERROR: $it") }
                                        if (result.errors.isEmpty()) msgs.add("OK: All songs converted successfully")
                                        msgs
                                    } catch (e: Exception) { listOf("ERROR: ${e.message}") }
                                }
                                spsState = ConvertState.DONE
                            }
                        }, enabled = spsFile != null && spsOutputDir != null) {
                            Icon(Icons.Default.Transform, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Convert")
                        }
                    }
                    ConvertState.PREVIEW -> {
                        Button(onClick = {
                            spsState = ConvertState.CONVERTING
                            scope.launch {
                                spsLog = withContext(Dispatchers.IO) {
                                    try {
                                        val result = SpsToSongConverter.convert(spsFile!!, spsOutputDir!!)
                                        val msgs = mutableListOf(
                                            "Songbook: ${spsPreview?.songbookName}",
                                            "Songs converted: ${result.songsConverted}",
                                            "Output: ${result.songbookFolder}"
                                        )
                                        result.errors.forEach { msgs.add("ERROR: $it") }
                                        if (result.errors.isEmpty()) msgs.add("OK: All songs converted successfully")
                                        msgs
                                    } catch (e: Exception) { listOf("ERROR: ${e.message}") }
                                }
                                spsState = ConvertState.DONE
                            }
                        }, enabled = spsPreview?.error == null) {
                            Icon(Icons.Default.Transform, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Convert ${spsPreview?.songCount ?: 0} song(s)")
                        }
                        OutlinedButton(onClick = { spsState = ConvertState.SELECT; spsPreview = null }) { Text("Back") }
                    }
                    ConvertState.CONVERTING -> {
                        Button(enabled = false, onClick = {}) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text("Converting...")
                        }
                    }
                    ConvertState.DONE -> {
                        OutlinedButton(onClick = {
                            spsState = ConvertState.SELECT; spsFile = null; spsPreview = null; spsLog = emptyList()
                        }) { Text("Start Over") }
                    }
                }
            }
        }

        // SPS preview
        if (spsState == ConvertState.PREVIEW) {
            spsPreview?.let { p ->
                if (p.error != null) {
                    item { Text("Error: ${p.error}", color = MaterialTheme.colorScheme.error) }
                } else {
                    item {
                        Column {
                            Text("Songbook: ${p.songbookName}", style = MaterialTheme.typography.bodyMedium)
                            Text("Songs found: ${p.songCount}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Output folder: ${p.outputFolder}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (p.folderExists) {
                                Text("Output folder already exists - files may be overwritten",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (p.sampleTitles.isNotEmpty()) {
                        item { Text("Songs:", style = MaterialTheme.typography.titleSmall) }
                        items(p.sampleTitles) { title ->
                            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (spsState == ConvertState.DONE && spsLog.isNotEmpty()) {
            item {
                val hasErr = spsLog.any { it.startsWith("ERROR") }
                Text(if (hasErr) "Completed with errors" else "Done", style = MaterialTheme.typography.titleSmall,
                    color = if (hasErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            items(spsLog) { msg -> LogLine(msg) }
        }
    }
}

// =============================================================================
// Bible Tab
// =============================================================================

@Composable
fun BibleConverterTab() {
    var inputFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var outputDir by remember { mutableStateOf<File?>(null) }
    var logMessages by remember { mutableStateOf<List<String>>(emptyList()) }
    var state by remember { mutableStateOf(ConvertState.SELECT) }
    var previewItems by remember { mutableStateOf<List<PreviewItem>>(emptyList()) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Bible Converter", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Convert Zefania XML bible files to ChurchPresenter .spb format",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val files = pickFiles("XML Bible Files", "xml", multiSelection = true)
                if (files.isNotEmpty()) {
                    inputFiles = files; state = ConvertState.SELECT; previewItems = emptyList(); logMessages = emptyList()
                }
            }) {
                Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Select .xml Files")
            }
            Button(onClick = {
                val dir = pickDirectory()
                if (dir != null) {
                    val files = findXmlFilesRecursive(dir)
                    inputFiles = files; state = ConvertState.SELECT; previewItems = emptyList(); logMessages = emptyList()
                }
            }) {
                Icon(Icons.Default.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Select Folder")
            }
            if (inputFiles.isNotEmpty()) {
                Text("${inputFiles.size} file(s)", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val dir = pickDirectory(); if (dir != null) { outputDir = dir }
            }) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Output Folder")
            }
            Text(
                outputDir?.absolutePath ?: "Same as input (default)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                ConvertState.SELECT -> {
                    OutlinedButton(onClick = {
                        previewItems = buildBiblePreview(inputFiles, outputDir); state = ConvertState.PREVIEW
                    }, enabled = inputFiles.isNotEmpty()) {
                        Icon(Icons.Default.Preview, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Preview")
                    }
                    Button(onClick = {
                        state = ConvertState.CONVERTING
                        scope.launch {
                            logMessages = withContext(Dispatchers.IO) {
                                inputFiles.map { file ->
                                    try {
                                        val outDir = outputDir ?: file.parentFile
                                        val outFile = File(outDir, file.nameWithoutExtension + ".spb")
                                        XmlToSpbConverter.convert(file, outFile)
                                        "OK: ${file.name} -> ${outFile.name}"
                                    } catch (e: Exception) { "ERROR: ${file.name} - ${e.message}" }
                                }
                            }
                            state = ConvertState.DONE
                        }
                    }, enabled = inputFiles.isNotEmpty()) {
                        Icon(Icons.Default.Transform, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Convert")
                    }
                }
                ConvertState.PREVIEW -> {
                    Button(onClick = {
                        state = ConvertState.CONVERTING
                        scope.launch {
                            logMessages = withContext(Dispatchers.IO) {
                                inputFiles.map { file ->
                                    try {
                                        val outDir = outputDir ?: file.parentFile
                                        val outFile = File(outDir, file.nameWithoutExtension + ".spb")
                                        XmlToSpbConverter.convert(file, outFile)
                                        "OK: ${file.name} -> ${outFile.name}"
                                    } catch (e: Exception) { "ERROR: ${file.name} - ${e.message}" }
                                }
                            }
                            state = ConvertState.DONE
                        }
                    }) {
                        Icon(Icons.Default.Transform, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Convert ${inputFiles.size} file(s)")
                    }
                    OutlinedButton(onClick = { state = ConvertState.SELECT; previewItems = emptyList() }) { Text("Back") }
                }
                ConvertState.CONVERTING -> {
                    Button(enabled = false, onClick = {}) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Converting...")
                    }
                }
                ConvertState.DONE -> {
                    OutlinedButton(onClick = {
                        state = ConvertState.SELECT; inputFiles = emptyList(); previewItems = emptyList(); logMessages = emptyList()
                    }) { Text("Start Over") }
                }
            }
        }

        when (state) {
            ConvertState.PREVIEW -> {
                Text("Preview:", style = MaterialTheme.typography.titleSmall)
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        items(previewItems) { item -> PreviewRow(item) }
                    }
                }
            }
            ConvertState.DONE -> {
                val ok = logMessages.count { it.startsWith("OK") }; val err = logMessages.count { it.startsWith("ERROR") }
                Text("Done: $ok converted, $err failed", style = MaterialTheme.typography.titleSmall,
                    color = if (err > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        items(logMessages) { msg -> LogLine(msg) }
                    }
                }
            }
            else -> {}
        }
    }
}

// =============================================================================
// Shared UI components
// =============================================================================

data class PreviewItem(
    val inputName: String,
    val inputPath: String,
    val outputName: String,
    val outputPath: String,
    val details: String,
    val willOverwrite: Boolean
)

data class SpsPreviewData(
    val songbookName: String,
    val songCount: Int,
    val outputFolder: String,
    val folderExists: Boolean,
    val sampleTitles: List<String>,
    val error: String? = null
)

@Composable
private fun PreviewRow(item: PreviewItem) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertDriveFile, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(item.inputName, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(item.outputName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (item.details.isNotBlank()) {
                Text(item.details, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, top = 2.dp))
            }
            if (item.willOverwrite) {
                Text("Output file already exists - will be overwritten", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 22.dp, top = 2.dp))
            }
            Text(item.outputPath, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, top = 2.dp))
        }
    }
}

@Composable
private fun LogLine(msg: String) {
    val color = if (msg.startsWith("ERROR")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Text(msg, style = MaterialTheme.typography.bodySmall, color = color)
}

// =============================================================================
// Preview builders
// =============================================================================

private fun buildSongPreview(files: List<File>, outputDir: File?): List<PreviewItem> {
    return files.map { file ->
        val outDir = outputDir ?: file.parentFile
        val outFile = File(outDir, file.nameWithoutExtension + ".song")
        val details = try {
            val song = SngToSongConverter.parse(file)
            val parts = mutableListOf<String>()
            if (song.title.isNotBlank()) parts.add("\"${song.title}\"")
            parts.add("${song.sections.size} section(s)")
            if (song.verseOrder.isNotEmpty()) parts.add("order: ${song.verseOrder.joinToString(", ")}")
            parts.joinToString(" | ")
        } catch (e: Exception) { "Parse error: ${e.message}" }
        PreviewItem(file.name, file.absolutePath, outFile.name, outFile.absolutePath, details, outFile.exists())
    }
}

private fun buildSpsPreview(spsFile: File, outputDir: File): SpsPreviewData {
    return try {
        val result = SpsToSongConverter.parse(spsFile)
        val folderName = SpsToSongConverter.getTargetFolderName(spsFile)
        val targetFolder = File(outputDir, folderName)
        val titles = result.songs.map { "${it.number.padStart(4, '0')} - ${it.title}" }
        SpsPreviewData(result.songbookName, result.songs.size, targetFolder.absolutePath, targetFolder.exists(), titles)
    } catch (e: Exception) {
        SpsPreviewData("", 0, "", false, emptyList(), error = e.message)
    }
}

private fun buildBiblePreview(files: List<File>, outputDir: File?): List<PreviewItem> {
    return files.map { file ->
        val outDir = outputDir ?: file.parentFile
        val outFile = File(outDir, file.nameWithoutExtension + ".spb")
        val details = try {
            val bible = XmlToSpbConverter.parse(file)
            val parts = mutableListOf<String>()
            parts.add("\"${bible.name}\"")
            parts.add("${bible.books.size} book(s)")
            val totalVerses = bible.books.sumOf { b -> b.chapters.sumOf { c -> c.verses.size } }
            parts.add("$totalVerses verses")
            if (bible.language != null) parts.add("lang: ${bible.language}")
            parts.joinToString(" | ")
        } catch (e: Exception) { "Parse error: ${e.message}" }
        PreviewItem(file.name, file.absolutePath, outFile.name, outFile.absolutePath, details, outFile.exists())
    }
}

// =============================================================================
// File pickers
// =============================================================================

private fun pickFiles(description: String, extension: String, multiSelection: Boolean): List<File> {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter(description, extension)
        isMultiSelectionEnabled = multiSelection
        dialogTitle = "Select $description"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        if (multiSelection) chooser.selectedFiles.toList() else listOfNotNull(chooser.selectedFile)
    } else emptyList()
}

private fun pickDirectory(): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Select Folder"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun findXmlFilesRecursive(dir: File): List<File> {
    return dir.walkTopDown().filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }.toList()
}
