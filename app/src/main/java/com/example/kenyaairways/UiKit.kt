package com.example.kenyaairways

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kenyaairways.databinding.FragmentListBinding
import com.example.kenyaairways.databinding.ItemRowBinding
import com.google.android.material.chip.Chip


class RowAdapter<T>(
    items: List<T>,
    private val bind: (ItemRowBinding, T) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    class VH(val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root)

    private var data: List<T> = items

    fun submit(newItems: List<T>) { data = newItems; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.b.reset()
        bind(holder.b, data[position])
    }
}

fun ItemRowBinding.reset() {
    tvSubtitle.visibility = View.GONE
    tvDetail.visibility = View.GONE
    tvBadge.visibility = View.GONE
    progress.visibility = View.GONE
    switchRow.setOnCheckedChangeListener(null)
    switchRow.visibility = View.GONE
    actions.visibility = View.GONE
    btnPrimary.isEnabled = true
    btnPrimary.setOnClickListener(null)
    btnSecondary.visibility = View.GONE
    btnSecondary.setOnClickListener(null)
    root.setOnClickListener(null)
    root.isClickable = false
    root.alpha = 1f
    tvTitle.setTypeface(null, Typeface.BOLD)
    viewAccent.setBackgroundColor(Color.TRANSPARENT)
}

private fun TextView.showOrHide(value: String?) {
    visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    text = value
}

fun ItemRowBinding.setTexts(title: String, subtitle: String? = null, detail: String? = null) {
    tvTitle.text = title
    tvSubtitle.showOrHide(subtitle)
    tvDetail.showOrHide(detail)
}

fun ItemRowBinding.badge(text: String, colorRes: Int) {
    tvBadge.visibility = View.VISIBLE
    tvBadge.text = text
    tvBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(root.context, colorRes))
}

fun ItemRowBinding.accent(colorRes: Int) =
    viewAccent.setBackgroundColor(ContextCompat.getColor(root.context, colorRes))

fun ItemRowBinding.showProgress(percent: Int, colorRes: Int) {
    progress.visibility = View.VISIBLE
    progress.setProgressCompat(percent.coerceIn(0, 100), false)
    progress.setIndicatorColor(ContextCompat.getColor(root.context, colorRes))
}


abstract class ListFragment(private val screenTitle: String) : Fragment() {

    private var _b: FragmentListBinding? = null
    protected val b get() = _b!!
    protected val isViewReady: Boolean get() = _b != null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentListBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        b.rvList.layoutManager = LinearLayoutManager(requireContext())
        onReady()
    }

    abstract fun onReady()

    protected fun setSummary(text: String?, onClick: (() -> Unit)? = null) {
        b.tvSummary.visibility = if (text == null) View.GONE else View.VISIBLE
        b.tvSummary.text = text
        if (onClick != null) b.tvSummary.setOnClickListener { onClick() }
        else { b.tvSummary.setOnClickListener(null); b.tvSummary.isClickable = false }
    }

    protected fun showEmpty(show: Boolean, message: String = "Nothing to show") {
        b.tvEmpty.visibility = if (show) View.VISIBLE else View.GONE
        b.tvEmpty.text = message
    }

    protected fun setupChips(labels: List<String>, onSelect: (Int) -> Unit) {
        b.chipScroll.visibility = View.VISIBLE
        b.chipGroup.removeAllViews()
        labels.forEach { label ->
            b.chipGroup.addView(Chip(requireContext()).apply {
                id = View.generateViewId(); text = label; isCheckable = true
            })
        }
        b.chipGroup.setOnCheckedStateChangeListener { group, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<View>(it) }
            onSelect(if (chip == null) 0 else group.indexOfChild(chip))
        }
        (b.chipGroup.getChildAt(0) as Chip).isChecked = true
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setScreenTitle(screenTitle)
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
