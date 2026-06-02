package dev.tally.emoji

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Emoji picker panel rendered as a self-contained [LinearLayout].
 *
 * Layout (top-to-bottom):
 *   1. Search bar — [EditText] for keyword filtering.
 *   2. Category tab strip — horizontal scroll with one [TextView] tab per category.
 *   3. Emoji grid — [GridView] showing the current category or search results.
 *   4. Skin-tone picker row — shown only when an emoji with skin-tone variants is long-pressed.
 *
 * Integration contract:
 *   - Set [onEmojiSelected] before attaching the view; it is called with the final emoji
 *     string (after skin-tone selection if applicable) when the user taps an emoji.
 *   - Call [bind] with a loaded [EmojiRepository] once the repository is ready. The panel
 *     renders nothing until [bind] is called.
 *   - The panel does not self-scroll; embed it inside the IME view hierarchy at a fixed height.
 *
 * No [android.view.inputmethod.InputConnection] involvement: emoji insertion is the caller's
 * responsibility via [onEmojiSelected]. The panel only dispatches selection events.
 */
class EmojiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Called with the selected emoji string when the user taps an emoji in the grid. */
    var onEmojiSelected: ((String) -> Unit)? = null

    private var repository: EmojiRepository? = null
    private var currentCategory: EmojiCategory = EmojiCategory.SMILEYS_EMOTION
    private var currentEntries: List<EmojiEntry> = emptyList()

    // ── Child views ───────────────────────────────────────────────────────────

    private val searchBar: EditText
    private val categoryScrollView: HorizontalScrollView
    private val categoryStrip: LinearLayout
    private val grid: GridView
    private val skinToneRow: LinearLayout
    private val adapter: EmojiGridAdapter

    private var pendingSkinToneEntry: EmojiEntry? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#F5F5F5"))

        // Search bar
        searchBar = EditText(context).apply {
            hint = "Search emoji…"
            maxLines = 1
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
            textSize = 14f
        }
        addView(searchBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        // Category tab strip
        categoryScrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        categoryStrip = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        categoryScrollView.addView(categoryStrip)
        addView(categoryScrollView, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))

        // Emoji grid
        adapter = EmojiGridAdapter(context)
        grid = GridView(context).apply {
            numColumns = GRID_COLUMNS
            verticalSpacing = dp(4)
            horizontalSpacing = dp(4)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToPadding = false
            this.adapter = this@EmojiPanelView.adapter
        }
        // Give the grid weight so it fills remaining space
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Skin-tone picker (hidden by default)
        skinToneRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        addView(skinToneRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))

        // Wire up interactions
        wireSearch()
        wireGrid()
    }

    /**
     * Binds the panel to a loaded [EmojiRepository].
     *
     * Must be called on the main thread. The panel immediately shows the first available
     * category's emoji.
     */
    fun bind(repo: EmojiRepository) {
        repository = repo
        rebuildCategoryTabs(repo)
        val firstCategory = repo.availableCategories().firstOrNull()
            ?: EmojiCategory.SMILEYS_EMOTION
        showCategory(firstCategory)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun rebuildCategoryTabs(repo: EmojiRepository) {
        categoryStrip.removeAllViews()
        for (cat in repo.availableCategories()) {
            val tab = TextView(context).apply {
                text = categoryIcon(cat)
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    searchBar.setText("")
                    showCategory(cat)
                    scrollCategoryTabIntoView(this)
                }
            }
            categoryStrip.addView(tab, LinearLayout.LayoutParams(dp(44), dp(40)))
        }
    }

    private fun showCategory(category: EmojiCategory) {
        currentCategory = category
        val repo = repository ?: return
        currentEntries = repo.forCategory(category)
        adapter.submitList(currentEntries)
        hideSkinToneRow()
    }

    private fun wireSearch() {
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty().trim()
                val repo  = repository ?: return
                if (query.isEmpty()) {
                    showCategory(currentCategory)
                } else {
                    currentEntries = repo.search(query)
                    adapter.submitList(currentEntries)
                    hideSkinToneRow()
                }
            }
        })
    }

    private fun wireGrid() {
        grid.setOnItemClickListener { _, _, position, _ ->
            val entry = currentEntries.getOrNull(position) ?: return@setOnItemClickListener
            if (entry.skinTones.isNotEmpty()) {
                showSkinToneRow(entry)
            } else {
                commitEmoji(entry.emoji)
            }
        }

        grid.setOnItemLongClickListener { _, _, position, _ ->
            val entry = currentEntries.getOrNull(position) ?: return@setOnItemLongClickListener false
            if (entry.skinTones.isNotEmpty()) {
                showSkinToneRow(entry)
                true
            } else {
                false
            }
        }
    }

    private fun showSkinToneRow(entry: EmojiEntry) {
        pendingSkinToneEntry = entry
        skinToneRow.removeAllViews()

        // Base emoji (no skin tone) first
        skinToneRow.addView(skinToneCell(entry.emoji))

        for (variant in entry.skinTones) {
            skinToneRow.addView(skinToneCell(variant))
        }

        skinToneRow.visibility = View.VISIBLE
    }

    private fun skinToneCell(emoji: String): View {
        return TextView(context).apply {
            text = emoji
            textSize = 24f
            gravity = Gravity.CENTER
            setOnClickListener { commitEmoji(emoji) }
        }.also { tv ->
            tv.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
    }

    private fun hideSkinToneRow() {
        pendingSkinToneEntry = null
        skinToneRow.visibility = View.GONE
        skinToneRow.removeAllViews()
    }

    private fun commitEmoji(emoji: String) {
        hideSkinToneRow()
        repository?.recents?.record(emoji)
        onEmojiSelected?.invoke(emoji)
    }

    private fun scrollCategoryTabIntoView(tab: View) {
        categoryScrollView.post {
            val left = tab.left
            val right = tab.right
            categoryScrollView.smoothScrollTo((left + right) / 2 - categoryScrollView.width / 2, 0)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val GRID_COLUMNS = 8

        private fun categoryIcon(category: EmojiCategory): String = when (category) {
            EmojiCategory.RECENTS        -> "🕐"
            EmojiCategory.SMILEYS_EMOTION -> "😀"
            EmojiCategory.PEOPLE_BODY    -> "👋"
            EmojiCategory.ANIMALS_NATURE -> "🐶"
            EmojiCategory.FOOD_DRINK     -> "🍎"
            EmojiCategory.TRAVEL_PLACES  -> "✈️"
            EmojiCategory.ACTIVITIES     -> "⚽"
            EmojiCategory.OBJECTS        -> "💡"
            EmojiCategory.SYMBOLS        -> "❤️"
        }
    }
}

/**
 * Flat [android.widget.BaseAdapter] for the emoji grid.
 *
 * Each cell is a [TextView] showing the emoji glyph at 24 sp. The grid cell dimensions
 * are uniform; the adapter does not vary heights.
 */
private class EmojiGridAdapter(private val context: Context) :
    android.widget.BaseAdapter() {

    private var entries: List<EmojiEntry> = emptyList()

    fun submitList(list: List<EmojiEntry>) {
        entries = list
        notifyDataSetChanged()
    }

    override fun getCount(): Int = entries.size
    override fun getItem(position: Int): Any = entries[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val cell = (convertView as? TextView) ?: TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            val size = (40 * context.resources.displayMetrics.density + 0.5f).toInt()
            layoutParams = ViewGroup.LayoutParams(size, size)
        }
        cell.text = entries[position].emoji
        return cell
    }
}
