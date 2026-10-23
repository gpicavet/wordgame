package app.wordgame.android.ui

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * A tile's letter and points, drawn by the shape of the glyphs rather than by text lines.
 * Compose centers a line box, which keeps room under the baseline for descenders: a capital letter
 * then sits a little high, by an amount that depends on the phone's font. Here the letter's ink
 * bounds are centered, and sizes follow the tile (not the system font size), so it never overflows.
 */
@Composable
fun TileGlyph(
    letter: String,
    points: Int?,
    color: Color,
    modifier: Modifier = Modifier,
    letterSize: Float = 0.58f,
    pointsSize: Float = 0.26f,
) {
    val letterPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD } }
    // Several characters (premium labels, numbers): plain weight reads better at small sizes.
    if (letter.length > 1) letterPaint.typeface = Typeface.DEFAULT
    val pointsPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val bounds = remember { Rect() }
    Canvas(
        modifier.fillMaxSize().semantics {
            contentDescription = if (points != null && letter.isNotEmpty()) "$letter, $points points" else letter
        },
    ) {
        val side = size.minDimension
        drawIntoCanvas { canvas ->
            if (letter.isNotEmpty()) {
                letterPaint.color = color.toArgb()
                letterPaint.textSize = side * letterSize
                letterPaint.getTextBounds(letter, 0, letter.length, bounds)
                canvas.nativeCanvas.drawText(
                    letter,
                    size.width / 2 - bounds.exactCenterX(),
                    size.height / 2 - bounds.exactCenterY(),
                    letterPaint,
                )
            }
            if (points != null && points > 0) {
                val text = points.toString()
                pointsPaint.color = color.toArgb()
                pointsPaint.textSize = side * pointsSize
                pointsPaint.getTextBounds(text, 0, text.length, bounds)
                // Bottom-right corner, inset by a small margin.
                val margin = side * 0.08f
                canvas.nativeCanvas.drawText(
                    text,
                    size.width - margin - bounds.right,
                    size.height - margin - bounds.bottom,
                    pointsPaint,
                )
            }
        }
    }
}
