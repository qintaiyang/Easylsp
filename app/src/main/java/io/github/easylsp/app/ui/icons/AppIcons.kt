// Material Design outlined icons, Google, Apache-2.0. Paths from google/material-design-icons.
package io.github.easylsp.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object AppIcons {
    val Add: ImageVector by lazy { ImageVector.Builder("Add", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"), fill = SolidColor(Color.Black))
    }.build() }
    val Android: ImageVector by lazy { ImageVector.Builder("Android", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M17.6,9.48l1.84-3.18c0.16-0.31,0.04-0.69-0.26-0.85c-0.29-0.15-0.65-0.06-0.83,0.22l-1.88,3.24 c-2.86-1.21-6.08-1.21-8.94,0L5.65,5.67c-0.19-0.29-0.58-0.38-0.87-0.2C4.5,5.65,4.41,6.01,4.56,6.3L6.4,9.48 C3.3,11.25,1.28,14.44,1,18h22C22.72,14.44,20.7,11.25,17.6,9.48z M7,15.25c-0.69,0-1.25-0.56-1.25-1.25 c0-0.69,0.56-1.25,1.25-1.25S8.25,13.31,8.25,14C8.25,14.69,7.69,15.25,7,15.25z M17,15.25c-0.69,0-1.25-0.56-1.25-1.25 c0-0.69,0.56-1.25,1.25-1.25s1.25,0.56,1.25,1.25C18.25,14.69,17.69,15.25,17,15.25z"), fill = SolidColor(Color.Black))
    }.build() }
    val Apps: ImageVector by lazy { ImageVector.Builder("Apps", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z"), fill = SolidColor(Color.Black))
    }.build() }
    val ArrowBack: ImageVector by lazy { ImageVector.Builder("ArrowBack", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"), fill = SolidColor(Color.Black))
    }.build() }
    val AutoAwesome: ImageVector by lazy { ImageVector.Builder("AutoAwesome", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M11.5,9.5L9,4L6.5,9.5L1,12l5.5,2.5L9,20l2.5-5.5L17,12L11.5,9.5z M9.99,12.99L9,15.17l-0.99-2.18L5.83,12l2.18-0.99 L9,8.83l0.99,2.18L12.17,12L9.99,12.99z"), fill = SolidColor(Color.Black))
    }.build() }
    val ChevronRight: ImageVector by lazy { ImageVector.Builder("ChevronRight", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6-6-6z"), fill = SolidColor(Color.Black))
    }.build() }
    val Close: ImageVector by lazy { ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12 19 6.41z"), fill = SolidColor(Color.Black))
    }.build() }
    val ContentCopy: ImageVector by lazy { ImageVector.Builder("ContentCopy", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"), fill = SolidColor(Color.Black))
    }.build() }
    val DeleteOutline: ImageVector by lazy { ImageVector.Builder("DeleteOutline", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM8 9h8v10H8V9zm7.5-5l-1-1h-5l-1 1H5v2h14V4h-3.5z"), fill = SolidColor(Color.Black))
    }.build() }
    val Extension: ImageVector by lazy { ImageVector.Builder("Extension", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M10.5 4.5c.28 0 .5.22.5.5v2h6v6h2c.28 0 .5.22.5.5s-.22.5-.5.5h-2v6h-2.12c-.68-1.75-2.39-3-4.38-3s-3.7 1.25-4.38 3H4v-2.12c1.75-.68 3-2.39 3-4.38 0-1.99-1.24-3.7-2.99-4.38L4 7h6V5c0-.28.22-.5.5-.5m0-2C9.12 2.5 8 3.62 8 5H4c-1.1 0-1.99.9-1.99 2v3.8h.29c1.49 0 2.7 1.21 2.7 2.7s-1.21 2.7-2.7 2.7H2V20c0 1.1.9 2 2 2h3.8v-.3c0-1.49 1.21-2.7 2.7-2.7s2.7 1.21 2.7 2.7v.3H17c1.1 0 2-.9 2-2v-4c1.38 0 2.5-1.12 2.5-2.5S20.38 11 19 11V7c0-1.1-.9-2-2-2h-4c0-1.38-1.12-2.5-2.5-2.5z"), fill = SolidColor(Color.Black))
    }.build() }
    val FileOpen: ImageVector by lazy { ImageVector.Builder("FileOpen", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M15,22H6c-1.1,0-2-0.9-2-2V4c0-1.1,0.9-2,2-2h8l6,6v6h-2V9h-5V4H6v16h9V22z M19,21.66l0-2.24l2.95,2.95l1.41-1.41L20.41,18 h2.24v-2H17v5.66H19z"), fill = SolidColor(Color.Black))
    }.build() }
    val FolderOpen: ImageVector by lazy { ImageVector.Builder("FolderOpen", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z"), fill = SolidColor(Color.Black))
    }.build() }
    val FormatIndentDecrease: ImageVector by lazy { ImageVector.Builder("FormatIndentDecrease", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M11 17h10v-2H11v2zm-8-5l4 4V8l-4 4zm0 9h18v-2H3v2zM3 3v2h18V3H3zm8 6h10V7H11v2zm0 4h10v-2H11v2z"), fill = SolidColor(Color.Black))
    }.build() }
    val FormatIndentIncrease: ImageVector by lazy { ImageVector.Builder("FormatIndentIncrease", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M3 21h18v-2H3v2zM3 8v8l4-4-4-4zm8 9h10v-2H11v2zM3 3v2h18V3H3zm8 6h10V7H11v2zm0 4h10v-2H11v2z"), fill = SolidColor(Color.Black))
    }.build() }
    val Home: ImageVector by lazy { ImageVector.Builder("Home", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M12 5.69l5 4.5V18h-2v-6H9v6H7v-7.81l5-4.5M12 3L2 12h3v8h6v-6h2v6h6v-8h3L12 3z"), fill = SolidColor(Color.Black))
    }.build() }
    val Inbox: ImageVector by lazy { ImageVector.Builder("Inbox", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5v-3h3.56c.69 1.19 1.97 2 3.45 2s2.75-.81 3.45-2H19v3zm0-5h-4.99c0 1.1-.9 2-2 2s-2-.9-2-2H5V5h14v9z"), fill = SolidColor(Color.Black))
    }.build() }
    val Info: ImageVector by lazy { ImageVector.Builder("Info", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z"), fill = SolidColor(Color.Black))
    }.build() }
    val MoreVert: ImageVector by lazy { ImageVector.Builder("MoreVert", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"), fill = SolidColor(Color.Black))
    }.build() }
    val Palette: ImageVector by lazy { ImageVector.Builder("Palette", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M12,22C6.49,22,2,17.51,2,12S6.49,2,12,2s10,4.04,10,9c0,3.31-2.69,6-6,6h-1.77c-0.28,0-0.5,0.22-0.5,0.5 c0,0.12,0.05,0.23,0.13,0.33c0.41,0.47,0.64,1.06,0.64,1.67C14.5,20.88,13.38,22,12,22z M12,4c-4.41,0-8,3.59-8,8s3.59,8,8,8 c0.28,0,0.5-0.22,0.5-0.5c0-0.16-0.08-0.28-0.14-0.35c-0.41-0.46-0.63-1.05-0.63-1.65c0-1.38,1.12-2.5,2.5-2.5H16 c2.21,0,4-1.79,4-4C20,7.14,16.41,4,12,4z"), fill = SolidColor(Color.Black))
    }.build() }
    val PlayArrow: ImageVector by lazy { ImageVector.Builder("PlayArrow", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M10 8.64L15.27 12 10 15.36V8.64M8 5v14l11-7L8 5z"), fill = SolidColor(Color.Black))
    }.build() }
    val Redo: ImageVector by lazy { ImageVector.Builder("Redo", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z"), fill = SolidColor(Color.Black))
    }.build() }
    val Refresh: ImageVector by lazy { ImageVector.Builder("Refresh", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"), fill = SolidColor(Color.Black))
    }.build() }
    val Search: ImageVector by lazy { ImageVector.Builder("Search", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"), fill = SolidColor(Color.Black))
    }.build() }
    val Settings: ImageVector by lazy { ImageVector.Builder("Settings", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M19.43 12.98c.04-.32.07-.64.07-.98 0-.34-.03-.66-.07-.98l2.11-1.65c.19-.15.24-.42.12-.64l-2-3.46c-.09-.16-.26-.25-.44-.25-.06 0-.12.01-.17.03l-2.49 1c-.52-.4-1.08-.73-1.69-.98l-.38-2.65C14.46 2.18 14.25 2 14 2h-4c-.25 0-.46.18-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98l-2.49-1c-.06-.02-.12-.03-.18-.03-.17 0-.34.09-.43.25l-2 3.46c-.13.22-.07.49.12.64l2.11 1.65c-.04.32-.07.65-.07.98 0 .33.03.66.07.98l-2.11 1.65c-.19.15-.24.42-.12.64l2 3.46c.09.16.26.25.44.25.06 0 .12-.01.17-.03l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.03.24.24.42.49.42h4c.25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1c.06.02.12.03.18.03.17 0 .34-.09.43-.25l2-3.46c.12-.22.07-.49-.12-.64l-2.11-1.65zm-1.98-1.71c.04.31.05.52.05.73 0 .21-.02.43-.05.73l-.14 1.13.89.7 1.08.84-.7 1.21-1.27-.51-1.04-.42-.9.68c-.43.32-.84.56-1.25.73l-1.06.43-.16 1.13-.2 1.35h-1.4l-.19-1.35-.16-1.13-1.06-.43c-.43-.18-.83-.41-1.23-.71l-.91-.7-1.06.43-1.27.51-.7-1.21 1.08-.84.89-.7-.14-1.13c-.03-.31-.05-.54-.05-.74s.02-.43.05-.73l.14-1.13-.89-.7-1.08-.84.7-1.21 1.27.51 1.04.42.9-.68c.43-.32.84-.56 1.25-.73l1.06-.43.16-1.13.2-1.35h1.39l.19 1.35.16 1.13 1.06.43c.43.18.83.41 1.23.71l.91.7 1.06-.43 1.27-.51.7 1.21-1.07.85-.89.7.14 1.13zM12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm0 6c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"), fill = SolidColor(Color.Black))
    }.build() }
    val Sort: ImageVector by lazy { ImageVector.Builder("Sort", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M3 18h6v-2H3v2zM3 6v2h18V6H3zm0 7h12v-2H3v2z"), fill = SolidColor(Color.Black))
    }.build() }
    val Storage: ImageVector by lazy { ImageVector.Builder("Storage", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2zm-4 7h20v-4H2v4zm2-3h2v2H4v-2z"), fill = SolidColor(Color.Black))
    }.build() }
    val Terminal: ImageVector by lazy { ImageVector.Builder("Terminal", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M20,4H4C2.89,4,2,4.9,2,6v12c0,1.1,0.89,2,2,2h16c1.1,0,2-0.9,2-2V6C22,4.9,21.11,4,20,4z M20,18H4V8h16V18z M18,17h-6v-2 h6V17z M7.5,17l-1.41-1.41L8.67,13l-2.59-2.59L7.5,9l4,4L7.5,17z"), fill = SolidColor(Color.Black))
    }.build() }
    val Undo: ImageVector by lazy { ImageVector.Builder("Undo", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z"), fill = SolidColor(Color.Black))
    }.build() }
    val VerifiedUser: ImageVector by lazy { ImageVector.Builder("VerifiedUser", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm7 10c0 4.52-2.98 8.69-7 9.93-4.02-1.24-7-5.41-7-9.93V6.3l7-3.11 7 3.11V11zm-11.59.59L6 13l4 4 8-8-1.41-1.42L10 14.17z"), fill = SolidColor(Color.Black))
    }.build() }
}
