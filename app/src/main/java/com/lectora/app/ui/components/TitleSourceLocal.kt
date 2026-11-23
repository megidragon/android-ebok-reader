package com.lectora.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.lectora.app.data.TitleSource

/** Preferencia de título de libro (nombre de archivo o título interno), disponible en toda la UI. */
val LocalTitleSource = staticCompositionLocalOf { TitleSource.FILE_NAME }
