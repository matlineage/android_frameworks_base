/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.animation

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.fonts.Font
import android.graphics.fonts.FontVariationAxis
import android.graphics.text.PositionedGlyphs
import android.text.Layout
import android.text.TextPaint
import android.text.TextShaper
import android.util.MathUtils.lerp
import androidx.core.graphics.withSave
import com.android.internal.graphics.ColorUtils
import kotlin.math.max

interface TextInterpolatorListener {
    fun onPaintModified(paint: Paint) {}

    fun onRebased(progress: Float) {}

    fun getCharWidthAdjustment(font: Font, char: Char, width: Float): Float = 0f

    fun onTotalAdjustmentComputed(
        paint: Paint,
        lineAdvance: Float,
        totalAdjustment: Float,
    ): Boolean = false
}

data class ShapingResult(val text: String, val lines: List<ShapingLine>, val layout: Layout)

data class ShapingLine(val text: String, val runs: List<ShapingRun>)

data class ShapingRun(val text: String, val glyphs: PositionedGlyphs)

/** Provide text style linear interpolation for plain text. */
class TextInterpolator(
    layout: Layout,
    var typefaceCache: TypefaceVariantCache,
    private val listener: TextInterpolatorListener? = null,
) {
    /**
     * Returns base paint used for interpolation.
     *
     * Once you modified the style parameters, you have to call reshapeText to recalculate base text
     * layout.
     *
     * Do not bypass the cache and update the typeface or font variation directly.
     *
     * @return a paint object
     */
    val basePaint = TextPaint(layout.paint)

    /**
     * Returns target paint used for interpolation.
     *
     * Once you modified the style parameters, you have to call reshapeText to recalculate target
     * text layout.
     *
     * Do not bypass the cache and update the typeface or font variation directly.
     *
     * @return a paint object
     */
    val targetPaint = TextPaint(layout.paint)

    /**
     * A class represents a single font run.
     *
     * A font run is a range that will be drawn with the same font.
     */
    private data class FontRun(
        val start: Int, // inclusive
        val end: Int, // exclusive
        var baseFont: Font,
        var targetFont: Font,
    ) {
        val length: Int
            get() = end - start
    }

    /** A class represents text layout of a single run. */
    private class Run(
        val glyphIds: IntArray,
        val baseX: FloatArray, // same length as glyphIds
        val baseY: FloatArray, // same length as glyphIds
        val targetX: FloatArray, // same length as glyphIds
        val targetY: FloatArray, // same length as glyphIds
        val fontRuns: List<FontRun>,
    )

    /** A class represents text layout of a single line. */
    private class Line(var baseOffset: Float, var targetOffset: Float, val runs: List<Run>)

    private var lines = listOf<Line>()
    private val fontInterpolator = FontInterpolator(typefaceCache.fontCache)

    // Recycling object for glyph drawing and tweaking.
    private val tmpPaint = TextPaint()
    // Will be extended for the longest font run if needed.
    private var tmpPositionArray = FloatArray(20)

    /**
     * The progress position of the interpolation.
     *
     * The 0f means the start state, 1f means the end state.
     */
    var progress: Float = 0f

    /** Linear progress value (not interpolated) */
    var linearProgress: Float = 0f

    /**
     * The layout used for drawing text.
     *
     * Only non-styled text is supported. Even if the given layout is created from Spanned, the span
     * information is not used.
     *
     * The paint objects used for interpolation are not changed by this method call.
     *
     * Note: disabling ligature is strongly recommended if you give extra letter spacing since they
     * may be disjointed based on letter spacing value and cannot be interpolated. Animator will
     * throw runtime exception if they cannot be interpolated.
     */
    var layout: Layout = layout
        set(value) {
            field = value
            shapeText(value)
        }

    var shapedText: String = ""
        private set

    init {
        // shapeText needs to be called after all members are initialized.
        shapeText(layout)
    }

    /**
     * Recalculate internal text layout for interpolation.
     *
     * Whenever the target paint is modified, call this method to recalculate internal text layout
     * used for interpolation.
     */
    fun onTargetPaintModified() {
        try {
            updatePositionsAndFonts(shapeText(layout, targetPaint), updateBase = false)
        } catch (e: IllegalArgumentException) {
            shapeText(layout)
        }
        listener?.onPaintModified(targetPaint)
    }

    /**
     * Recalculate internal text layout for interpolation.
     *
     * Whenever the base paint is modified, call this method to recalculate internal text layout
     * used for interpolation.
     */
    fun onBasePaintModified() {
        try {
            updatePositionsAndFonts(shapeText(layout, basePaint), updateBase = true)
        } catch (e: IllegalArgumentException) {
            shapeText(layout)
        }
        listener?.onPaintModified(basePaint)
    }

    /**
     * Rebase the base state to the middle of the interpolation.
     *
     * The text interpolator does not calculate all the text position by text shaper due to
     * performance reasons. Instead, the text interpolator shape the start and end state and
     * calculate text position of the middle state by linear interpolation. Due to this trick, the
     * text positions of the middle state is likely different from the text shaper result. So, if
     * you want to start animation from the middle state, you will see the glyph jumps due to this
     * trick, i.e. the progress 0.5 of interpolation between weight 400 and 700 is different from
     * text shape result of weight 550.
     *
     * After calling this method, do not call onBasePaintModified() since it reshape the text and
     * update the base state. As in above notice, the text shaping result at current progress is
     * different shaped result. By calling onBasePaintModified(), you may see the glyph jump.
     *
     * By calling this method, the progress will be reset to 0.
     *
     * This API is useful to continue animation from the middle of the state. For example, if you
     * animate weight from 200 to 400, then if you want to move back to 200 at the half of the
     * animation, it will look like
     * <pre> <code>
     * ```
     *     val interp = TextInterpolator(layout)
     *
     *     // Interpolate between weight 200 to 400.
     *     interp.basePaint.fontVariationSettings = "'wght' 200"
     *     interp.onBasePaintModified()
     *     interp.targetPaint.fontVariationSettings = "'wght' 400"
     *     interp.onTargetPaintModified()
     *
     *     // animate
     *     val animator = ValueAnimator.ofFloat(1f).apply {
     *         addUpdaterListener {
     *             interp.progress = it.animateValue as Float
     *         }
     *     }.start()
     *
     *     // Here, assuming you receive some event and want to start new animation from current
     *     // state.
     *     OnSomeEvent {
     *         animator.cancel()
     *
     *         // start another animation from the current state.
     *         interp.rebase() // Use current state as base state.
     *         interp.targetPaint.fontVariationSettings = "'wght' 200" // set new target
     *         interp.onTargetPaintModified() // reshape target
     *
     *         // Here the textInterpolator interpolate from 'wght' from 300 to 200 if the current
     *         // progress is 0.5
     *         animator.start()
     *     }
     * ```
     * </code> </pre>
     */
    fun rebase() {
        when (progress) {
            0f -> {
                listener?.onRebased(progress)
                return
            }
            1f -> basePaint.set(targetPaint)
            else -> {
                lerp(basePaint, targetPaint, progress, tmpPaint)
                basePaint.set(tmpPaint)
            }
        }

        lines.forEach { line ->
            line.baseOffset = lerp(line.baseOffset, line.targetOffset, progress)
            line.runs.forEach { run ->
                for (i in run.baseX.indices) {
                    run.baseX[i] = lerp(run.baseX[i], run.targetX[i], progress)
                    run.baseY[i] = lerp(run.baseY[i], run.targetY[i], progress)
                }
                run.fontRuns.forEach { fontRun ->
                    fontRun.baseFont =
                        fontInterpolator.lerp(
                            fontRun.baseFont,
                            fontRun.targetFont,
                            progress,
                            linearProgress,
                        )
                    val fvar = FontVariationAxis.toFontVariationSettings(fontRun.baseFont.axes)
                    basePaint.typeface = typefaceCache.getTypefaceForVariant(fvar)
                }
            }
        }

        listener?.onRebased(progress)
        linearProgress = 0f
        progress = 0f
    }

    /**
     * Draws interpolated text at the given progress.
     *
     * @param canvas a canvas.
     */
    fun draw(canvas: Canvas) {
        lerp(basePaint, targetPaint, progress, tmpPaint)
        lines.forEachIndexed { lineNo, line ->
            canvas.withSave {
                val offset = lerp(line.baseOffset, line.targetOffset, progress)
                // Move to drawing origin w/ correction for RTL offset
                val origin = layout.getLineDrawOrigin(lineNo)
                translate(origin - (origin + offset), layout.getLineBaseline(lineNo).toFloat())

                line.runs.forEach { run ->
                    run.fontRuns.forEach { fontRun -> drawFontRun(run, fontRun, tmpPaint) }
                }
            }
        }
    }

    // Shape text with current paint parameters.
    private fun shapeText(layout: Layout) {
        val baseLayout = shapeText(layout, basePaint)
        var maxRunLength = 0
        lines =
            baseLayout.lines.map { baseLine ->
                val offset = baseLine.runs.lastOrNull()?.glyphs?.offsetX ?: 0f
                val runs =
                    baseLine.runs.map { base ->
                        val glyphCount = base.glyphs.glyphCount()
                        val glyphIds = IntArray(glyphCount) { base.glyphs.getGlyphId(it) }
                        val x = FloatArray(glyphCount)
                        val y = FloatArray(glyphCount)
                        populateGlyphPositions(
                            basePaint,
                            baseLayout.layout,
                            base.glyphs,
                            base.text,
                            x,
                            y,
                        )
                        val fontRuns = buildList {
                            if (glyphCount != 0) {
                                var start = 0
                                var font = base.glyphs.getFont(start)
                                for (i in 1 until glyphCount) {
                                    val nextFont = base.glyphs.getFont(i)
                                    if (font !== nextFont) {
                                        add(FontRun(start, i, font, font))
                                        maxRunLength = max(maxRunLength, i - start)
                                        font = nextFont
                                        start = i
                                    }
                                }
                                add(FontRun(start, glyphCount, font, font))
                                maxRunLength = max(maxRunLength, glyphCount - start)
                            }
                        }
                        Run(glyphIds, x, y, x.copyOf(), y.copyOf(), fontRuns)
                    }
                Line(offset, offset, runs)
            }
        if (tmpPositionArray.size < maxRunLength * 2) {
            tmpPositionArray = FloatArray(maxRunLength * 2)
        }
    }

    // Draws single font run.
    private fun Canvas.drawFontRun(line: Run, run: FontRun, paint: Paint) {
        var arrayIndex = 0
        val font = fontInterpolator.lerp(run.baseFont, run.targetFont, progress, linearProgress)
        for (i in run.start until run.end) {
            tmpPositionArray[arrayIndex++] = lerp(line.baseX[i], line.targetX[i], progress)
            tmpPositionArray[arrayIndex++] = lerp(line.baseY[i], line.targetY[i], progress)
        }
        drawGlyphs(line.glyphIds, run.start, tmpPositionArray, 0, run.length, font, paint)
    }

    private fun updatePositionsAndFonts(layoutResult: ShapingResult, updateBase: Boolean) {
        // Update target positions with newly calculated text layout.
        check(layoutResult.lines.size == lines.size) {
            "The new layout result has different line count."
        }

        lines.zip(layoutResult.lines) { baseLine, newLine ->
            if (updateBase) {
                baseLine.baseOffset = newLine.runs.lastOrNull()?.glyphs?.offsetX ?: 0f
            } else {
                baseLine.targetOffset = newLine.runs.lastOrNull()?.glyphs?.offsetX ?: 0f
            }

            baseLine.runs.zip(newLine.runs) { baseRun, newRun ->
                require(newRun.glyphs.glyphCount() == baseRun.glyphIds.size) {
                    "The new layout has different glyph count."
                }

                baseRun.fontRuns.forEach { fontRun ->
                    val newFont = newRun.glyphs.getFont(fontRun.start)
                    for (i in fontRun.start until fontRun.end) {
                        require(
                            newRun.glyphs.getGlyphId(fontRun.start) ==
                                baseRun.glyphIds[fontRun.start]
                        ) {
                            "The new layout has different glyph ID at ${fontRun.start}"
                        }
                        require(newFont === newRun.glyphs.getFont(i)) {
                            "The new layout has different font run." +
                                " $newFont vs ${newRun.glyphs.getFont(i)} at $i"
                        }
                    }

                    // The passing base font and target font is already interpolatable, so just
                    // check new font can be interpolatable with base font.
                    require(FontInterpolator.canInterpolate(newFont, fontRun.baseFont)) {
                        "New font cannot be interpolated with existing font. $newFont, ${fontRun.baseFont}"
                    }

                    if (updateBase) {
                        fontRun.baseFont = newFont
                    } else {
                        fontRun.targetFont = newFont
                    }
                }

                if (updateBase) {
                    populateGlyphPositions(
                        basePaint,
                        layoutResult.layout,
                        newRun.glyphs,
                        newRun.text,
                        baseRun.baseX,
                        baseRun.baseY,
                    )
                } else {
                    populateGlyphPositions(
                        targetPaint,
                        layoutResult.layout,
                        newRun.glyphs,
                        newRun.text,
                        baseRun.targetX,
                        baseRun.targetY,
                    )
                }
            }
        }
    }

    // Linear interpolate the paint.
    private fun lerp(from: Paint, to: Paint, progress: Float, out: Paint) {
        out.set(from)

        // Currently only font size & colors are interpolated.
        // TODO(172943390): Add other interpolation or support custom interpolator.
        out.textSize = lerp(from.textSize, to.textSize, progress)
        out.color = ColorUtils.blendARGB(from.color, to.color, progress)
        out.strokeWidth = lerp(from.strokeWidth, to.strokeWidth, progress)
    }

    // Shape the text and stores the result to out argument.
    private fun shapeText(layout: Layout, paint: TextPaint): ShapingResult {
        val lines = buildList {
            for (lineNo in 0 until layout.lineCount) { // Shape all lines.
                val lineStart = layout.getLineStart(lineNo)
                val lineEnd = layout.getLineEnd(lineNo)
                var lineCount = lineEnd - lineStart
                // Do not render the last character in the line if it's a newline and unprintable
                val last = lineStart + lineCount - 1
                if (last > lineStart && last < layout.text.length && layout.text[last] == '\n') {
                    lineCount--
                }

                val runs = buildList {
                    TextShaper.shapeText(
                        layout.text,
                        lineStart,
                        lineCount,
                        layout.textDirectionHeuristic,
                        paint,
                    ) { runStart, runCount, glyphs, _ ->
                        add(
                            ShapingRun(layout.text.substring(runStart, runStart + runCount), glyphs)
                        )
                    }
                }

                add(ShapingLine(layout.text.substring(lineStart, lineEnd), runs))
            }
        }

        val shapedText = buildString {
            lines.forEachIndexed { lineNo, line ->
                if (lineNo > 0) append("\n")
                append(line.text)
            }
        }
        this.shapedText = shapedText
        return ShapingResult(shapedText, lines, layout)
    }

    private fun populateGlyphPositions(
        paint: Paint,
        layout: Layout,
        glyphs: PositionedGlyphs,
        str: String,
        outX: FloatArray,
        outY: FloatArray,
    ) {
        val isRtl = layout.textDirectionHeuristic.isRtl(str, 0, str.length)
        val range = (0 until glyphs.glyphCount()).let { if (isRtl) it.reversed() else it }
        val sign = if (isRtl) -1 else 1
        var xAdjustment = 0f
        for (i in range) {
            val xPos = glyphs.getGlyphX(i)
            outX[i] = xPos + xAdjustment * sign
            outY[i] = glyphs.getGlyphY(i)

            // Characters are left-aligned so any modifications to width only effect the positioning
            // of later characters. As a result, all we need to do is track a cumulative total. The
            // last character is skipped as the view bounds don't include it's trailing spacing.
            if (i != range.last()) {
                val font = glyphs.getFont(i)
                val nextXPos =
                    when {
                        i + 1 < glyphs.glyphCount() -> glyphs.getGlyphX(i + 1)
                        !isRtl -> glyphs.advance
                        else -> 0f
                    }
                xAdjustment += listener?.getCharWidthAdjustment(font, str[i], nextXPos - xPos) ?: 0f
            }
        }

        listener?.onTotalAdjustmentComputed(paint, glyphs.advance, xAdjustment)
    }

    companion object {
        private fun Layout.getLineDrawOrigin(lineNo: Int): Float {
            return if (getParagraphDirection(lineNo) == Layout.DIR_LEFT_TO_RIGHT) {
                getLineLeft(lineNo)
            } else {
                getLineRight(lineNo)
            }
        }
    }
}
