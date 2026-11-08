package app.wordgame.android.ui

import androidx.compose.ui.res.stringResource
import app.wordgame.android.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Back button of the top bars: a filled triangle pointing left, easier to see than an arrow. */
@Composable
fun BackButton(onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val back = stringResource(R.string.back)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = back }) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            // Slightly narrower than tall, tip on the left edge, centered vertically.
            val triangle = Path().apply {
                moveTo(w * 0.1f, h / 2)
                lineTo(w * 0.85f, 0f)
                lineTo(w * 0.85f, h)
                close()
            }
            drawPath(triangle, color)
        }
    }
}
