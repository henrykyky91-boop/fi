package de.zapko.spur.capture

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Lautstaerketaste -> Ausloeser. */
object ShutterBus {
    val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    @Volatile var cameraActive = false
}

/** Navigation von aussen (Schnellkachel, App-Verknuepfung). */
object NavBus {
    val route = MutableStateFlow<String?>(null)
}

/** Liste, durch die man in der Detailansicht wischen kann. */
object DetailList {
    var ids: List<String> = emptyList()
}
