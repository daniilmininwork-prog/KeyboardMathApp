package dev.tally.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Clipboard history panel rendered as a self-contained [LinearLayout].
 *
 * Layout (top-to-bottom):
 *   1. Header row — "Clipboard" label + "Clear" button.
 *   2. History list — [ListView] of [ClipEntry] rows, each with text, entity chip, and pin toggle.
 *
 * ## Insertion contract (binding)
 *
 * When the user taps a clipboard entry the panel calls [ClipboardRepository.insert], which
 * forwards to the [onInsert] lambda wired by the IME service. That lambda calls
 * [android.view.inputmethod.InputConnection.commitText] directly — there is no reference to
 * [android.content.ClipboardManager] in any insertion path. This is enforced structurally:
 * neither this file nor [ClipboardRepository] imports or references ClipboardManager.
 *
 * Integration contract:
 *   - Set [repository] before attaching or use [bind] to update it.
 *   - Call [refresh] after any external change to re-render the list.
 *   - The panel does not auto-refresh; it is stateless between [bind]/[refresh] calls.
 *
 * @param context Application or service context.
 */
internal class ClipboardPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var repository: ClipboardRepository? = null
    private val adapter: ClipAdapter

    // ── Child views ───────────────────────────────────────────────────────────

    private val emptyLabel: TextView
    private val listView: ListView

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#F5F5F5"))

        // Header row
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }
        val title = TextView(context).apply {
            text = "Clipboard"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val clearBtn = TextView(context).apply {
            text = "Clear"
            textSize = 12f
            setTextColor(Color.parseColor("#5C6BC0"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                repository?.clearHistory()
                refresh()
            }
        }
        header.addView(title)
        header.addView(clearBtn)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Empty-state label
        emptyLabel = TextView(context).apply {
            text = "No clipboard history"
            textSize = 13f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        addView(emptyLabel, LayoutParams(LayoutParams.MATCH_PARENT, dp(60)))

        // History list
        adapter = ClipAdapter(context)
        listView = ListView(context).apply {
            divider = null
            dividerHeight = dp(4)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToPadding = false
            this.adapter = this@ClipboardPanelView.adapter
        }
        addView(listView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * Binds the panel to a [ClipboardRepository] and renders the current history.
     */
    fun bind(repo: ClipboardRepository) {
        repository = repo
        adapter.repository = repo
        refresh()
    }

    /**
     * Re-renders the list from the current repository state.
     *
     * Call after any external store change (e.g. new entry captured from paste action).
     */
    fun refresh() {
        val entries = repository?.entries() ?: emptyList()
        adapter.submitList(entries)
        emptyLabel.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility   = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

/**
 * [BaseAdapter] for the clipboard history list.
 *
 * Each row shows:
 * - An entity-type chip label (URL / Email / Phone / Address) when applicable.
 * - The clipped text (truncated to two lines).
 * - A pin toggle button.
 * - A tap target on the text that triggers insertion via the repository.
 */
private class ClipAdapter(private val context: Context) : BaseAdapter() {

    var repository: ClipboardRepository? = null
    private var entries: List<ClipEntry> = emptyList()

    fun submitList(list: List<ClipEntry>) {
        entries = list
        notifyDataSetChanged()
    }

    override fun getCount(): Int = entries.size
    override fun getItem(position: Int): Any = entries[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row = convertView as? LinearLayout ?: buildRow()
        val entry = entries[position]
        val chip  = row.getChildAt(0) as TextView
        val text  = row.getChildAt(1) as TextView
        val pin   = row.getChildAt(2) as TextView

        // Entity chip
        val chipLabel = entityChipLabel(entry.entityType)
        if (chipLabel != null) {
            chip.text = chipLabel
            chip.visibility = View.VISIBLE
        } else {
            chip.visibility = View.GONE
        }

        text.text = entry.text
        pin.text  = if (entry.isPinned) "📌" else "○"
        pin.contentDescription = if (entry.isPinned) "Unpin" else "Pin"

        row.setOnClickListener {
            repository?.insert(entry)
        }
        pin.setOnClickListener {
            repository?.togglePin(entry)
            notifyDataSetChanged()
        }
        return row
    }

    private fun buildRow(): LinearLayout {
        val dm = context.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density + 0.5f).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val chip = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#5C6BC0"))
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        row.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(6) })

        val text = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(text)

        val pin = TextView(context).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        row.addView(pin, LinearLayout.LayoutParams(dp(40), dp(40)))

        return row
    }

    private fun entityChipLabel(type: EntityType): String? = when (type) {
        EntityType.URL     -> "URL"
        EntityType.EMAIL   -> "Email"
        EntityType.PHONE   -> "Phone"
        EntityType.ADDRESS -> "Addr"
        EntityType.NONE    -> null
    }
}
