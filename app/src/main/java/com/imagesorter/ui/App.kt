package com.imagesorter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.imagesorter.data.MediaOps
import com.imagesorter.data.MediaQueries
import com.imagesorter.data.db.ArchiveDao
import com.imagesorter.data.db.DecisionDao

sealed interface Screen {
    data object Folders : Screen
    data class Deck(val bucketIds: Set<String>?, val title: String) : Screen
    data object Trash : Screen
}

@Composable
fun App(queries: MediaQueries, ops: MediaOps, dao: DecisionDao, archiveDao: ArchiveDao) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(Permissions.status(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = Permissions.status(context) }

    if (!access.fullRead) {
        PermissionScreen(access, onResult = { access = Permissions.status(context) })
        return
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Folders) }
    when (val s = screen) {
        Screen.Folders -> FolderPickerScreen(
            queries = queries,
            ops = ops,
            dao = dao,
            archiveDao = archiveDao,
            access = access,
            onOpenDeck = { screen = it },
            onOpenTrash = { screen = Screen.Trash },
        )
        is Screen.Deck -> DeckScreen(s, queries, ops, dao, archiveDao, onBack = { screen = Screen.Folders })
        Screen.Trash -> TrashScreen(queries, ops, onBack = { screen = Screen.Folders })
    }
}
