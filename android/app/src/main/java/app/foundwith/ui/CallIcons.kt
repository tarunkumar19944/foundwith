package app.foundwith.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.foundwith.core.CallEvent
import app.foundwith.core.CallType

/** The call log's symbols, drawn as strokes so they tint like any icon. */
object CallIcons {
    private const val PHONE = "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"
    val Missed = build("Missed", PHONE, "M16 3l5 5M21 3l-5 5")
    val Received = build("Received", PHONE, "M21 3l-6 6M15 3v6h6")
    val Called = build("Called", PHONE, "M15 3h6v6M21 3l-6 6")
    val Rejected = build("Rejected", PHONE, "M3 3l18 18")
    val Phone = build("Phone", PHONE)

    private fun build(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(addPathNodes(p), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return b.build()
    }
}

/** The symbol for a call, in the colour of its kind: red missed or rejected, green received, blue called, grey unanswered. */
@Composable
fun CallKindIcon(call: CallEvent, size: Dp = 40.dp) {
    val (icon, tint) = when (call.type) {
        CallType.MISSED -> CallIcons.Missed to MaterialTheme.colorScheme.error
        CallType.REJECTED -> CallIcons.Rejected to MaterialTheme.colorScheme.error
        CallType.ANSWERED -> CallIcons.Received to MaterialTheme.colorScheme.tertiary
        CallType.OUTGOING -> CallIcons.Called to if (call.duration > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        CallType.OTHER -> CallIcons.Phone to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = callKind(call), tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}

/** The colour for a call's kind line: red for missed or rejected, grey otherwise. */
@Composable
fun callKindColour(call: CallEvent): Color =
    if (call.type == CallType.MISSED || call.type == CallType.REJECTED) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant
