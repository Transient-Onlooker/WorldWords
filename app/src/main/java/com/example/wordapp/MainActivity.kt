package com.example.wordapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.BufferedWriter
import android.widget.Toast
import android.util.Base64
import com.example.wordapp.Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.engine.android.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import io.ktor.client.call.*
import kotlinx.serialization.Serializable
import com.example.wordapp.ui.theme.WordAppTheme
import com.example.wordapp.TTSManager
import com.example.wordapp.ExtensionManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp

@Serializable
data class GithubFile(val name: String, val download_url: String?, val url: String)

@Serializable
data class GithubFileContent(val content: String? = null, val encoding: String? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WordAppTheme { Surface(Modifier.fillMaxSize()) { RootApp() } } }
    }
}

fun findDuplicates(wordList: List<WordPair>): Map<Pair<String, String>, List<WordPair>> {
    return wordList.groupBy { Pair(it.eng, it.kor) }
        .filter { it.value.size > 1 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootApp() {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        TTSManager.init(context, java.util.Locale.ENGLISH)
    }

    DisposableEffect(Unit) {
        onDispose {
            TTSManager.shutdown()
        }
    }

    val scope = rememberCoroutineScope()
    val wordList = remember { mutableStateListOf<WordPair>() }
    var folderName by remember { mutableStateOf("EBS 단어장") }
    var fileName by remember { mutableStateOf("Day 1") }
    val baseDir: File = remember { context.getExternalFilesDir(null) ?: context.filesDir }
    var inputText by remember { mutableStateOf("") }
    var editIndex by remember { mutableIntStateOf(-1) }
    var currentScreen by remember { mutableStateOf(Screen.WORDS) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var duplicateWordsInfo by remember { mutableStateOf<Map<Pair<String, String>, List<WordPair>>>(emptyMap()) }
    var originalLoadedWords by remember { mutableStateOf<List<WordPair>>(emptyList()) }
    var wordListGeneration by remember { mutableIntStateOf(0) }

    // State for passing a specific list of words to the quiz screen
    var quizOverrideList by remember { mutableStateOf<List<WordPair>?>(null) }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            val loaded = readWordsFromUri(context, uri)

            val duplicates = findDuplicates(loaded)
            if (duplicates.isNotEmpty()) {
                originalLoadedWords = loaded
                duplicateWordsInfo = duplicates
                showDuplicateDialog = true
            } else {
                wordList.clear()
                wordList.addAll(loaded)
                editIndex = -1
                inputText = ""
                wordListGeneration++
            }
            getDisplayName(context, uri)?.let { name ->
                fileName = name.removeSuffix(".txt").removeSuffix(".TXT")
            }
            guessFolderNameFromUri(uri)?.let { guessed ->
                if (guessed.isNotBlank()) folderName = guessed
            }
        }
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            saveWordsToUri(context, uri, wordList)
        }
    }

    var cloudWordsToSave by remember { mutableStateOf<List<WordPair>?>(null) }
    val saveCloudWordsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            cloudWordsToSave?.let { words ->
                saveWordsToUri(context, uri, words)
            }
            cloudWordsToSave = null // Clear after saving
        }
    }

    val mergeDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val loaded = readWordsFromUri(context, uri)
            val mergedList = (wordList + loaded)
            val duplicates = findDuplicates(mergedList)

            if (duplicates.isNotEmpty()) {
                originalLoadedWords = mergedList
                duplicateWordsInfo = duplicates
                showDuplicateDialog = true
            } else {
                wordList.clear()
                wordList.addAll(mergedList)
                Toast.makeText(context, "${loaded.size}개의 새 단어를 병합했습니다.", Toast.LENGTH_SHORT).show()
                wordListGeneration++
            }
        }
    }

    var unknownWordsToSave by remember { mutableStateOf<List<WordPair>?>(null) }
    val saveUnknownWordsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            unknownWordsToSave?.let { words ->
                saveWordsToUri(context, uri, words)
            }
            unknownWordsToSave = null // Clear after saving
        }
    }

    var incompleteWordsToSave by remember { mutableStateOf<List<WordPair>?>(null) }
    val saveIncompleteWordsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            incompleteWordsToSave?.let { words ->
                saveWordsToUri(context, uri, words)
            }
            incompleteWordsToSave = null // Clear after saving
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var showOverflow by remember { mutableStateOf(false) }

    if (showDuplicateDialog) {
        DuplicateWarningDialog(
            duplicateCount = duplicateWordsInfo.values.sumOf { it.size - 1 },
            onDismiss = { showDuplicateDialog = false },
            onRemoveDuplicates = {
                val uniqueWords = originalLoadedWords.distinctBy { Pair(it.eng, it.kor) }
                wordList.clear()
                wordList.addAll(uniqueWords)
                Toast.makeText(context, "중복 단어가 제거되었습니다.", Toast.LENGTH_SHORT).show()
                showDuplicateDialog = false
                wordListGeneration++
            },
            onIgnoreDuplicates = {
                wordList.clear()
                wordList.addAll(originalLoadedWords)
                showDuplicateDialog = false
                wordListGeneration++
            },
            onViewDetails = {
                currentScreen = Screen.DUPLICATE_DETAILS
                showDuplicateDialog = false
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "카테고리",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                NavigationDrawerItem(
                    label = { Text("단어장") },
                    selected = currentScreen == Screen.WORDS,
                    onClick = {
                        currentScreen = Screen.WORDS
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                NavigationDrawerItem(
                    label = { Text("플래시카드") },
                    selected = currentScreen == Screen.FLASHCARD,
                    onClick = {
                        currentScreen = Screen.FLASHCARD
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                NavigationDrawerItem(
                    label = { Text("퀴즈") },
                    selected = currentScreen == Screen.QUIZ,
                    onClick = {
                        currentScreen = Screen.QUIZ
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                NavigationDrawerItem(
                    label = { Text("예문 퀴즈") },
                    selected = currentScreen == Screen.EXAMPLE_QUIZ,
                    onClick = {
                        currentScreen = Screen.EXAMPLE_QUIZ
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )

                val enabledExtensions = ExtensionManager.getBuiltInExtensions().filter { SettingsManager.isExtensionEnabled(context, it.id) }
                if (enabledExtensions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "확장 프로그램",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    enabledExtensions.filter { it.screen != Screen.SETTINGS }.forEach { extension ->
                        NavigationDrawerItem(
                            label = { Text(extension.name) },
                            selected = currentScreen == extension.screen,
                            onClick = {
                                currentScreen = extension.screen
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                NavigationDrawerItem(
                    label = { Text("확장 프로그램") },
                    selected = currentScreen == Screen.EXTENSIONS,
                    onClick = {
                        currentScreen = Screen.EXTENSIONS
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )

                NavigationDrawerItem(
                    label = { Text("개발자에게 제보하기") },
                    selected = false, // This item doesn't represent a screen
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("mailto:")
                            putExtra(Intent.EXTRA_EMAIL, arrayOf("junuh145858@gmail.com"))
                            putExtra(Intent.EXTRA_SUBJECT, "단어장 앱 문의")
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "이메일 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )

                NavigationDrawerItem(
                    label = { Text("설정") },
                    selected = currentScreen == Screen.SETTINGS,
                    onClick = {
                        currentScreen = Screen.SETTINGS
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )

            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                if (drawerState.isClosed) drawerState.open() else drawerState.close()
                            }
                        }) { Text("≡", style = MaterialTheme.typography.titleLarge) } // Using a character for menu icon
                    },
                    title = {
                        val title = when (currentScreen) {
                            Screen.WORDS -> "WorldWords V1.0.0"
                            Screen.FLASHCARD -> "플래시카드"
                            Screen.QUIZ -> "퀴즈"
                            Screen.EXAMPLE_QUIZ -> "예문 퀴즈"
                            Screen.SETTINGS -> "설정"
                            Screen.EXTENSIONS -> "확장 프로그램"
                            Screen.DUPLICATE_DETAILS -> "중복 단어 상세 정보"
                            Screen.CLOUD_WORDBOOK -> "클라우드 단어장"
                        }
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        if (currentScreen == Screen.WORDS || currentScreen == Screen.FLASHCARD) {
                            Box {
                                IconButton(onClick = { showOverflow = true }) {
                                    Text("⋮") // Using a character for more options icon
                                }
                                AppOverflowMenu(
                                    expanded = showOverflow,
                                    onExpandedChange = { showOverflow = it },
                                    onPickToLoad = {
                                        openDocLauncher.launch(arrayOf("text/plain", "text/*"))
                                    },
                                    onMerge = {
                                        if (wordList.isEmpty()) {
                                            Toast.makeText(context, "먼저 병합할 1번째 단어장을 선택해주세요.", Toast.LENGTH_SHORT).show()
                                            openDocLauncher.launch(arrayOf("text/plain", "text/*"))
                                        } else {
                                            Toast.makeText(context, "병합할 2번째 단어장을 선택해주세요.", Toast.LENGTH_SHORT).show()
                                            mergeDocLauncher.launch(arrayOf("text/plain", "text/*"))
                                        }
                                    },
                                    onPickToSave = { createDocLauncher.launch("$fileName.txt") },
                                    onLoadFromAppFolder = {
                                        val loaded = loadWordsFromAppSpecificStorage(context)
                                        val filtered = loaded.filterNot { isInvalidEnglishWord(it.eng) }

                                        val duplicates = findDuplicates(filtered)
                                        if (duplicates.isNotEmpty()) {
                                            originalLoadedWords = filtered
                                            duplicateWordsInfo = duplicates
                                            showDuplicateDialog = true
                                        } else {
                                            wordList.clear()
                                            wordList.addAll(filtered)
                                            wordListGeneration++
                                        }
                                    },
                                    onSaveToAppFolder = {
                                        saveWordsToAppSpecificStorage(context, wordList)
                                    },
                                    onClearAll = {
                                        wordList.clear()
                                        wordListGeneration++
                                    }
                                )
                            }
                        }
                    }
                )
            }
        ) {
            val baseModifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(16.dp)

            when (currentScreen) {
                Screen.WORDS -> WordsScreen(
                    modifier = baseModifier,
                    wordList = wordList,
                    inputText = inputText,
                    onInputTextChange = { inputText = it },
                    editIndex = editIndex,
                    setEditIndex = { editIndex = it },
                    folderName = folderName,
                    onFolderNameChange = { folderName = it },
                    fileName = fileName,
                    onFileNameChange = { fileName = it },
                    baseDir = baseDir
                )

                Screen.FLASHCARD -> FlashcardScreen(
                    modifier = baseModifier,
                    wordList = wordList,
                    generation = wordListGeneration,
                    fileName = fileName,
                    onSaveUnknownWords = { wordsToSave ->
                        unknownWordsToSave = wordsToSave
                        val newFileName = "${fileName}_모르겠음.txt"
                        saveUnknownWordsLauncher.launch(newFileName)
                    },
                    onSaveIncompleteWords = { wordsToSave ->
                        incompleteWordsToSave = wordsToSave
                        val newFileName = "${fileName}_미완료.txt"
                        saveIncompleteWordsLauncher.launch(newFileName)
                    },
                    onStartQuizWithWords = { words ->
                        quizOverrideList = words
                        currentScreen = Screen.QUIZ
                    }
                )

                Screen.QUIZ -> QuizHostScreen(
                    modifier = baseModifier,
                    wholeList = wordList,
                    fileName = fileName,
                    onFileNameChange = { fileName = it },
                    quizOverrideList = quizOverrideList,
                    onConsumeOverrideList = { quizOverrideList = null }
                )

                Screen.EXAMPLE_QUIZ -> ExampleQuizHostScreen(
                    modifier = baseModifier,
                    fileName = fileName,
                    onFileNameChange = { fileName = it }
                )
                Screen.SETTINGS -> SettingsScreen(modifier = baseModifier)
                Screen.EXTENSIONS -> ExtensionsScreen(modifier = baseModifier)
                Screen.DUPLICATE_DETAILS -> DuplicateDetailsScreen(
                    modifier = baseModifier,
                    duplicateInfo = duplicateWordsInfo,
                    onRemoveDuplicates = {
                        val uniqueWords = originalLoadedWords.distinctBy { Pair(it.eng, it.kor) }
                        wordList.clear()
                        wordList.addAll(uniqueWords)
                        Toast.makeText(context, "중복 단어가 제거되었습니다.", Toast.LENGTH_SHORT).show()
                        currentScreen = Screen.WORDS
                    },
                    onGoBack = {
                        wordList.clear()
                        wordList.addAll(originalLoadedWords)
                        currentScreen = Screen.WORDS
                    }
                )
                Screen.CLOUD_WORDBOOK -> CloudWordbookScreen(
                    modifier = baseModifier,
                    onApply = {
                        wordList.clear()
                        wordList.addAll(it)
                        currentScreen = Screen.WORDS
                        Toast.makeText(context, "${it.size}개의 단어를 적용했습니다.", Toast.LENGTH_SHORT).show()
                    },
                    saveCloudWordsLauncher = saveCloudWordsLauncher,
                    onSetCloudWordsToSave = { cloudWordsToSave = it }
                )
            }
        }
    }
}




@Composable
fun SettingsScreen(modifier: Modifier) {
    val context = LocalContext.current
    var vibrationEnabled by remember { mutableStateOf(SettingsManager.isVibrationEnabled(context)) }
    var mc4AutoAdvanceEnabled by remember { mutableStateOf(SettingsManager.isMc4AutoAdvanceEnabled(context)) }
    var mc4AutoAdvanceDelay by remember { mutableStateOf(SettingsManager.getMc4AutoAdvanceDelay(context)) }

    var themeSetting by remember { mutableStateOf(SettingsManager.getThemeSetting(context)) }
    val isSystemInDark = isSystemInDarkTheme()

    val showLightColorSettings = themeSetting == "light" || (themeSetting == "system" && !isSystemInDark)
    val showDarkColorSettings = themeSetting == "dark" || (themeSetting == "system" && isSystemInDark)

    var showPrimaryColorPicker by remember { mutableStateOf(false) }
    var showSecondaryColorPicker by remember { mutableStateOf(false) }
    var primaryColor by remember { mutableStateOf(Color(SettingsManager.getPrimaryColor(context))) }
    var secondaryColor by remember { mutableStateOf(Color(SettingsManager.getSecondaryContainerColor(context))) }

    var primaryDarkColor by remember { mutableStateOf(Color(SettingsManager.getPrimaryDarkColor(context))) }
    var secondaryDarkColor by remember { mutableStateOf(Color(SettingsManager.getSecondaryContainerDarkColor(context))) }
    var showPrimaryDarkColorPicker by remember { mutableStateOf(false) }
    var showSecondaryDarkColorPicker by remember { mutableStateOf(false) }

    val predefinedLightPrimaryColors = listOf(
        Color(0xFF6650a4) to "기본값",
        Color(0xFFD32F2F) to "진한 빨강",
        Color(0xFF388E3C) to "진한 초록",
        Color(0xFF1976D2) to "진한 파랑",
        Color(0xFFFBC02D) to "진한 노랑",
        Color(0xFF7B1FA2) to "진한 보라"
    )

    val predefinedLightSecondaryColors = listOf(
        Color(0xFFE8DEF8) to "기본값",
        Color(0xFFFFCDD2) to "연한 빨강",
        Color(0xFFC8E6C9) to "연한 초록",
        Color(0xFFBBDEFB) to "연한 파랑",
        Color(0xFFFFF9C4) to "연한 노랑",
        Color(0xFFE1BEE7) to "연한 보라"
    )

    val predefinedDarkPrimaryColors = listOf(
        Color(0xFFD0BCFF) to "기본값",
        Color(0xFFF48FB1) to "연한 핑크",
        Color(0xFFA5D6A7) to "연한 초록",
        Color(0xFF90CAF9) to "연한 파랑",
        Color(0xFFFFF59D) to "연한 노랑",
        Color(0xFFCE93D8) to "연한 보라"
    )

    val predefinedDarkSecondaryColors = listOf(
        Color(0xFF4A4458) to "기본값",
        Color(0xFF614345) to "어두운 빨강",
        Color(0xFF405040) to "어두운 초록",
        Color(0xFF384D64) to "어두운 파랑",
        Color(0xFF645A3A) to "어두운 노랑",
        Color(0xFF56435A) to "어두운 보라"
    )

    if (showPrimaryColorPicker) {
        ColorPickerDialog(
            colors = predefinedLightPrimaryColors,
            onColorSelected = {
                primaryColor = it
                SettingsManager.setPrimaryColor(context, it.toArgb())
                showPrimaryColorPicker = false
            },
            onDismiss = { showPrimaryColorPicker = false }
        )
    }

    if (showSecondaryColorPicker) {
        ColorPickerDialog(
            colors = predefinedLightSecondaryColors,
            onColorSelected = {
                secondaryColor = it
                SettingsManager.setSecondaryContainerColor(context, it.toArgb())
                showSecondaryColorPicker = false
            },
            onDismiss = { showSecondaryColorPicker = false }
        )
    }

    if (showPrimaryDarkColorPicker) {
        ColorPickerDialog(
            colors = predefinedDarkPrimaryColors,
            onColorSelected = {
                primaryDarkColor = it
                SettingsManager.setPrimaryDarkColor(context, it.toArgb())
                showPrimaryDarkColorPicker = false
            },
            onDismiss = { showPrimaryDarkColorPicker = false }
        )
    }

    if (showSecondaryDarkColorPicker) {
        ColorPickerDialog(
            colors = predefinedDarkSecondaryColors,
            onColorSelected = {
                secondaryDarkColor = it
                SettingsManager.setSecondaryContainerDarkColor(context, it.toArgb())
                showSecondaryDarkColorPicker = false
            },
            onDismiss = { showSecondaryDarkColorPicker = false }
        )
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Text("일반", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("진동 활성화", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = vibrationEnabled,
                onCheckedChange = {
                    vibrationEnabled = it
                    SettingsManager.setVibrationEnabled(context, it)
                }
            )
        }
        Text(
            "퀴즈에서 정답, 오답 시 진동을 웁니다. (무음 모드 시에는 작동하지 않을 수 있습니다)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("퀴즈", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("4지선다 퀴즈 자동 넘기기", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = mc4AutoAdvanceEnabled,
                onCheckedChange = {
                    mc4AutoAdvanceEnabled = it
                    SettingsManager.setMc4AutoAdvanceEnabled(context, it)
                }
            )
        }
        Text(
            "정답/오답 확인 후 설정된 시간 뒤에 자동으로 다음 문제로 넘어갑니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (mc4AutoAdvanceEnabled) {
            Spacer(Modifier.height(8.dp))
            Text("자동 넘기기 딜레이: ${String.format("%.1f", mc4AutoAdvanceDelay)}초")
            Slider(
                value = mc4AutoAdvanceDelay,
                onValueChange = { mc4AutoAdvanceDelay = it },
                valueRange = 0.5f..5f,
                steps = 8,
                onValueChangeFinished = {
                    SettingsManager.setMc4AutoAdvanceDelay(context, mc4AutoAdvanceDelay)
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("테마", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Column {
            val themes = listOf("라이트" to "light", "다크" to "dark", "시스템 설정" to "system")
            themes.forEach { (name, value) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { 
                            themeSetting = value
                            SettingsManager.setThemeSetting(context, value)
                         },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = themeSetting == value,
                        onClick = { 
                            themeSetting = value
                            SettingsManager.setThemeSetting(context, value)
                        }
                    )
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (showLightColorSettings) {
            Text("라이트 모드 테마 색상 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("기본 색상", style = MaterialTheme.typography.bodyLarge)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                        .clickable { showPrimaryColorPicker = true }
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("보조 색상", style = MaterialTheme.typography.bodyLarge)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(secondaryColor)
                        .clickable { showSecondaryColorPicker = true }
                )
            }
        }

        if (showDarkColorSettings) {
            Text("다크 모드 테마 색상 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("다크 모드 기본 색상", style = MaterialTheme.typography.bodyLarge)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(primaryDarkColor)
                        .clickable { showPrimaryDarkColorPicker = true }
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("다크 모드 보조 색상", style = MaterialTheme.typography.bodyLarge)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(secondaryDarkColor)
                        .clickable { showSecondaryDarkColorPicker = true }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "참고: 테마 색상 변경은 앱을 다시 시작해야 완전히 적용됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ColorPickerDialog(
    colors: List<Pair<Color, String>>,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("색상 선택") },
        text = {
            Column {
                colors.forEach { (color, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onColorSelected(color) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(name)
                    }
                }
            }
        },
        confirmButton = { }
    )
}

@Composable
fun DuplicateWarningDialog(
    duplicateCount: Int,
    onDismiss: () -> Unit, // This will now be used for Ignore
    onRemoveDuplicates: () -> Unit,
    onIgnoreDuplicates: () -> Unit,
    onViewDetails: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onIgnoreDuplicates, // Clicking outside ignores
        title = { Text("중복 단어 발견") },
        text = {
            Column {
                Text("로드된 파일에 ${duplicateCount}개의 중복 단어가 있습니다. 어떻게 처리하시겠습니까?")
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onViewDetails, modifier = Modifier.fillMaxWidth()) { Text("자세히 보기") }
            }
        },
        confirmButton = {
            Button(onClick = onRemoveDuplicates) { Text("제거하기") }
        },
        dismissButton = {
            OutlinedButton(onClick = onIgnoreDuplicates) { Text("무시하기") }
        },
        // We will add the third button manually in the content
//        neutralButton = {
//            TextButton(onClick = onViewDetails) { Text("자세히 보기") }
//        }
    )
}

@Composable
private fun AppOverflowMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPickToLoad: () -> Unit,
    onMerge: () -> Unit,
    onPickToSave: () -> Unit,
    onLoadFromAppFolder: () -> Unit,
    onSaveToAppFolder: () -> Unit,
    onClearAll: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { onExpandedChange(false) }
    ) {
        DropdownMenuItem(
            text = { Text("불러오기") },
            onClick = {
                onPickToLoad()
                onExpandedChange(false)
            }
        )
        DropdownMenuItem(
            text = { Text("단어장 병합하기") },
            onClick = {
                onMerge()
                onExpandedChange(false)
            }
        )
        DropdownMenuItem(
            text = { Text("다른 이름으로 저장") },
            onClick = {
                onPickToSave()
                onExpandedChange(false)
            }
        )
        Divider()
        DropdownMenuItem(
            text = { Text("앱 폴더에서 불러오기") },
            onClick = {
                onLoadFromAppFolder()
                onExpandedChange(false)
            }
        )
        DropdownMenuItem(
            text = { Text("앱 폴더에 저장하기") },
            onClick = {
                onSaveToAppFolder()
                onExpandedChange(false)
            }
        )
        Divider()
        DropdownMenuItem(
            text = { Text("모두 지우기") },
            onClick = {
                onClearAll()
                onExpandedChange(false)
            }
        )
    }
}

@Composable
fun ExtensionsScreen(modifier: Modifier) {
    val context = LocalContext.current
    val extensions = ExtensionManager.getBuiltInExtensions()

    Column(modifier = modifier) {
        Text("확장 프로그램", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        LazyColumn {
            items(extensions.size) { index ->
                val extension = extensions[index]
                var isEnabled by remember { mutableStateOf(SettingsManager.isExtensionEnabled(context, extension.id)) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(extension.name, style = MaterialTheme.typography.bodyLarge)
                        Text(extension.description, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = {
                            isEnabled = it
                            SettingsManager.setExtensionEnabled(context, extension.id, it)
                        }
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun DuplicateDetailsScreen(
    modifier: Modifier,
    duplicateInfo: Map<Pair<String, String>, List<WordPair>>,
    onRemoveDuplicates: () -> Unit,
    onGoBack: () -> Unit
) {
    Column(modifier = modifier) {
        Text("중복 단어 상세 정보", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("총 ${duplicateInfo.size} 종류의 단어가 중복되었습니다.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            duplicateInfo.forEach { (pair, duplicates) ->
                item {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = "'${pair.first}' - '${pair.second}' (${duplicates.size}회 중복)",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("이 단어는 파일 내에서 여러 번 발견되었습니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onRemoveDuplicates, modifier = Modifier.fillMaxWidth()) {
            Text("중복 모두 제거하고 돌아가기")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onGoBack, modifier = Modifier.fillMaxWidth()) {
            Text("무시하고 돌아가기")
        }
    }
}



@Composable
fun CloudWordbookScreen(modifier: Modifier, onApply: (List<WordPair>) -> Unit, saveCloudWordsLauncher: ActivityResultLauncher<String>, onSetCloudWordsToSave: (List<WordPair>?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileList by remember { mutableStateOf<List<GithubFile>>(emptyList()) }
    var wordList by remember { mutableStateOf<List<WordPair>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<GithubFile?>(null) }

    val client = HttpClient(Android) {
        engine {
            // Explicitly configure engine, though defaults are usually fine
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    // Function to fetch file list from GitHub
    val fetchFileList: () -> Unit = {
        scope.launch {
            isLoading = true
            val repoPath = "passerby0730/WordappExtension/contents/"
            val apiUrl = "https://api.github.com/repos/$repoPath"
            try {
                val files = client.get(apiUrl).body<List<GithubFile>>()
                fileList = files.filter { it.name.endsWith(".txt", ignoreCase = true) }
            } catch (e: Exception) {
                Toast.makeText(context, "파일 목록을 불러오는 데 실패했습니다.", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
            isLoading = false
        }
    }

    // Fetch file list on screen launch
    LaunchedEffect(Unit) {
        fetchFileList()
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("클라우드 단어장", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(onClick = fetchFileList) {
                Text("새로고침")
            }
        }
        Spacer(Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (fileList.isEmpty()) {
            Text("표시할 단어장(.txt) 파일이 없거나, 저장소를 불러올 수 없습니다.", modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(fileList.size) { index ->
                    val file = fileList[index]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                selectedFile = file
                                scope.launch {
                                    try {
                                        // Use the file's API URL to get content
                                        val contentResponse = client.get(file.url).body<GithubFileContent>()
                                        if (contentResponse.encoding == "base64" && contentResponse.content != null) {
                                            val decodedContent = Base64.decode(contentResponse.content, Base64.DEFAULT).toString(Charsets.UTF_8)
                                            val words = decodedContent.lines().mapNotNull { line -> parseLineToPair(line) }
                                            wordList = words.map { WordPair(it.first, it.second) }
                                            if (wordList.isEmpty()) {
                                                Toast.makeText(context, "단어장 내용은 비어있습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "파일 내용을 읽을 수 없습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "실패: ${e.message}", Toast.LENGTH_LONG).show()
                                        e.printStackTrace()
                                    }
                                }
                            },
                    ) {
                        Text(file.name, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }

        selectedFile?.let {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(it.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row {
                Button(onClick = { onApply(wordList) }, enabled = wordList.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text("이 단어장 적용하기")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onSetCloudWordsToSave(wordList)
                    val newFileName = "${selectedFile?.name?.removeSuffix(".txt") ?: "클라우드 단어장"}.txt"
                    saveCloudWordsLauncher.launch(newFileName)
                }, enabled = wordList.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text("저장하기")
                }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(wordList.size) { index ->
                    val word = wordList[index]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(word.eng)
                        Text(word.kor)
                    }
                }
            }
        }
    }
}



fun readWordsFromUri(context: Context, uri: Uri): List<WordPair> {
    val words = mutableListOf<WordPair>()
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            reader.forEachLine { line ->
                parseLineToPair(line)?.let { (eng, kor) ->
                    if (!isInvalidEnglishWord(eng)) {
                        words.add(WordPair(eng, kor))
                    }
                }
            }
        }
    }
    return words
}

fun saveWordsToUri(context: Context, uri: Uri, wordList: List<WordPair>) {
    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
        outputStream.bufferedWriter().use { writer ->
            wordList.forEach { wordPair ->
                writer.write("${wordPair.eng} = ${wordPair.kor}")
                writer.newLine()
            }
        }
    }
}

fun loadWordsFromAppSpecificStorage(context: Context): List<WordPair> {
    val words = mutableListOf<WordPair>()
    val file = File(context.filesDir, "words.txt") // Assuming a default filename
    if (file.exists()) {
        file.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                parseLineToPair(line)?.let { (eng, kor) ->
                    if (!isInvalidEnglishWord(eng)) {
                        words.add(WordPair(eng, kor))
                    }
                }
            }
        }
    }
    return words
}

fun saveWordsToAppSpecificStorage(context: Context, wordList: List<WordPair>) {
    val file = File(context.filesDir, "words.txt") // Assuming a default filename
    file.bufferedWriter().use { writer ->
        wordList.forEach { wordPair ->
            writer.write("${wordPair.eng} = ${wordPair.kor}")
            writer.newLine()
        }
    }
}


