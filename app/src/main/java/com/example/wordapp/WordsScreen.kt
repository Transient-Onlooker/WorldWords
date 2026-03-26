package com.example.wordapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import java.io.File
import com.example.wordapp.isEnglishToken
import com.example.wordapp.isKoreanToken
import com.example.wordapp.normalizeForAnswer
import com.example.wordapp.maskEnglishWord

@Composable
fun WordsScreen(
    modifier: Modifier,
    wordList: MutableList<WordPair>,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    editIndex: Int,
    setEditIndex: (Int) -> Unit,
    folderName: String,
    onFolderNameChange: (String) -> Unit,
    fileName: String,
    onFileNameChange: (String) -> Unit,
    baseDir: File
) {
    var validationWarningPairs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pendingValidPairs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    if (validationWarningPairs.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { validationWarningPairs = emptyList() },
            title = { Text("입력 경고") },
            text = {
                Column {
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column {
                            val errorText = validationWarningPairs.joinToString("\n") { (e, k) ->
                                val engError = if (isKoreanToken(e)) "단어 '$e'에 한글이 포함됩니다." else ""
                                val korError = if (isEnglishToken(k)) "뜻 '$k'에 영어가 포함됩니다." else ""
                                listOf(engError, korError).filter { it.isNotBlank() }.joinToString("\n")
                            }
                            Text(errorText)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                val allPairsToAdd = pendingValidPairs + validationWarningPairs
                                val filtered = allPairsToAdd.filterNot { isInvalidEnglishWord(it.first) }
                                if (editIndex >= 0) {
                                    filtered.firstOrNull()?.let { (e, k) ->
                                        val old = wordList[editIndex]
                                        wordList[editIndex] = old.copy(eng = e, kor = k)
                                        setEditIndex(-1)
                                    }
                                } else {
                                    wordList.addAll(filtered.map { (e, k) -> WordPair(e, k) })
                                }
                                onInputTextChange("")
                                pendingValidPairs = emptyList()
                                validationWarningPairs = emptyList()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("무시하고 전부 추가") }
                        Button(
                            onClick = {
                                val filtered = pendingValidPairs.filterNot { isInvalidEnglishWord(it.first) }
                                if (editIndex >= 0) {
                                    filtered.firstOrNull()?.let { (e, k) ->
                                        val old = wordList[editIndex]
                                        wordList[editIndex] = old.copy(eng = e, kor = k)
                                        setEditIndex(-1)
                                    }
                                } else {
                                    wordList.addAll(filtered.map { (e, k) -> WordPair(e, k) })
                                }
                                onInputTextChange("")
                                pendingValidPairs = emptyList()
                                validationWarningPairs = emptyList()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("유효한 것만 추가") }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { validationWarningPairs = emptyList() },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("닫기") }
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("전체 삭제") },
            text = { Text("정말로 모두 지우시겠습니까?") },
            confirmButton = {
                Button(onClick = {
                    wordList.clear()
                    setEditIndex(-1)
                    onInputTextChange("")
                    showClearConfirmDialog = false
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) { Text("취소") }
            }
        )
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            val fromIndex = from.index - 1
            val toIndex = to.index - 1
            if (fromIndex !in wordList.indices || toIndex !in 0..wordList.size) return@rememberReorderableLazyListState
            val item = wordList.removeAt(fromIndex)
            wordList.add(toIndex, item)
            if (editIndex == fromIndex) setEditIndex(toIndex) else {
                if (fromIndex < editIndex && toIndex >= editIndex) setEditIndex(editIndex - 1)
                else if (fromIndex > editIndex && toIndex <= editIndex) setEditIndex(editIndex + 1)
            }
        },
        canDragOver = { draggedOver, _ -> draggedOver.index > 0 }
    )

    LazyColumn(
        modifier = modifier,
        state = reorderState.listState,
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "settings-panel") {
            Column {
                Text("단어 추가", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    label = { Text("예: clever 재치 있는ㅣnever 절대") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val res = parsePairsFromInline(inputText)
                        if (res.valid.isEmpty() && res.errors.isEmpty()) return@Button
                        if (res.errors.isNotEmpty()) {
                            pendingValidPairs = res.valid
                            validationWarningPairs = res.errors
                        } else {
                            val filtered = res.valid.filterNot { isInvalidEnglishWord(it.first) }
                            if (editIndex >= 0) {
                                filtered.firstOrNull()?.let { (e, k) ->
                                    val old = wordList[editIndex]
                                    wordList[editIndex] = old.copy(eng = e, kor = k)
                                    setEditIndex(-1)
                                }
                            } else {
                                wordList.addAll(filtered.map { (e, k) -> WordPair(e, k) })
                            }
                            onInputTextChange("")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (editIndex >= 0) "수정하기" else "추가하기") }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "줄 바꿈 또는 한글 ㅣ를 이용하여 단어를 구분하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("저장/불러오기 (앱 폴더)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = folderName,
                    onValueChange = onFolderNameChange,
                    label = { Text("폴더 이름 (예: EBS 단어장)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = onFileNameChange,
                    label = { Text("파일 이름 (확장자 자동)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "앱 폴더 경로: " + File(File(baseDir, folderName), "${fileName}.txt").absolutePath,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("단어 리스트 (항목을 길게 눌러 끌어서 순서 변경)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showClearConfirmDialog = true }) { Text("모두 지우기") }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        items(items = wordList, key = { it.id }) { item ->
            ReorderableItem(reorderState, key = item.id) { dragging ->
                val bg = if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .reorderable(reorderState)
                        .detectReorderAfterLongPress(reorderState),
                    colors = CardDefaults.cardColors(containerColor = bg)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${item.eng} = ${item.kor}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Row {
                            TextButton(onClick = {
                                onInputTextChange("${item.eng} ${item.kor}")
                                setEditIndex(wordList.indexOfFirst { it.id == item.id })
                            }) { Text("수정") }
                            TextButton(onClick = {
                                val index = wordList.indexOfFirst { it.id == item.id }
                                if (index >= 0) {
                                    wordList.removeAt(index)
                                    if (editIndex == index) { setEditIndex(-1); onInputTextChange("") }
                                    else if (editIndex > index) { setEditIndex(editIndex - 1) }
                                }
                            }) { Text("삭제") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppOverflowMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPickToLoad: () -> Unit,
    onPickToSave: () -> Unit,
    onLoadFromAppFolder: () -> Unit,
    onSaveToAppFolder: () -> Unit,
    onClearAll: () -> Unit
) {
    IconButton(onClick = { onExpandedChange(true) }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
        DropdownMenuItem(text = { Text("파일 선택해 불러오기") }, onClick = { onExpandedChange(false); onPickToLoad() })
        DropdownMenuItem(text = { Text("파일 선택해 저장") }, onClick = { onExpandedChange(false); onPickToSave() })
        HorizontalDivider()
        DropdownMenuItem(text = { Text("앱 폴더에서 불러오기") }, onClick = { onExpandedChange(false); onLoadFromAppFolder() })
        DropdownMenuItem(text = { Text("앱 폴더에 저장") }, onClick = { onExpandedChange(false); onSaveToAppFolder() })
        HorizontalDivider()
        DropdownMenuItem(text = { Text("전체 비우기") }, onClick = { onExpandedChange(false); onClearAll() })
    }
}