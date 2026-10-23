package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SYNTHETIC regression tests for the grouping used by WebModeExtractor.readSlice (BubbleGrouping.groups /
 * groupContaining / joinLines -- the same functions the production path calls). They use hand-made boxes, not
 * real Webtoon screenshots, and cannot exercise the Android-only readSlice itself.
 * Boxes: centre x, centre y, width, height in px.
 */
class BubbleGroupingTest {
    private fun line(text: String, cx: Float, cy: Float, w: Float, h: Float = 30f, vertical: Boolean = false, angle: Float = 0f) =
        OcrBlock(text = text, top = (cy - h / 2).toInt(), left = (cx - w / 2).toInt(), cx = cx, cy = cy, w = w, h = h,
            angle = angle, fontPx = if (vertical) w else h, vertical = vertical)

    private fun groups(blocks: List<OcrBlock>, sep: ((OcrBlock, OcrBlock) -> Boolean)? = null) =
        BubbleGrouping.groups(blocks, blocks.indices.toList(), sep).map { g -> g.map { blocks[it].text } }

    @Test fun threeLineKoreanBubbleIsOneGroup() {
        val g = groups(listOf(
            line("몸에서 배어나오는", 400f, 100f, 280f),
            line("씻을 수 없는", 400f, 134f, 210f),
            line("피비린내.", 400f, 168f, 130f)))
        assertEquals(listOf(listOf("몸에서 배어나오는", "씻을 수 없는", "피비린내.")), g)
    }

    @Test fun joinedKoreanTextIsOneRequestString() {
        assertEquals("몸에서 배어나오는 씻을 수 없는 피비린내.",
            BubbleGrouping.joinLines(listOf("몸에서 배어나오는", "씻을 수 없는", "피비린내.")))
    }

    @Test fun englishLeftAlignedLinesGroup() {
        val g = groups(listOf(line("I told you", 250f, 100f, 200f), line("not to come here", 290f, 134f, 280f)))
        assertEquals(1, g.size)
    }

    @Test fun japaneseHorizontalLinesGroupWithoutInsertedSpace() {
        val g = groups(listOf(line("これは", 300f, 100f, 100f), line("テストです。", 300f, 134f, 190f)))
        assertEquals(1, g.size)
        assertEquals("これはテストです。", BubbleGrouping.joinLines(listOf("これは", "テストです。")))
    }

    @Test fun mixedKoreanEnglishKeepsSpace() {
        assertEquals("HP 가 0 이다", BubbleGrouping.joinLines(listOf("HP", "가 0 이다")))
    }

    @Test fun neighbouringStackedBubblesSeparatedByOutlineEvidence() {
        // Geometry alone would merge them (gap 4 px). The image says there is an outline between the lines.
        val a = line("첫 번째 말풍선", 300f, 100f, 220f); val b = line("두 번째 말풍선", 300f, 134f, 220f)
        assertEquals(1, groups(listOf(a, b)).size)
        assertEquals(2, groups(listOf(a, b)) { _, _ -> true }.size)
    }

    @Test fun sideBySideBubblesStaySeparate() {
        val g = groups(listOf(
            line("왼쪽 줄1", 150f, 100f, 160f), line("오른쪽 줄1", 600f, 105f, 170f),
            line("왼쪽 줄2", 150f, 134f, 160f), line("오른쪽 줄2", 600f, 139f, 170f)))
        assertEquals(2, g.size)
        assertEquals(setOf(listOf("왼쪽 줄1", "왼쪽 줄2"), listOf("오른쪽 줄1", "오른쪽 줄2")), g.toSet())
    }

    @Test fun differentlySizedLinesInOneBubbleGroup() {
        val g = groups(listOf(line("BIG", 300f, 100f, 160f, 44f), line("smaller line", 300f, 140f, 200f, 30f)))
        assertEquals(1, g.size)
    }

    @Test fun hugelyDifferentSizesStaySeparate() {
        assertEquals(2, groups(listOf(line("BIG", 300f, 100f, 200f, 90f), line("tiny caption", 300f, 160f, 120f, 18f))).size)
    }

    @Test fun verticalColumnsAreNotMergedByThisPath() {
        // Documents current behaviour: vertical columns stay individual (no vertical grouping on this path).
        val g = groups(listOf(
            line("縦書き", 300f, 200f, 30f, 120f, vertical = true), line("テキスト", 340f, 200f, 30f, 120f, vertical = true)))
        assertEquals(2, g.size)
    }

    @Test fun overlappingBoxesOfOneBubbleGroup() {
        assertEquals(1, groups(listOf(line("위 줄", 300f, 100f, 200f), line("아래 줄", 300f, 120f, 200f))).size)  // 10 px overlap
    }

    @Test fun widerLineSpacingInsideOneBubbleStillGroups() {
        // Webtoon OCR boxes can have a 1.5-font gap even when the lines belong to the same balloon.
        val g = groups(listOf(line("저자는 노인이", 300f, 100f, 220f), line("될 때까지 얼마나", 300f, 175f, 260f)))
        assertEquals(1, g.size)
        assertEquals("저자는 노인이 될 때까지 얼마나", BubbleGrouping.joinLines(g.single()))
    }

    @Test fun unusuallyLargeGapSeparates() {
        assertEquals(2, groups(listOf(line("위", 300f, 100f, 200f), line("아래", 300f, 190f, 200f))).size)  // gap 60 > 1 font
    }

    @Test fun degenerateBoxesDoNotCrashOrMerge() {
        val z = OcrBlock("?", 0, 0, cx = 0f, cy = 0f, w = 0f, h = 0f, fontPx = 0f)
        assertEquals(2, groups(listOf(z, line("정상", 300f, 100f, 100f))).size)
    }

    @Test fun strayLineBetweenTwoBubblesDoesNotChainThemTogether() {
        // Bubble A (2 lines, tight leading), stray X, bubble B (2 lines). X is within a generic 1-font gap of
        // both. The old `grp.any` growth fused A + X + B into one translation; now A and B must never share a group.
        val blocks = listOf(
            line("A1", 200f, 100f, 200f), line("A2", 200f, 134f, 200f),
            line("X", 210f, 187f, 120f),
            line("B1", 220f, 240f, 200f), line("B2", 220f, 274f, 200f))
        val g = groups(blocks)
        assertFalse(g.any { grp -> grp.any { it.startsWith("A") } && grp.any { it.startsWith("B") } })
        assertTrue(g.any { it.containsAll(listOf("A1", "A2")) })
    }

    @Test fun eachLineBelongsToExactlyOneGroup() {
        val blocks = (0 until 12).map { line("L$it", 200f + (it % 3) * 60f, 100f + it * 34f, 150f) }
        val all = BubbleGrouping.groups(blocks, blocks.indices.toList()).flatten()
        assertEquals(blocks.size, all.size)
        assertEquals(blocks.size, all.toSet().size)
    }
}
