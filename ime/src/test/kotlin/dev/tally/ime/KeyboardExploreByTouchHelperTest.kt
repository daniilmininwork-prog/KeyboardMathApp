package dev.tally.ime

import android.content.Context
import android.graphics.Rect
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import androidx.test.core.app.ApplicationProvider
import dev.tally.keyboard.engine.KeyDef
import dev.tally.keyboard.engine.KeyGeometry
import dev.tally.keyboard.engine.KeyId
import dev.tally.keyboard.engine.ResolvedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [KeyboardExploreByTouchHelper].
 *
 * Covers the virtual-view tree contract required by T1.6 (P0-8):
 *   - Virtual ID encoding is stable and round-trips correctly.
 *   - [virtualViewAt] maps pixel coordinates to the right key ID.
 *   - [visibleVirtualViews] enumerates every key exactly once.
 *   - [populateNodeForTest] sets the expected content description and bounds.
 *   - [performActionForTest] routes ACTION_CLICK through the key-click listener.
 *   - Null geometry degrades gracefully — no crash, no keys enumerated.
 *   - Long-press alternates appear in the accessibility content description.
 *   - Special-key labels (⇧ ⌫ ↵ and space) are humanised for TalkBack.
 *   - [invalidateKey] and [invalidateAllKeys] do not crash.
 *   - Updated rows are reflected in subsequent node queries (shift/layer change path).
 *
 * Tests use the internal test-seam accessors on [KeyboardExploreByTouchHelper] so the
 * protected ExploreByTouchHelper methods can be exercised without a live accessibility
 * session or reflection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardExploreByTouchHelperTest {

    private lateinit var view: KeyPlaneView
    private lateinit var helper: KeyboardExploreByTouchHelper
    private val clickedKeys = mutableListOf<Key>()

    /**
     * Minimal 2×2 geometry:
     *
     *   key(0,0) = [0..100) × [0..50)     key(0,1) = [100..200) × [0..50)
     *   key(1,0) = [0..100) × [50..100)   key(1,1) = [100..200) × [50..100)
     */
    private lateinit var geometry: KeyGeometry
    private lateinit var rows: List<KeyRow>

    private val kDef = KeyDef(code = 'a'.code, label = "a", width = 0.5f)

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        view = KeyPlaneView(ctx)

        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 200, 100)

        geometry = KeyGeometry(
            viewportWidth  = 200,
            viewportHeight = 100,
            rowHeight      = 50f,
            keys = listOf(
                ResolvedKey(KeyId(0, 0), kDef, left =   0f, top =  0f, right = 100f, bottom =  50f),
                ResolvedKey(KeyId(0, 1), kDef, left = 100f, top =  0f, right = 200f, bottom =  50f),
                ResolvedKey(KeyId(1, 0), kDef, left =   0f, top = 50f, right = 100f, bottom = 100f),
                ResolvedKey(KeyId(1, 1), kDef, left = 100f, top = 50f, right = 200f, bottom = 100f),
            ),
        )

        val key00 = Key(KeyCode.Char('a'), "a")
        val key01 = Key(KeyCode.Char('b'), "b")
        val key10 = Key(KeyCode.Char('c'), "c")
        val key11 = Key(KeyCode.Char('d'), "d")
        rows = listOf(
            KeyRow(listOf(key00, key01)),
            KeyRow(listOf(key10, key11)),
        )

        view.keyListener = { key, _ -> clickedKeys += key }
        view.currentGeometry = geometry
        view.currentRows = rows

        helper = view.a11yHelper
    }

    // ── Virtual ID encoding ───────────────────────────────────────────────────

    @Test
    fun virtualId_keyAt_row0Key0_is0() {
        // key(0,0) → 0*100 + 0 = 0
        val id = helper.virtualViewAt(50f, 25f)
        assertEquals(0, id)
    }

    @Test
    fun virtualId_keyAt_row0Key1_is1() {
        // key(0,1) → 0*100 + 1 = 1
        val id = helper.virtualViewAt(150f, 25f)
        assertEquals(1, id)
    }

    @Test
    fun virtualId_keyAt_row1Key0_is100() {
        // key(1,0) → 1*100 + 0 = 100
        val id = helper.virtualViewAt(50f, 75f)
        assertEquals(100, id)
    }

    @Test
    fun virtualId_keyAt_row1Key1_is101() {
        // key(1,1) → 1*100 + 1 = 101
        val id = helper.virtualViewAt(150f, 75f)
        assertEquals(101, id)
    }

    // ── virtualViewAt ─────────────────────────────────────────────────────────

    @Test
    fun virtualViewAt_outsideAllKeys_returnsInvalidId() {
        val id = helper.virtualViewAt(500f, 500f)
        assertEquals(ExploreByTouchHelper.INVALID_ID, id)
    }

    @Test
    fun virtualViewAt_nullGeometry_returnsInvalidId() {
        helper.geometry = null
        val id = helper.virtualViewAt(50f, 25f)
        assertEquals(ExploreByTouchHelper.INVALID_ID, id)
    }

    @Test
    fun virtualViewAt_exactBoundary_topLeftKey() {
        // Top-left corner of key(0,0): should still be inside [0, 0, 100, 50).
        val id = helper.virtualViewAt(0f, 0f)
        assertEquals(0, id)
    }

    // ── visibleVirtualViews ───────────────────────────────────────────────────

    @Test
    fun visibleVirtualViews_returnsAllFourKeys() {
        val ids = mutableListOf<Int>()
        helper.visibleVirtualViews(ids)
        assertEquals(4, ids.size)
    }

    @Test
    fun visibleVirtualViews_containsExpectedIds() {
        val ids = mutableListOf<Int>()
        helper.visibleVirtualViews(ids)
        assertTrue("key(0,0) id 0 must be present",   ids.contains(0))
        assertTrue("key(0,1) id 1 must be present",   ids.contains(1))
        assertTrue("key(1,0) id 100 must be present", ids.contains(100))
        assertTrue("key(1,1) id 101 must be present", ids.contains(101))
    }

    @Test
    fun visibleVirtualViews_nullGeometry_returnsEmpty() {
        helper.geometry = null
        val ids = mutableListOf<Int>()
        helper.visibleVirtualViews(ids)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun visibleVirtualViews_singleKeyGeometry_returnsOneId() {
        helper.geometry = KeyGeometry(
            viewportWidth = 100, viewportHeight = 50, rowHeight = 50f,
            keys = listOf(ResolvedKey(KeyId(0, 0), kDef, 0f, 0f, 100f, 50f)),
        )
        val ids = mutableListOf<Int>()
        helper.visibleVirtualViews(ids)
        assertEquals(1, ids.size)
    }

    // ── populateNodeForTest ───────────────────────────────────────────────────

    @Test
    fun populateNode_setsContentDescriptionFromLabel() {
        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)  // key(0,0), label "a"
        assertEquals("a", node.contentDescription)
        node.recycle()
    }

    @Test
    fun populateNode_setsBoundsInParent_forKey00() {
        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)  // key(0,0) = [0, 0, 100, 50]

        val bounds = Rect()
        node.getBoundsInParent(bounds)
        assertEquals(Rect(0, 0, 100, 50), bounds)
        node.recycle()
    }

    @Test
    fun populateNode_setsBoundsInParent_forKey11() {
        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(101, node)  // key(1,1) = [100, 50, 200, 100]

        val bounds = Rect()
        node.getBoundsInParent(bounds)
        assertEquals(Rect(100, 50, 200, 100), bounds)
        node.recycle()
    }

    @Test
    fun populateNode_isClickableAndFocusableAndEnabled() {
        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)

        assertTrue("node must be clickable",  node.isClickable)
        assertTrue("node must be focusable",  node.isFocusable)
        assertTrue("node must be enabled",    node.isEnabled)
        node.recycle()
    }

    @Test
    fun populateNode_classNameIsButton() {
        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)

        assertEquals("android.widget.Button", node.className)
        node.recycle()
    }

    @Test
    fun populateNode_addsClickAction() {
        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)

        val actionIds = node.actionList.map { it.id }
        assertTrue(
            "ACTION_CLICK must be present",
            actionIds.contains(AccessibilityNodeInfoCompat.ACTION_CLICK),
        )
        node.recycle()
    }

    @Test
    fun populateNode_invalidId_doesNotCrash() {
        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(9999, node)  // no such key in current geometry
        node.recycle()
    }

    @Test
    fun populateNode_nullGeometry_doesNotCrash() {
        helper.geometry = null
        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)
        node.recycle()
    }

    // ── performActionForTest ──────────────────────────────────────────────────

    @Test
    fun performAction_click_firstKey_routesToKeyClickListener() {
        val result = helper.performActionForTest(0, AccessibilityNodeInfoCompat.ACTION_CLICK)

        assertTrue("ACTION_CLICK should return true", result)
        assertEquals(1, clickedKeys.size)
        assertEquals(KeyCode.Char('a'), clickedKeys[0].code)
    }

    @Test
    fun performAction_click_secondKey_routesCorrectKey() {
        helper.performActionForTest(1, AccessibilityNodeInfoCompat.ACTION_CLICK)

        assertEquals(1, clickedKeys.size)
        assertEquals(KeyCode.Char('b'), clickedKeys[0].code)
    }

    @Test
    fun performAction_click_lowerRowKey_routesCorrectKey() {
        helper.performActionForTest(100, AccessibilityNodeInfoCompat.ACTION_CLICK)

        assertEquals(1, clickedKeys.size)
        assertEquals(KeyCode.Char('c'), clickedKeys[0].code)
    }

    @Test
    fun performAction_unknownAction_returnsFalse() {
        val result = helper.performActionForTest(
            0,
            AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
        )
        assertFalse(result)
        assertTrue(clickedKeys.isEmpty())
    }

    @Test
    fun performAction_invalidId_returnsFalse() {
        val result = helper.performActionForTest(9999, AccessibilityNodeInfoCompat.ACTION_CLICK)
        assertFalse(result)
        assertTrue(clickedKeys.isEmpty())
    }

    @Test
    fun performAction_clickPathIsSameAsRealTap() {
        // The a11y click listener calls the same keyListener as a real touch event.
        // Confirm the same Key object (by code) is delivered in both paths.
        val realTapKeys = mutableListOf<Key>()
        view.keyListener = { key, _ ->
            clickedKeys += key
            realTapKeys += key
        }

        // Simulate a real touch (via keyListener directly as the PointerTrackerDispatchTest does).
        view.keyListener?.invoke(Key(KeyCode.Char('a'), "a"), android.os.SystemClock.uptimeMillis())

        // Now simulate TalkBack ACTION_CLICK.
        helper.performActionForTest(0, AccessibilityNodeInfoCompat.ACTION_CLICK)

        assertEquals(2, clickedKeys.size)
        assertEquals(clickedKeys[0].code, clickedKeys[1].code)
    }

    // ── Long-press alternate descriptions ─────────────────────────────────────

    @Test
    fun populateNode_moreKeys_appendedToDescription() {
        helper.rows = listOf(
            KeyRow(listOf(
                Key(KeyCode.Char('e'), "e", moreKeys = listOf("é", "ê", "ë")),
                Key(KeyCode.Char('b'), "b"),
            )),
        )
        helper.geometry = KeyGeometry(
            viewportWidth = 200, viewportHeight = 50, rowHeight = 50f,
            keys = listOf(
                ResolvedKey(KeyId(0, 0), kDef, 0f, 0f, 100f, 50f),
                ResolvedKey(KeyId(0, 1), kDef, 100f, 0f, 200f, 50f),
            ),
        )

        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)

        val desc = node.contentDescription?.toString() ?: ""
        assertTrue("Description should mention 'é'", desc.contains("é"))
        assertTrue("Description should mention 'ê'", desc.contains("ê"))
        assertTrue("Description should mention 'ë'", desc.contains("ë"))
        node.recycle()
    }

    @Test
    fun populateNode_noMoreKeys_descriptionIsJustLabel() {
        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(1, node)  // key(0,1) = "b", no alternates
        assertEquals("b", node.contentDescription)
        node.recycle()
    }

    // ── Special-key label humanisation ───────────────────────────────────────

    @Test
    fun populateNode_shiftKey_describedAsShift() {
        helper.rows = listOf(KeyRow(listOf(Key(KeyCode.Shift, "⇧"))))
        helper.geometry = KeyGeometry(
            viewportWidth = 100, viewportHeight = 50, rowHeight = 50f,
            keys = listOf(ResolvedKey(KeyId(0, 0), kDef, 0f, 0f, 100f, 50f)),
        )

        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)
        assertEquals("Shift", node.contentDescription)
        node.recycle()
    }

    @Test
    fun populateNode_backspaceKey_describedAsBackspace() {
        helper.rows = listOf(KeyRow(listOf(Key(KeyCode.Backspace, "⌫"))))
        helper.geometry = KeyGeometry(
            viewportWidth = 100, viewportHeight = 50, rowHeight = 50f,
            keys = listOf(ResolvedKey(KeyId(0, 0), kDef, 0f, 0f, 100f, 50f)),
        )

        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)
        assertEquals("Backspace", node.contentDescription)
        node.recycle()
    }

    @Test
    fun populateNode_returnKey_describedAsReturn() {
        helper.rows = listOf(KeyRow(listOf(Key(KeyCode.Enter, "↵"))))
        helper.geometry = KeyGeometry(
            viewportWidth = 100, viewportHeight = 50, rowHeight = 50f,
            keys = listOf(ResolvedKey(KeyId(0, 0), kDef, 0f, 0f, 100f, 50f)),
        )

        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)
        assertEquals("Return", node.contentDescription)
        node.recycle()
    }

    @Test
    fun populateNode_spaceKey_describedAsSpace() {
        helper.rows = listOf(KeyRow(listOf(Key(KeyCode.Space, ""))))
        helper.geometry = KeyGeometry(
            viewportWidth = 100, viewportHeight = 50, rowHeight = 50f,
            keys = listOf(ResolvedKey(KeyId(0, 0), kDef, 0f, 0f, 100f, 50f)),
        )

        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)
        assertEquals("Space", node.contentDescription)
        node.recycle()
    }

    // ── Shift/layer change invalidation ───────────────────────────────────────

    @Test
    fun invalidateAllKeys_doesNotCrash() {
        // The practical assertion is that TalkBack re-reads updated labels;
        // the unit-testable assertion is that no exception is thrown.
        helper.invalidateAllKeys()
    }

    @Test
    fun invalidateKey_doesNotCrash() {
        helper.invalidateKey(KeyId(0, 0))
        helper.invalidateKey(KeyId(1, 1))
    }

    @Test
    fun shiftLayerChange_updatedRowsReflectedInNodeQuery() {
        // Simulate a shift-layer change: replace rows, invalidate, then re-query.
        val shiftedRows = listOf(
            KeyRow(listOf(
                Key(KeyCode.Char('A'), "A"),
                Key(KeyCode.Char('B'), "B"),
            )),
            KeyRow(listOf(
                Key(KeyCode.Char('C'), "C"),
                Key(KeyCode.Char('D'), "D"),
            )),
        )
        view.currentRows = shiftedRows  // also updates helper.rows
        helper.invalidateAllKeys()

        val node = AccessibilityNodeInfoCompat.obtain()
        helper.populateNodeForTest(0, node)
        // key(0,0) is now "A" (uppercase after shift)
        assertEquals("A", node.contentDescription)
        node.recycle()
    }

    @Test
    fun geometryChange_newGeometryReflectedInEnumeration() {
        val oneKey = KeyGeometry(
            viewportWidth = 100, viewportHeight = 50, rowHeight = 50f,
            keys = listOf(ResolvedKey(KeyId(0, 0), kDef, 0f, 0f, 100f, 50f)),
        )
        view.currentGeometry = oneKey
        helper.invalidateAllKeys()

        val ids = mutableListOf<Int>()
        helper.visibleVirtualViews(ids)
        assertEquals(1, ids.size)
    }

    // ── Accessibility delegate wiring ─────────────────────────────────────────

    @Test
    fun view_hasAccessibilityDelegateSet() {
        val delegate = androidx.core.view.ViewCompat.getAccessibilityDelegate(view)
        assertNotNull("Accessibility delegate must be set for TalkBack explore-by-touch", delegate)
    }

    @Test
    fun view_a11yHelperIsNonNull() {
        assertNotNull(view.a11yHelper)
    }
}
