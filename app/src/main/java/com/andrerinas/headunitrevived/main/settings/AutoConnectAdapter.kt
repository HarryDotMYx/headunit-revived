package com.andrerinas.headunitrevived.main.settings

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.R

data class AutoConnectMethod(
    val id: String,
    val nameResId: Int,
    val descriptionResId: Int,
    var isEnabled: Boolean
)

class AutoConnectAdapter(
    private val items: MutableList<AutoConnectMethod>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<AutoConnectAdapter.ViewHolder>() {

    var itemTouchHelper: ItemTouchHelper? = null

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: ImageView = view.findViewById(R.id.drag_handle)
        val priorityNumber: TextView = view.findViewById(R.id.priority_number)
        val methodName: TextView = view.findViewById(R.id.method_name)
        val methodDescription: TextView = view.findViewById(R.id.method_description)
        val btnMoveUp: ImageButton = view.findViewById(R.id.btn_move_up)
        val btnMoveDown: ImageButton = view.findViewById(R.id.btn_move_down)
        val methodToggle: Switch = view.findViewById(R.id.method_toggle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_auto_connect, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.priorityNumber.text = "#${position + 1}"
        holder.methodName.setText(item.nameResId)
        holder.methodDescription.setText(item.descriptionResId)

        // Arrow visibility
        holder.btnMoveUp.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        holder.btnMoveDown.visibility = if (position == items.size - 1) View.INVISIBLE else View.VISIBLE

        // Toggle
        holder.methodToggle.setOnCheckedChangeListener(null)
        holder.methodToggle.isChecked = item.isEnabled
        holder.methodToggle.setOnCheckedChangeListener { _, isChecked ->
            item.isEnabled = isChecked
            onChanged()
        }

        // Arrow click listeners
        holder.btnMoveUp.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos > 0) {
                swapItems(pos, pos - 1)
            }
        }

        holder.btnMoveDown.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos < items.size - 1) {
                swapItems(pos, pos + 1)
            }
        }

        // Drag handle touch listener
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                itemTouchHelper?.startDrag(holder)
            }
            false
        }

        // Background based on position
        val bgRes = when {
            items.size == 1 -> R.drawable.bg_setting_single
            position == 0 -> R.drawable.bg_setting_top
            position == items.size - 1 -> R.drawable.bg_setting_bottom
            else -> R.drawable.bg_setting_middle
        }
        holder.itemView.setBackgroundResource(bgRes)
    }

    override fun getItemCount() = items.size

    /**
     * Pure list move + [notifyItemMoved] — no rebind. Safe to call from
     * [AutoConnectTouchCallback.onMove] during an active drag: [notifyItemChanged] there would
     * rebind/replace the ViewHolder ItemTouchHelper still holds as the dragged item, breaking
     * or flickering the drag shadow. No-ops on an out-of-range index (bindingAdapterPosition can
     * be NO_POSITION mid-gesture).
     */
    fun moveItem(from: Int, to: Int) {
        if (from < 0 || from >= items.size || to < 0 || to >= items.size) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    /** For the arrow-button reorder (not an active drag): moves the item and refreshes every
     *  row between [from] and [to] so "#N" labels and arrow visibility are correct, not just
     *  the two endpoints. */
    fun swapItems(from: Int, to: Int) {
        if (from < 0 || from >= items.size || to < 0 || to >= items.size) return
        moveItem(from, to)
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        notifyItemRangeChanged(lo, hi - lo + 1)
        onChanged()
    }

    /** Called once a drag-driven reorder finishes (see [AutoConnectTouchCallback.clearView]) to
     *  refresh labels/arrows for the whole list — intentionally skipped during the drag itself. */
    fun refreshAfterDrag() {
        notifyDataSetChanged()
        onChanged()
    }

    fun getOrderedIds(): List<String> = items.map { it.id }

    fun getEnabledStates(): Map<String, Boolean> = items.associate { it.id to it.isEnabled }
}

class AutoConnectTouchCallback(
    private val adapter: AutoConnectAdapter
) : ItemTouchHelper.Callback() {

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        // [FIX] bindingAdapterPosition can be NO_POSITION (-1) mid-gesture (e.g. right after an
        // adapter update); swapItems()/the old code had no range check and would throw.
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
        adapter.moveItem(from, to)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // No swipe
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        // The drag itself only ever calls moveItem() (no rebinds, to avoid breaking
        // ItemTouchHelper's held ViewHolder reference) — refresh labels/arrows now that it's over.
        adapter.refreshAfterDrag()
    }

    override fun isLongPressDragEnabled() = false
}
