package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import converter.DuplicateFinder
import converter.DuplicateGroup
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
        val tabs = listOf("Songs", "Bibles", "Duplicates", "Rename")
        val tabIcons = listOf(Icons.Default.MusicNote, Icons.Default.Book, Icons.Default.ContentCopy, Icons.Default.DriveFileRenameOutline)

        Scaffold { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                            icon = { Icon(tabIcons[index], contentDescription = title) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> SongsTab()
                    1 -> BibleConverterTab()
                    2 -> DuplicateFinderTab()
                    3 -> BulkRenameTab()
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
// Duplicate Finder Tab
// =============================================================================

enum class ScanState { IDLE, SCANNING, DONE }

@Composable
fun DuplicateFinderTab() {
    var directory by remember { mutableStateOf<File?>(null) }
    var scanState by remember { mutableStateOf(ScanState.IDLE) }
    var duplicateGroups by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    var totalScanned by remember { mutableStateOf(0) }
    var expandedGroups by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var songFolders by remember { mutableStateOf<List<File>>(emptyList()) }
    var keepFolder by remember { mutableStateOf<File?>(null) }
    var keepDropdownExpanded by remember { mutableStateOf(false) }
    var filesToDelete by remember { mutableStateOf<List<File>>(emptyList()) }
    var deleteLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var matchByNumber by remember { mutableStateOf(false) }
    var matchByTitle by remember { mutableStateOf(false) }
    var threshold by remember { mutableStateOf(0.9f) }
    var filterMinSimilarity by remember { mutableStateOf(0f) }
    var filterMinFiles by remember { mutableStateOf(2) }
    var filterMaxFiles by remember { mutableStateOf(10) }
    var filterCategories by remember { mutableStateOf(setOf("Same song number", "Same title", "Similar lyrics")) }
    val scope = rememberCoroutineScope()

    val filteredGroups by remember(duplicateGroups, filterCategories, filterMinFiles, filterMaxFiles, filterMinSimilarity) {
        derivedStateOf {
            duplicateGroups.filter { group ->
                group.reason in filterCategories &&
                group.songs.size >= filterMinFiles &&
                group.songs.size <= filterMaxFiles &&
                (group.similarities.isEmpty() || run {
                    val avgSim = if (group.similarities.size > 1)
                        group.similarities.drop(1).average() else 1.0
                    avgSim >= filterMinSimilarity
                })
            }
        }
    }

    // Recompute files to delete based on filtered groups
    LaunchedEffect(keepFolder, filteredGroups) {
        filesToDelete = if (keepFolder != null && filteredGroups.isNotEmpty()) {
            DuplicateFinder.resolveDeletes(filteredGroups, keepFolder!!)
        } else emptyList()
    }

    Row(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Left panel — controls
        val leftScrollState = rememberScrollState()
        Column(
            modifier = Modifier.width(360.dp).fillMaxHeight().verticalScroll(leftScrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Duplicate Song Finder", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Scans in 3 passes: song number, title, lyrics similarity. " +
                "Run multiple times after deleting to catch more.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // Folder picker
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val dir = pickDirectory()
                    if (dir != null) {
                        directory = dir; scanState = ScanState.IDLE; duplicateGroups = emptyList()
                        expandedGroups = emptySet(); keepFolder = null; deleteLog = emptyList()
                        songFolders = emptyList()
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Select Folder")
                }
            }
            if (directory != null) {
                Text(directory!!.absolutePath, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Match options
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(checked = matchByNumber, onCheckedChange = { matchByNumber = it },
                    enabled = scanState != ScanState.SCANNING)
                Text("Match by song number (also checks lyrics similarity)", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(checked = matchByTitle, onCheckedChange = { matchByTitle = it },
                    enabled = scanState != ScanState.SCANNING)
                Text("Match by title", style = MaterialTheme.typography.bodySmall)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Threshold:", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    valueRange = 0.3f..1.0f,
                    steps = 13,
                    modifier = Modifier.weight(1f),
                    enabled = scanState != ScanState.SCANNING
                )
                Text("${(threshold * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }

            // Scan button
            when (scanState) {
                ScanState.IDLE -> {
                    Button(onClick = {
                        scanState = ScanState.SCANNING
                        val useNumber = matchByNumber
                        val useTitle = matchByTitle
                        val useThreshold = threshold.toDouble()
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val songs = DuplicateFinder.scanSongs(directory!!)
                                val groups = DuplicateFinder.findDuplicates(directory!!, threshold = useThreshold, matchByNumber = useNumber, matchByTitle = useTitle)
                                val folders = songs.map { it.file.parentFile }.distinct().sortedBy { it.absolutePath }
                                Triple(songs.size, groups, folders)
                            }
                            totalScanned = result.first
                            duplicateGroups = result.second
                            songFolders = result.third
                            scanState = ScanState.DONE
                        }
                    }, enabled = directory != null, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Scan for Duplicates")
                    }
                }
                ScanState.SCANNING -> {
                    Button(enabled = false, onClick = {}, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Scanning...")
                    }
                }
                ScanState.DONE -> {
                    OutlinedButton(onClick = {
                        scanState = ScanState.IDLE; duplicateGroups = emptyList(); expandedGroups = emptySet()
                        keepFolder = null; deleteLog = emptyList(); songFolders = emptyList()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Scan Again") }
                }
            }

            if (scanState == ScanState.DONE) {
                HorizontalDivider()

                val dupeCount = duplicateGroups.sumOf { it.songs.size }
                if (duplicateGroups.isEmpty()) {
                    Text("No duplicates found among $totalScanned song(s)",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("${duplicateGroups.size} group(s) ($dupeCount songs) / $totalScanned scanned",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)

                    // Keep folder
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            OutlinedButton(onClick = { keepDropdownExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Shield, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                Text(if (keepFolder != null) keepFolder!!.name else "Keep Folder")
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = keepDropdownExpanded, onDismissRequest = { keepDropdownExpanded = false }) {
                                songFolders.forEach { folder ->
                                    val relativePath = directory?.let {
                                        folder.toRelativeString(it).ifEmpty { "." }
                                    } ?: folder.name
                                    DropdownMenuItem(
                                        text = { Text(relativePath) },
                                        onClick = { keepFolder = folder; deleteLog = emptyList(); keepDropdownExpanded = false },
                                        leadingIcon = {
                                            Icon(if (folder == keepFolder) Icons.Default.CheckCircle else Icons.Default.Folder,
                                                null, Modifier.size(18.dp))
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (keepFolder != null && filesToDelete.isNotEmpty() && deleteLog.isEmpty()) {
                        Button(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Delete ${filesToDelete.size} duplicate(s)")
                        }
                    }
                    if (keepFolder != null && filesToDelete.isEmpty() && deleteLog.isEmpty()) {
                        Text("No duplicates to delete for this filter/folder combo",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (deleteLog.isNotEmpty()) {
                        val deleted = deleteLog.count { it.startsWith("Deleted") }
                        val errors = deleteLog.count { it.startsWith("ERROR") }
                        Text("Done: $deleted deleted, $errors failed",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }

                // Filters
                HorizontalDivider()
                Text("Filters", style = MaterialTheme.typography.labelMedium)

                val allCategories = listOf("Same song number", "Same title", "Similar lyrics")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    allCategories.forEach { cat ->
                        FilterChip(
                            selected = cat in filterCategories,
                            onClick = {
                                filterCategories = if (cat in filterCategories)
                                    filterCategories - cat else filterCategories + cat
                            },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Min sim:", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = filterMinSimilarity,
                        onValueChange = { filterMinSimilarity = it },
                        valueRange = 0f..1f, steps = 19,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${(filterMinSimilarity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Files/group:", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = filterMinFiles.toString(),
                        onValueChange = { v -> v.filter { it.isDigit() }.toIntOrNull()?.let { if (it >= 2) filterMinFiles = it } },
                        modifier = Modifier.width(55.dp), textStyle = MaterialTheme.typography.bodySmall, singleLine = true
                    )
                    Text("-", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = filterMaxFiles.toString(),
                        onValueChange = { v -> v.filter { it.isDigit() }.toIntOrNull()?.let { if (it >= 2) filterMaxFiles = it } },
                        modifier = Modifier.width(55.dp), textStyle = MaterialTheme.typography.bodySmall, singleLine = true
                    )
                }

                if (filterMinSimilarity > 0f || filterMinFiles > 2 || filterMaxFiles < 10 || filterCategories.size < 3) {
                    TextButton(onClick = {
                        filterMinSimilarity = 0f; filterMinFiles = 2; filterMaxFiles = 10
                        filterCategories = setOf("Same song number", "Same title", "Similar lyrics")
                    }) {
                        Icon(Icons.Default.Clear, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                        Text("Clear filters")
                    }
                }
            }
        }

        // Confirmation dialog
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete duplicates?") },
                text = {
                    Column {
                        Text("This will permanently delete ${filesToDelete.size} file(s) that are duplicates of songs in:")
                        Spacer(Modifier.height(4.dp))
                        Text(keepFolder!!.absolutePath,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Files to delete:", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        filesToDelete.take(10).forEach { f ->
                            Text(f.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (filesToDelete.size > 10) {
                            Text("... and ${filesToDelete.size - 10} more",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showDeleteConfirm = false
                        scope.launch {
                            deleteLog = withContext(Dispatchers.IO) {
                                filesToDelete.map { file ->
                                    try { file.delete(); "Deleted: ${file.absolutePath}" }
                                    catch (e: Exception) { "ERROR: ${file.name} - ${e.message}" }
                                }
                            }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                },
                dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
            )
        }

        // Right panel — results
        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            if (scanState != ScanState.DONE) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Results will appear here", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column {
                    Text(
                        "Showing ${filteredGroups.size} of ${duplicateGroups.size} group(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )

                    val listState = rememberLazyListState()
                    Box(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(start = 8.dp, top = 0.dp, bottom = 8.dp, end = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (deleteLog.isNotEmpty()) {
                                items(deleteLog.size, key = { "log_$it" }) { idx -> LogLine(deleteLog[idx]) }
                            }

                            filteredGroups.forEachIndexed { groupIdx, group ->
                                item(key = "header_$groupIdx") {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                        onClick = {
                                            expandedGroups = if (groupIdx in expandedGroups)
                                                expandedGroups - groupIdx else expandedGroups + groupIdx
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                if (groupIdx in expandedGroups) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                null, Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "Group ${groupIdx + 1}: ${group.songs.first().title}",
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                val avgSim = if (group.similarities.size > 1)
                                                    group.similarities.drop(1).average() else 1.0
                                                Text(
                                                    "${group.songs.size} files \u2022 ${group.reason} \u2022 ${(avgSim * 100).toInt()}% avg similarity",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                if (groupIdx in expandedGroups) {
                                    items(group.songs.size, key = { "song_${groupIdx}_$it" }) { songIdx ->
                                        val song = group.songs[songIdx]
                                        val isKept = keepFolder != null &&
                                                song.file.canonicalPath.startsWith(keepFolder!!.canonicalPath)
                                        val willBeDeleted = song.file in filesToDelete
                                        Card(
                                            modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = when {
                                                    isKept -> MaterialTheme.colorScheme.primaryContainer
                                                    willBeDeleted -> MaterialTheme.colorScheme.errorContainer
                                                    else -> MaterialTheme.colorScheme.surface
                                                }
                                            )
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        if (isKept) Icons.Default.Shield
                                                        else if (willBeDeleted) Icons.Default.Delete
                                                        else Icons.Default.InsertDriveFile,
                                                        null, Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(song.file.name, style = MaterialTheme.typography.bodyMedium)
                                                    if (isKept) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("KEEP", style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary)
                                                    }
                                                    if (willBeDeleted) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("DELETE", style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.error)
                                                    }
                                                }
                                                val simPercent = if (group.similarities.size > songIdx)
                                                    "${(group.similarities[songIdx] * 100).toInt()}%" else ""
                                                Text(
                                                    "Title: ${song.title}" + if (simPercent.isNotEmpty()) " \u2022 $simPercent similarity" else "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(start = 22.dp, top = 2.dp)
                                                )
                                                Text(
                                                    song.file.absolutePath,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(start = 22.dp, top = 2.dp)
                                                )
                                                if (song.lyricsText.isNotBlank()) {
                                                    val preview = song.lyricsText.lines().take(3).joinToString(" / ")
                                                    Text(
                                                        preview,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(start = 22.dp, top = 4.dp),
                                                        maxLines = 2
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(listState)
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// Bulk Rename Tab
// =============================================================================

data class RenameEntry(val file: File, val newName: String, val conflict: Boolean)

@Composable
fun BulkRenameTab() {
    var directory by remember { mutableStateOf<File?>(null) }
    var stripNumbers by remember { mutableStateOf(true) }
    var renameToFirstVerse by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<RenameEntry>>(emptyList()) }
    var logMessages by remember { mutableStateOf<List<String>>(emptyList()) }
    var state by remember { mutableStateOf(ConvertState.SELECT) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Bulk Rename", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Rename .song files by stripping leading numbers and/or using the first verse line",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val dir = pickDirectory()
                if (dir != null) {
                    directory = dir; state = ConvertState.SELECT; preview = emptyList(); logMessages = emptyList()
                }
            }) {
                Icon(Icons.Default.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Select Folder")
            }
            if (directory != null) {
                Text(directory!!.absolutePath, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Checkbox(checked = stripNumbers, onCheckedChange = { stripNumbers = it },
                enabled = state != ConvertState.CONVERTING)
            Text("Strip leading numbers (e.g. \"0111 - Title\" → \"Title\")", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Checkbox(checked = renameToFirstVerse, onCheckedChange = { renameToFirstVerse = it },
                enabled = state != ConvertState.CONVERTING)
            Text("Rename to first line of verse 1", style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                ConvertState.SELECT -> {
                    OutlinedButton(onClick = {
                        preview = buildRenamePreview(directory!!, stripNumbers, renameToFirstVerse)
                        state = ConvertState.PREVIEW
                    }, enabled = directory != null) {
                        Icon(Icons.Default.Preview, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Preview")
                    }
                }
                ConvertState.PREVIEW -> {
                    val renameCount = preview.count { it.file.name != it.newName }
                    Button(onClick = {
                        state = ConvertState.CONVERTING
                        scope.launch {
                            logMessages = withContext(Dispatchers.IO) {
                                preview.filter { it.file.name != it.newName }.map { entry ->
                                    try {
                                        val target = File(entry.file.parentFile, entry.newName)
                                        if (target.exists()) {
                                            "SKIP: ${entry.file.name} → ${entry.newName} (target exists)"
                                        } else {
                                            entry.file.renameTo(target)
                                            "OK: ${entry.file.name} → ${entry.newName}"
                                        }
                                    } catch (e: Exception) { "ERROR: ${entry.file.name} - ${e.message}" }
                                }
                            }
                            state = ConvertState.DONE
                        }
                    }, enabled = renameCount > 0) {
                        Icon(Icons.Default.DriveFileRenameOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Rename $renameCount file(s)")
                    }
                    OutlinedButton(onClick = { state = ConvertState.SELECT; preview = emptyList() }) { Text("Back") }
                }
                ConvertState.CONVERTING -> {
                    Button(enabled = false, onClick = {}) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Renaming...")
                    }
                }
                ConvertState.DONE -> {
                    OutlinedButton(onClick = {
                        state = ConvertState.SELECT; preview = emptyList(); logMessages = emptyList()
                    }) { Text("Start Over") }
                }
            }
        }

        when (state) {
            ConvertState.PREVIEW -> {
                val renameCount = preview.count { it.file.name != it.newName }
                val conflicts = preview.count { it.conflict }
                Text("$renameCount to rename, ${preview.size - renameCount} unchanged" +
                    if (conflicts > 0) ", $conflicts conflicts (will be skipped)" else "",
                    style = MaterialTheme.typography.titleSmall)
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        items(preview.filter { it.file.name != it.newName }) { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (entry.conflict) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.surfaceContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(entry.file.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ArrowForward, null, Modifier.size(14.dp).padding(horizontal = 4.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                    Text(entry.newName, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                    if (entry.conflict) {
                                        Text("conflict", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ConvertState.DONE -> {
                val ok = logMessages.count { it.startsWith("OK") }
                val skipped = logMessages.count { it.startsWith("SKIP") }
                val err = logMessages.count { it.startsWith("ERROR") }
                Text("Done: $ok renamed, $skipped skipped, $err failed", style = MaterialTheme.typography.titleSmall,
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

private val leadingNumberRegex = Regex("""^\d+\s*-\s*""")
private val verseHeaderRegex = Regex("""^\[.+\d.*\]$""", RegexOption.IGNORE_CASE)
private val invalidFilenameChars = Regex("""[\\/:*?"<>|]""")

private fun buildRenamePreview(directory: File, stripNumbers: Boolean, renameToFirstVerse: Boolean): List<RenameEntry> {
    val files = directory.listFiles { f -> f.isFile && f.extension.equals("song", ignoreCase = true) }
        ?.sortedBy { it.name } ?: return emptyList()

    val usedNames = mutableSetOf<String>()
    // Track existing filenames that won't be renamed
    files.forEach { usedNames.add(it.name.lowercase()) }

    return files.map { file ->
        var newBase = file.nameWithoutExtension

        if (stripNumbers) {
            newBase = leadingNumberRegex.replace(newBase, "")
        }
        if (renameToFirstVerse) {
            val firstLine = extractFirstVerseLine(file)
            if (firstLine != null) {
                newBase = sanitizeFilename(firstLine)
            }
        }

        val newName = "$newBase.song"
        val conflict = newName != file.name && (File(file.parentFile, newName).exists() ||
            newName.lowercase() in usedNames && newName.lowercase() != file.name.lowercase())
        usedNames.add(newName.lowercase())
        RenameEntry(file, newName, conflict)
    }
}

private fun extractFirstVerseLine(file: File): String? {
    val content = DuplicateFinder.readFileWithFallback(file)
    val lines = content.lines()
    var frontmatterDone = false
    var inFrontmatter = false
    var foundPrimary = false
    var foundVerse = false

    for (line in lines) {
        val trimmed = line.trim()
        if (!frontmatterDone) {
            if (trimmed == "---") {
                inFrontmatter = !inFrontmatter
                if (!inFrontmatter) frontmatterDone = true
            }
            continue
        }
        if (trimmed.equals("[Primary]", ignoreCase = true)) { foundPrimary = true; continue }
        if (trimmed.equals("[Secondary]", ignoreCase = true)) break
        if (foundPrimary && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            if (verseHeaderRegex.matches(trimmed)) { foundVerse = true; continue }
            if (foundVerse) break // hit next non-verse section after finding a verse
            continue
        }
        if (foundVerse && trimmed.isNotEmpty()) {
            return trimmed
        }
    }
    return null
}

private fun sanitizeFilename(text: String): String {
    return invalidFilenameChars.replace(text, "").trim().take(100)
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
