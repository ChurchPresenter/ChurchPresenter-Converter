package ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import converter.DuplicateFinder
import converter.DuplicateGroup
import converter.SngToSongConverter
import converter.SpsToSongConverter
import converter.XmlToSpbConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun App() {
    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf("Bibles", "Songs", "Duplicates", "Rename")
        val tabIcons = listOf(Icons.Default.Book, Icons.Default.MusicNote, Icons.Default.ContentCopy, Icons.Default.DriveFileRenameOutline)

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
                    0 -> BibleConverterTab()
                    1 -> SongsTab()
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
    var markedForDelete by remember { mutableStateOf<Set<String>>(emptySet()) } // canonical paths
    var deleteLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var compareGroup by remember { mutableStateOf<DuplicateGroup?>(null) }
    var compareLeft by remember { mutableStateOf(0) }
    var compareRight by remember { mutableStateOf(1) }
    var showHomoglyphPrompt by remember { mutableStateOf(false) }
    var pendingHomoglyphFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var matchByNumber by remember { mutableStateOf(false) }
    var matchByTitle by remember { mutableStateOf(true) }
    var threshold by remember { mutableStateOf(0.9f) }
    var filterMinSimilarity by remember { mutableStateOf(0f) }
    var filterMinFiles by remember { mutableStateOf(2) }
    var filterMaxFiles by remember { mutableStateOf(10) }
    var filterCategories by remember { mutableStateOf(setOf("Same song number", "Same title", "Similar lyrics")) }
    val scope = rememberCoroutineScope()

    fun startScan() {
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
    }

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

    // When keep folder changes, auto-mark files outside it for deletion
    LaunchedEffect(keepFolder, filteredGroups) {
        if (keepFolder != null && filteredGroups.isNotEmpty()) {
            val autoMarked = DuplicateFinder.resolveDeletes(filteredGroups, keepFolder!!)
                .map { it.canonicalPath }.toSet()
            markedForDelete = autoMarked
        } else {
            markedForDelete = emptySet()
        }
    }

    val filesToDelete by remember(markedForDelete, filteredGroups) {
        derivedStateOf {
            val allSongFiles = filteredGroups.flatMap { it.songs }.map { it.file }
            allSongFiles.filter { it.canonicalPath in markedForDelete }
        }
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
                        expandedGroups = emptySet(); keepFolder = null; deleteLog = emptyList(); markedForDelete = emptySet()
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
                        // Check for homoglyphs first
                        scope.launch {
                            val hFiles = withContext(Dispatchers.IO) {
                                DuplicateFinder.findHomoglyphFiles(directory!!)
                            }
                            if (hFiles.isNotEmpty()) {
                                scanState = ScanState.IDLE
                                pendingHomoglyphFiles = hFiles
                                showHomoglyphPrompt = true
                            } else {
                                startScan()
                            }
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
                        keepFolder = null; deleteLog = emptyList(); songFolders = emptyList(); markedForDelete = emptySet()
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
                                        onClick = { keepFolder = folder; deleteLog = emptyList(); markedForDelete = emptySet(); keepDropdownExpanded = false },
                                        leadingIcon = {
                                            Icon(if (folder == keepFolder) Icons.Default.CheckCircle else Icons.Default.Folder,
                                                null, Modifier.size(18.dp))
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (filesToDelete.isNotEmpty() && deleteLog.isEmpty()) {
                        Button(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Delete ${filesToDelete.size} selected file(s)")
                        }
                    }
                    if (markedForDelete.isEmpty() && deleteLog.isEmpty()) {
                        Text("Select files to delete in the results panel, or pick a keep folder to auto-select",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (deleteLog.isNotEmpty()) {
                        val deleted = deleteLog.count { it.startsWith("Deleted") }
                        val errors = deleteLog.count { it.startsWith("ERROR") }
                        Text("Done: $deleted deleted, $errors failed",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Button(onClick = {
                            deleteLog = emptyList(); markedForDelete = emptySet()
                            expandedGroups = emptySet(); keepFolder = null
                            startScan()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Rescan")
                        }
                    }
                }

                // Homoglyph fix
                HorizontalDivider()
                var homoglyphFiles by remember { mutableStateOf<List<File>?>(null) }
                var homoglyphLog by remember { mutableStateOf<List<String>>(emptyList()) }
                OutlinedButton(onClick = {
                    scope.launch {
                        homoglyphFiles = withContext(Dispatchers.IO) {
                            DuplicateFinder.findHomoglyphFiles(directory!!)
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = directory != null) {
                    Icon(Icons.Default.TextFormat, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Find Latin/Cyrillic homoglyphs")
                }
                if (homoglyphFiles != null && homoglyphFiles!!.isEmpty()) {
                    Text("No homoglyphs found",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (homoglyphFiles != null && homoglyphFiles!!.isNotEmpty() && homoglyphLog.isEmpty()) {
                    Text("${homoglyphFiles!!.size} file(s) with mixed Latin/Cyrillic characters",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    homoglyphFiles!!.take(5).forEach { f ->
                        Text(f.name, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                    if (homoglyphFiles!!.size > 5) {
                        Text("... and ${homoglyphFiles!!.size - 5} more",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = {
                        scope.launch {
                            homoglyphLog = withContext(Dispatchers.IO) {
                                homoglyphFiles!!.map { f ->
                                    try {
                                        val count = DuplicateFinder.fixHomoglyphs(f)
                                        "Fixed $count chars: ${f.name}"
                                    } catch (e: Exception) { "ERROR: ${f.name} - ${e.message}" }
                                }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Build, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Fix ${homoglyphFiles!!.size} file(s)")
                    }
                    Text("Only fixes lines that are primarily Cyrillic — English lines are left untouched",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (homoglyphLog.isNotEmpty()) {
                    val fixed = homoglyphLog.count { it.startsWith("Fixed") }
                    val errors = homoglyphLog.count { it.startsWith("ERROR") }
                    Text("Done: $fixed fixed, $errors failed",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
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
                        Text("This will permanently delete ${filesToDelete.size} selected file(s).")
                        if (keepFolder != null) {
                            Spacer(Modifier.height(4.dp))
                            Text("Keep folder: ${keepFolder!!.absolutePath}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary)
                        }
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

        // Homoglyph prompt
        if (showHomoglyphPrompt) {
            AlertDialog(
                onDismissRequest = { showHomoglyphPrompt = false },
                title = { Text("Mixed Latin/Cyrillic characters found") },
                text = {
                    Column {
                        Text("${pendingHomoglyphFiles.size} file(s) contain Latin characters mixed into Cyrillic text (homoglyphs). This can prevent duplicates from being detected.")
                        Spacer(Modifier.height(8.dp))
                        Text("Would you like to fix them before scanning?",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("Only Cyrillic lines are affected — English lines are left untouched.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showHomoglyphPrompt = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                pendingHomoglyphFiles.forEach { DuplicateFinder.fixHomoglyphs(it) }
                            }
                            startScan()
                        }
                    }) { Text("Fix & Scan") }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        showHomoglyphPrompt = false
                        startScan()
                    }) { Text("Skip & Scan") }
                }
            )
        }

        // Comparison window
        if (compareGroup != null) {
            val cg = compareGroup!!
            DialogWindow(
                onCloseRequest = { compareGroup = null },
                title = "Compare: ${cg.songs.first().title}",
                resizable = true,
                state = rememberDialogState(size = DpSize(900.dp, 700.dp))
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            // Top bar: file selectors + delete buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left file selector
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    var leftExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        OutlinedButton(onClick = { leftExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                            val lf = cg.songs.getOrNull(compareLeft)?.file
                                            Text(if (lf != null) "${lf.parentFile.name}/${lf.name}" else "Select",
                                                maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                            Spacer(Modifier.width(4.dp))
                                            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                                        }
                                        DropdownMenu(expanded = leftExpanded, onDismissRequest = { leftExpanded = false }) {
                                            cg.songs.forEachIndexed { idx, song ->
                                                DropdownMenuItem(
                                                    text = { Text("${song.file.parentFile.name}/${song.file.name}", style = MaterialTheme.typography.bodySmall) },
                                                    onClick = { compareLeft = idx; leftExpanded = false },
                                                    enabled = idx != compareRight,
                                                    leadingIcon = {
                                                        if (idx == compareLeft) Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    // Delete checkbox + open for left
                                    val leftFile = cg.songs.getOrNull(compareLeft)?.file
                                    val leftPath = leftFile?.canonicalPath
                                    if (leftPath != null) {
                                        val leftMarked = leftPath in markedForDelete
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                            Checkbox(
                                                checked = leftMarked,
                                                onCheckedChange = {
                                                    markedForDelete = if (leftMarked) markedForDelete - leftPath else markedForDelete + leftPath
                                                },
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Mark for deletion", style = MaterialTheme.typography.labelSmall,
                                                color = if (leftMarked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.weight(1f))
                                            TextButton(onClick = { Desktop.getDesktop().open(leftFile) },
                                                modifier = Modifier.height(24.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                                Icon(Icons.Default.OpenInNew, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                                Text("Open", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                                // Right file selector
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Right", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    var rightExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        OutlinedButton(onClick = { rightExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                            val rf = cg.songs.getOrNull(compareRight)?.file
                                            Text(if (rf != null) "${rf.parentFile.name}/${rf.name}" else "Select",
                                                maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                            Spacer(Modifier.width(4.dp))
                                            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                                        }
                                        DropdownMenu(expanded = rightExpanded, onDismissRequest = { rightExpanded = false }) {
                                            cg.songs.forEachIndexed { idx, song ->
                                                DropdownMenuItem(
                                                    text = { Text("${song.file.parentFile.name}/${song.file.name}", style = MaterialTheme.typography.bodySmall) },
                                                    onClick = { compareRight = idx; rightExpanded = false },
                                                    enabled = idx != compareLeft,
                                                    leadingIcon = {
                                                        if (idx == compareRight) Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    // Delete checkbox + open for right
                                    val rightFile = cg.songs.getOrNull(compareRight)?.file
                                    val rightPath = rightFile?.canonicalPath
                                    if (rightPath != null) {
                                        val rightMarked = rightPath in markedForDelete
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                            Checkbox(
                                                checked = rightMarked,
                                                onCheckedChange = {
                                                    markedForDelete = if (rightMarked) markedForDelete - rightPath else markedForDelete + rightPath
                                                },
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Mark for deletion", style = MaterialTheme.typography.labelSmall,
                                                color = if (rightMarked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.weight(1f))
                                            TextButton(onClick = { Desktop.getDesktop().open(rightFile) },
                                                modifier = Modifier.height(24.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                                Icon(Icons.Default.OpenInNew, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                                Text("Open", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Section summary
                            val leftSong = cg.songs.getOrNull(compareLeft)
                            val rightSong = cg.songs.getOrNull(compareRight)
                            if (leftSong != null && rightSong != null) {
                                val allSections = (leftSong.sections + rightSong.sections).distinct()
                                val leftMissing = allSections - leftSong.sections.toSet()
                                val rightMissing = allSections - rightSong.sections.toSet()
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${leftSong.sections.size} sections, ${leftSong.lyricsText.lines().size} lines",
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (leftMissing.isNotEmpty()) {
                                            Text("Missing: ${leftMissing.joinToString(", ")}",
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${rightSong.sections.size} sections, ${rightSong.lyricsText.lines().size} lines",
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (rightMissing.isNotEmpty()) {
                                            Text("Missing: ${rightMissing.joinToString(", ")}",
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(4.dp))

                                // Side-by-side diff
                                val diffRows = computeSideBySide(leftSong.lyricsText.lines(), rightSong.lyricsText.lines())
                                val diffScrollV = rememberScrollState()
                                val monoStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                val addBg = Color(0xFF1B3A2A)
                                val delBg = Color(0xFF3A1B1B)
                                val emptyBg = MaterialTheme.colorScheme.surfaceContainerLow
                                val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                val dividerColor = MaterialTheme.colorScheme.outlineVariant

                                Box(
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(1.dp, dividerColor, RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                ) {
                                    Column(modifier = Modifier.verticalScroll(diffScrollV)) {
                                        diffRows.forEach { row ->
                                            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                                // Left side
                                                val leftBg = when {
                                                    row.leftText == null -> emptyBg
                                                    row.leftType == DiffType.DEL -> delBg
                                                    else -> Color.Transparent
                                                }
                                                val leftColor = when (row.leftType) {
                                                    DiffType.DEL -> Color(0xFFE27E7E)
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                }
                                                Row(
                                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                                        .background(leftBg).padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        row.leftNum?.toString()?.padStart(4) ?: "    ",
                                                        style = monoStyle, color = gutterColor,
                                                        modifier = Modifier.width(36.dp)
                                                    )
                                                    Text(
                                                        row.leftText ?: "",
                                                        style = monoStyle, color = leftColor,
                                                        softWrap = false
                                                    )
                                                }
                                                // Divider
                                                Box(Modifier.width(1.dp).fillMaxHeight().background(dividerColor))
                                                // Right side
                                                val rightBg = when {
                                                    row.rightText == null -> emptyBg
                                                    row.rightType == DiffType.ADD -> addBg
                                                    else -> Color.Transparent
                                                }
                                                val rightColor = when (row.rightType) {
                                                    DiffType.ADD -> Color(0xFF7EE2A8)
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                }
                                                Row(
                                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                                        .background(rightBg).padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        row.rightNum?.toString()?.padStart(4) ?: "    ",
                                                        style = monoStyle, color = gutterColor,
                                                        modifier = Modifier.width(36.dp)
                                                    )
                                                    Text(
                                                        row.rightText ?: "",
                                                        style = monoStyle, color = rightColor,
                                                        softWrap = false
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Showing ${filteredGroups.size} of ${duplicateGroups.size} group(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        // Select duplicates in same folder
                        TextButton(onClick = {
                            val toMark = mutableSetOf<String>()
                            for (group in filteredGroups) {
                                // Group songs by folder, mark all but one per folder
                                val byFolder = group.songs.groupBy { it.file.parentFile.canonicalPath }
                                for ((_, songsInFolder) in byFolder) {
                                    if (songsInFolder.size > 1) {
                                        songsInFolder.drop(1).forEach { toMark.add(it.file.canonicalPath) }
                                    }
                                }
                            }
                            markedForDelete = markedForDelete + toMark
                        }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text("Select same-folder dupes", style = MaterialTheme.typography.labelSmall)
                        }
                        // Expand/Collapse all
                        TextButton(onClick = {
                            expandedGroups = if (expandedGroups.size >= filteredGroups.size)
                                emptySet() else filteredGroups.indices.toSet()
                        }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(
                                if (expandedGroups.size >= filteredGroups.size) "Collapse all" else "Expand all",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

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
                                            IconButton(onClick = { compareLeft = 0; compareRight = minOf(1, group.songs.size - 1); compareGroup = group }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Default.CompareArrows, "Compare", Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }

                                if (groupIdx in expandedGroups) {
                                    items(group.songs.size, key = { "song_${groupIdx}_$it" }) { songIdx ->
                                        val song = group.songs[songIdx]
                                        val canonPath = song.file.canonicalPath
                                        val isMarked = canonPath in markedForDelete
                                        val isKept = keepFolder != null &&
                                                canonPath.startsWith(keepFolder!!.canonicalPath)
                                        Card(
                                            modifier = Modifier.fillMaxWidth().padding(start = 28.dp).clickable {
                                                markedForDelete = if (isMarked) markedForDelete - canonPath
                                                    else markedForDelete + canonPath
                                            },
                                            colors = CardDefaults.cardColors(
                                                containerColor = when {
                                                    isMarked -> MaterialTheme.colorScheme.errorContainer
                                                    isKept -> MaterialTheme.colorScheme.primaryContainer
                                                    else -> MaterialTheme.colorScheme.surface
                                                }
                                            )
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Checkbox(
                                                        checked = isMarked,
                                                        onCheckedChange = {
                                                            markedForDelete = if (isMarked) markedForDelete - canonPath
                                                                else markedForDelete + canonPath
                                                        },
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(song.file.name, style = MaterialTheme.typography.bodyMedium)
                                                    if (isKept && !isMarked) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("KEEP", style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary)
                                                    }
                                                    if (isMarked) {
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
                                                    modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                                                )
                                                // Show sections and missing verses
                                                if (song.sections.isNotEmpty()) {
                                                    val allSections = group.songs.flatMap { it.sections }.distinct()
                                                    val missing = allSections - song.sections.toSet()
                                                    Text(
                                                        "Sections: ${song.sections.joinToString(", ")}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                                                    )
                                                    if (missing.isNotEmpty()) {
                                                        Text(
                                                            "Missing: ${missing.joinToString(", ")}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.padding(start = 26.dp, top = 1.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    song.file.absolutePath,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(start = 26.dp, top = 2.dp)
                                                )
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
    var caseOption by remember { mutableStateOf("None") } // None, Title Case, lowercase, UPPERCASE
    var preview by remember { mutableStateOf<List<RenameEntry>>(emptyList()) }
    var logMessages by remember { mutableStateOf<List<String>>(emptyList()) }
    var state by remember { mutableStateOf(ConvertState.SELECT) }
    var renameCompareFiles by remember { mutableStateOf<List<File>?>(null) }
    var renameCompareLeft by remember { mutableStateOf(0) }
    var renameCompareRight by remember { mutableStateOf(1) }
    var renameMarkedForDelete by remember { mutableStateOf<Set<String>>(emptySet()) }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Case:", style = MaterialTheme.typography.bodySmall)
            listOf("None", "Sentence case", "Title Case", "lowercase", "UPPERCASE").forEach { opt ->
                FilterChip(
                    selected = caseOption == opt,
                    onClick = { caseOption = opt },
                    label = { Text(opt, style = MaterialTheme.typography.labelSmall) },
                    enabled = state != ConvertState.CONVERTING
                )
            }
        }

        // Live-update preview when options change
        LaunchedEffect(stripNumbers, renameToFirstVerse, caseOption) {
            if (state == ConvertState.PREVIEW && directory != null) {
                preview = withContext(Dispatchers.IO) {
                    buildRenamePreview(directory!!, stripNumbers, renameToFirstVerse, caseOption)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                ConvertState.SELECT -> {
                    OutlinedButton(onClick = {
                        preview = buildRenamePreview(directory!!, stripNumbers, renameToFirstVerse, caseOption)
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
                                        val isCaseOnly = entry.newName.equals(entry.file.name, ignoreCase = true)
                                        if (isCaseOnly) {
                                            // Windows: case-only rename needs a temp intermediate
                                            val temp = File(entry.file.parentFile, entry.file.name + ".tmp_rename")
                                            entry.file.renameTo(temp)
                                            temp.renameTo(target)
                                            "OK: ${entry.file.name} → ${entry.newName}"
                                        } else if (target.exists()) {
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
                // Delete marked files button
                if (renameMarkedForDelete.isNotEmpty()) {
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    Button(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Delete ${renameMarkedForDelete.size} marked file(s)")
                    }
                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            title = { Text("Delete files?") },
                            text = { Text("Permanently delete ${renameMarkedForDelete.size} file(s)?") },
                            confirmButton = {
                                Button(onClick = {
                                    showDeleteConfirm = false
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            renameMarkedForDelete.forEach { path -> File(path).delete() }
                                        }
                                        renameMarkedForDelete = emptySet()
                                        preview = buildRenamePreview(directory!!, stripNumbers, renameToFirstVerse, caseOption)
                                    }
                                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                            },
                            dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
                        )
                    }
                }
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
                                    containerColor = when {
                                        entry.file.canonicalPath in renameMarkedForDelete -> MaterialTheme.colorScheme.errorContainer
                                        entry.conflict -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                        else -> MaterialTheme.colorScheme.surfaceContainer
                                    }
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
                                    // Find all files that rename to the same name in the same folder
                                    val sameNameFiles = preview.filter { other ->
                                        other.file !== entry.file &&
                                        other.newName.equals(entry.newName, ignoreCase = true) &&
                                        other.file.parentFile.canonicalPath == entry.file.parentFile.canonicalPath
                                    }
                                    if (sameNameFiles.isNotEmpty()) {
                                        IconButton(onClick = {
                                            val allFiles = listOf(entry.file) + sameNameFiles.map { it.file }
                                            renameCompareFiles = allFiles
                                            renameCompareLeft = 0
                                            renameCompareRight = 1
                                        }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.CompareArrows, "Compare", Modifier.size(16.dp))
                                        }
                                    }
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

    // Compare dialog for rename conflicts
    if (renameCompareFiles != null && renameCompareFiles!!.size >= 2) {
        val cFiles = renameCompareFiles!!
        val cSongs = remember(cFiles) {
            cFiles.map { f ->
                try { DuplicateFinder.readFileWithFallback(f) } catch (_: Exception) { "" }
            }
        }
        DialogWindow(
            onCloseRequest = { renameCompareFiles = null },
            title = "Compare: ${cFiles.first().nameWithoutExtension}",
            resizable = true,
            state = rememberDialogState(size = DpSize(900.dp, 700.dp))
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        // File selectors + delete checkboxes
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Left
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                var leftExp by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { leftExp = true }, modifier = Modifier.fillMaxWidth()) {
                                        val lf = cFiles.getOrNull(renameCompareLeft)
                                        Text(if (lf != null) "${lf.parentFile.name}/${lf.name}" else "", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.width(4.dp)); Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                                    }
                                    DropdownMenu(expanded = leftExp, onDismissRequest = { leftExp = false }) {
                                        cFiles.forEachIndexed { idx, f ->
                                            DropdownMenuItem(text = { Text("${f.parentFile.name}/${f.name}", style = MaterialTheme.typography.bodySmall) },
                                                onClick = { renameCompareLeft = idx; leftExp = false }, enabled = idx != renameCompareRight)
                                        }
                                    }
                                }
                                val leftFileR = cFiles.getOrNull(renameCompareLeft)
                                val leftPath = leftFileR?.canonicalPath
                                if (leftPath != null) {
                                    val leftMarked = leftPath in renameMarkedForDelete
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                        Checkbox(checked = leftMarked, onCheckedChange = {
                                            renameMarkedForDelete = if (leftMarked) renameMarkedForDelete - leftPath else renameMarkedForDelete + leftPath
                                        }, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Mark for deletion", style = MaterialTheme.typography.labelSmall,
                                            color = if (leftMarked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = { Desktop.getDesktop().open(leftFileR) },
                                            modifier = Modifier.height(24.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                            Icon(Icons.Default.OpenInNew, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                            Text("Open", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            // Right
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Right", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                var rightExp by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { rightExp = true }, modifier = Modifier.fillMaxWidth()) {
                                        val rf = cFiles.getOrNull(renameCompareRight)
                                        Text(if (rf != null) "${rf.parentFile.name}/${rf.name}" else "", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.width(4.dp)); Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                                    }
                                    DropdownMenu(expanded = rightExp, onDismissRequest = { rightExp = false }) {
                                        cFiles.forEachIndexed { idx, f ->
                                            DropdownMenuItem(text = { Text("${f.parentFile.name}/${f.name}", style = MaterialTheme.typography.bodySmall) },
                                                onClick = { renameCompareRight = idx; rightExp = false }, enabled = idx != renameCompareLeft)
                                        }
                                    }
                                }
                                val rightFileR = cFiles.getOrNull(renameCompareRight)
                                val rightPath = rightFileR?.canonicalPath
                                if (rightPath != null) {
                                    val rightMarked = rightPath in renameMarkedForDelete
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                        Checkbox(checked = rightMarked, onCheckedChange = {
                                            renameMarkedForDelete = if (rightMarked) renameMarkedForDelete - rightPath else renameMarkedForDelete + rightPath
                                        }, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Mark for deletion", style = MaterialTheme.typography.labelSmall,
                                            color = if (rightMarked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = { Desktop.getDesktop().open(rightFileR) },
                                            modifier = Modifier.height(24.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                            Icon(Icons.Default.OpenInNew, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                            Text("Open", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))

                        // Side-by-side diff
                        val leftContent = cSongs.getOrNull(renameCompareLeft) ?: ""
                        val rightContent = cSongs.getOrNull(renameCompareRight) ?: ""
                        val diffRows = computeSideBySide(leftContent.lines(), rightContent.lines())
                        val diffScrollV = rememberScrollState()
                        val monoStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        val addBg = Color(0xFF1B3A2A)
                        val delBg = Color(0xFF3A1B1B)
                        val emptyBg = MaterialTheme.colorScheme.surfaceContainerLow
                        val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        val dividerColor = MaterialTheme.colorScheme.outlineVariant

                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, dividerColor, RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        ) {
                            Column(modifier = Modifier.verticalScroll(diffScrollV)) {
                                diffRows.forEach { row ->
                                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                        val leftBgC = when {
                                            row.leftText == null -> emptyBg
                                            row.leftType == DiffType.DEL -> delBg
                                            else -> Color.Transparent
                                        }
                                        val leftColor = if (row.leftType == DiffType.DEL) Color(0xFFE27E7E) else MaterialTheme.colorScheme.onSurface
                                        Row(modifier = Modifier.weight(1f).fillMaxHeight().background(leftBgC).padding(horizontal = 4.dp, vertical = 1.dp)) {
                                            Text(row.leftNum?.toString()?.padStart(4) ?: "    ", style = monoStyle, color = gutterColor, modifier = Modifier.width(36.dp))
                                            Text(row.leftText ?: "", style = monoStyle, color = leftColor, softWrap = false)
                                        }
                                        Box(Modifier.width(1.dp).fillMaxHeight().background(dividerColor))
                                        val rightBgC = when {
                                            row.rightText == null -> emptyBg
                                            row.rightType == DiffType.ADD -> addBg
                                            else -> Color.Transparent
                                        }
                                        val rightColor = if (row.rightType == DiffType.ADD) Color(0xFF7EE2A8) else MaterialTheme.colorScheme.onSurface
                                        Row(modifier = Modifier.weight(1f).fillMaxHeight().background(rightBgC).padding(horizontal = 4.dp, vertical = 1.dp)) {
                                            Text(row.rightNum?.toString()?.padStart(4) ?: "    ", style = monoStyle, color = gutterColor, modifier = Modifier.width(36.dp))
                                            Text(row.rightText ?: "", style = monoStyle, color = rightColor, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val leadingNumberRegex = Regex("""^\d+\s*-\s*""")
private val verseHeaderRegex = Regex("""^\[.+\d.*\]$""", RegexOption.IGNORE_CASE)
private val invalidFilenameChars = Regex("""[\\/:*?"<>|]""")

private fun applyCase(name: String, caseOption: String): String = when (caseOption) {
    "Sentence case" -> name.lowercase().replaceFirstChar { it.titlecase() }
    "Title Case" -> name.split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
    "lowercase" -> name.lowercase()
    "UPPERCASE" -> name.uppercase()
    else -> name
}

private fun buildRenamePreview(directory: File, stripNumbers: Boolean, renameToFirstVerse: Boolean, caseOption: String = "None"): List<RenameEntry> {
    val files = directory.walkTopDown()
        .filter { it.isFile && it.extension.equals("song", ignoreCase = true) }
        .sortedBy { it.absolutePath }
        .toList()
    if (files.isEmpty()) return emptyList()

    // Track used names per parent folder to detect conflicts within each directory
    val usedNamesPerFolder = mutableMapOf<File, MutableSet<String>>()
    files.forEach { f ->
        usedNamesPerFolder.getOrPut(f.parentFile) { mutableSetOf() }.add(f.name.lowercase())
    }

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
        if (caseOption != "None") {
            newBase = applyCase(newBase, caseOption)
        }

        val newName = "$newBase.song"
        val folderNames = usedNamesPerFolder.getOrPut(file.parentFile) { mutableSetOf() }
        val isCaseOnlyChange = newName.equals(file.name, ignoreCase = true) && newName != file.name
        val conflict = newName != file.name && !isCaseOnlyChange && (File(file.parentFile, newName).exists() ||
            newName.lowercase() in folderNames && newName.lowercase() != file.name.lowercase())
        folderNames.add(newName.lowercase())
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

private val defaultDir: File = File(System.getProperty("user.home"), "Downloads")

private fun pickFiles(description: String, extension: String, multiSelection: Boolean): List<File> {
    val chooser = JFileChooser(defaultDir).apply {
        fileFilter = FileNameExtensionFilter(description, extension)
        isMultiSelectionEnabled = multiSelection
        dialogTitle = "Select $description"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        if (multiSelection) chooser.selectedFiles.toList() else listOfNotNull(chooser.selectedFile)
    } else emptyList()
}

private fun pickDirectory(): File? {
    val chooser = JFileChooser(defaultDir).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Select Folder"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun findXmlFilesRecursive(dir: File): List<File> {
    return dir.walkTopDown().filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }.toList()
}

// =============================================================================
// Diff engine
// =============================================================================

enum class DiffType { SAME, ADD, DEL }

data class SideBySideRow(
    val leftNum: Int? = null,
    val leftText: String? = null,
    val leftType: DiffType = DiffType.SAME,
    val rightNum: Int? = null,
    val rightText: String? = null,
    val rightType: DiffType = DiffType.SAME
)

/** LCS-based side-by-side diff with aligned matching lines. */
private fun computeSideBySide(leftLines: List<String>, rightLines: List<String>): List<SideBySideRow> {
    val n = leftLines.size
    val m = rightLines.size

    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in 1..n) {
        for (j in 1..m) {
            dp[i][j] = if (leftLines[i - 1] == rightLines[j - 1]) dp[i - 1][j - 1] + 1
            else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
    }

    // Backtrack to get edit operations
    data class Op(val type: DiffType, val text: String, val li: Int, val ri: Int)
    val ops = mutableListOf<Op>()
    var i = n; var j = m
    while (i > 0 || j > 0) {
        when {
            i > 0 && j > 0 && leftLines[i - 1] == rightLines[j - 1] -> {
                ops.add(Op(DiffType.SAME, leftLines[i - 1], i, j)); i--; j--
            }
            j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                ops.add(Op(DiffType.ADD, rightLines[j - 1], 0, j)); j--
            }
            else -> {
                ops.add(Op(DiffType.DEL, leftLines[i - 1], i, 0)); i--
            }
        }
    }
    ops.reverse()

    // Convert to side-by-side rows, pairing adjacent DEL+ADD as modifications
    val rows = mutableListOf<SideBySideRow>()
    var idx = 0
    while (idx < ops.size) {
        val op = ops[idx]
        when (op.type) {
            DiffType.SAME -> {
                rows.add(SideBySideRow(op.li, op.text, DiffType.SAME, op.ri, op.text, DiffType.SAME))
                idx++
            }
            DiffType.DEL -> {
                // Collect consecutive DELs and ADDs to pair them
                val dels = mutableListOf<Op>()
                while (idx < ops.size && ops[idx].type == DiffType.DEL) { dels.add(ops[idx]); idx++ }
                val adds = mutableListOf<Op>()
                while (idx < ops.size && ops[idx].type == DiffType.ADD) { adds.add(ops[idx]); idx++ }
                val maxLen = maxOf(dels.size, adds.size)
                for (k in 0 until maxLen) {
                    val d = dels.getOrNull(k)
                    val a = adds.getOrNull(k)
                    rows.add(SideBySideRow(
                        leftNum = d?.li, leftText = d?.text, leftType = if (d != null) DiffType.DEL else DiffType.SAME,
                        rightNum = a?.ri, rightText = a?.text, rightType = if (a != null) DiffType.ADD else DiffType.SAME
                    ))
                }
            }
            DiffType.ADD -> {
                rows.add(SideBySideRow(rightNum = op.ri, rightText = op.text, rightType = DiffType.ADD))
                idx++
            }
        }
    }
    return rows
}
