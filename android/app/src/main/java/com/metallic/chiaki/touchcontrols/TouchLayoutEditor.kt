package com.metallic.chiaki.touchcontrols

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Lets the user move / resize / change opacity of any registered touch-control view.
 * Edit mode: drag = move, tap = dialog (size + opacity + reset).
 */
class TouchLayoutEditor(
	private val activity: Activity,
	private val parent: View,
	val store: TouchLayoutStore
)
{
	private class Entry(
		val view: View,
		val normalListener: View.OnTouchListener?,
		val extraLabel: String?,
		val extraAction: (() -> Unit)?
	)

	private val entries = LinkedHashMap<String, Entry>()
	var editMode = false
		private set
	val isEditing get() = editMode
	var onEditModeChanged: ((Boolean) -> Unit)? = null
	private var draggingId: String? = null

	init
	{
		parent.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
			if(r - l != or2 - ol || b - t != ob - ot) applyAll()
		}
	}

	/** [normalListener]: the view's usual OnTouchListener if it has one (null if it handles touches in onTouchEvent). */
	fun register(id: String, view: View, normalListener: View.OnTouchListener? = null,
				 extraLabel: String? = null, extraAction: (() -> Unit)? = null)
	{
		val entry = Entry(view, normalListener, extraLabel, extraAction)
		entries[id] = entry
		if(editMode) attachEditListener(id, entry)
		else if(normalListener != null) view.setOnTouchListener(normalListener)
		view.post { store.apply(id, view, parent, if(editMode) 0.5f else 0f) }
		// if the layout pass moves the view's default position, put it back where the user dropped it
		view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
			if(draggingId != id)
				store.apply(id, v, parent, if(editMode) 0.5f else 0f)
		}
	}

	fun unregister(id: String)
	{
		entries.remove(id)?.let { it.view.setOnTouchListener(null) }
	}

	fun applyAll()
	{
		for((id, e) in entries) store.apply(id, e.view, parent, if(editMode) 0.5f else 0f)
	}

	fun setEditMode(on: Boolean)
	{
		if(on == editMode) return
		editMode = on
		for((id, e) in entries)
		{
			if(on) attachEditListener(id, e) else
			{
				e.view.setOnTouchListener(e.normalListener)
				clearOutline(e.view)
			}
		}
		applyAll()
		if(on) Toast.makeText(activity, "Edit mode: drag to move, tap to resize / opacity. Back to exit.", Toast.LENGTH_LONG).show()
		else store.save()
		onEditModeChanged?.invoke(on)
	}

	private fun attachEditListener(id: String, e: Entry)
	{
		val slop = ViewConfiguration.get(activity).scaledTouchSlop
		var downX = 0f; var downY = 0f; var startTx = 0f; var startTy = 0f; var moved = false
		showOutline(e.view)
		e.view.setOnTouchListener { v, ev ->
			when(ev.actionMasked)
			{
				MotionEvent.ACTION_DOWN ->
				{
					downX = ev.rawX; downY = ev.rawY
					startTx = v.translationX; startTy = v.translationY
					moved = false
					v.parent?.requestDisallowInterceptTouchEvent(true)
				}
				MotionEvent.ACTION_MOVE ->
				{
					val dx = ev.rawX - downX
					val dy = ev.rawY - downY
					if(!moved && Math.hypot(dx.toDouble(), dy.toDouble()) > slop)
					{
						moved = true
						draggingId = id
					}
					if(moved)
					{
						// keep the button's center inside the screen so it can never get lost off-screen
						val baseX = v.left + v.width / 2f
						val baseY = v.top + v.height / 2f
						val centerX = (baseX + startTx + dx).coerceIn(0f, parent.width.toFloat())
						val centerY = (baseY + startTy + dy).coerceIn(0f, parent.height.toFloat())
						v.translationX = centerX - baseX
						v.translationY = centerY - baseY
					}
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
				{
					if(moved)
					{
						commitPosition(id, v)
						draggingId = null
					}
					else if(ev.actionMasked == MotionEvent.ACTION_UP)
						showDialog(id, e)
				}
			}
			true
		}
	}

	private fun commitPosition(id: String, v: View)
	{
		if(parent.width <= 0 || parent.height <= 0)
			return
		val l = store[id]
		l.cx = ((v.left + v.width / 2f + v.translationX) / parent.width).coerceIn(0f, 1f)
		l.cy = ((v.top + v.height / 2f + v.translationY) / parent.height).coerceIn(0f, 1f)
		store.save()
	}

	private fun showOutline(v: View)
	{
		if(Build.VERSION.SDK_INT >= 23)
			v.foreground = GradientDrawable().apply { setStroke(3, Color.CYAN); setColor(Color.TRANSPARENT) }
	}

	private fun clearOutline(v: View)
	{
		if(Build.VERSION.SDK_INT >= 23) v.foreground = null
	}

	private fun seek(max: Int, progress: Int, onChange: (Int) -> Unit) = SeekBar(activity).also {
		it.max = max
		it.progress = progress
		it.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if(fromUser) onChange(p) }
			override fun onStartTrackingTouch(s: SeekBar) {}
			override fun onStopTrackingTouch(s: SeekBar) {}
		})
	}

	private fun showDialog(id: String, e: Entry)
	{
		val l = store[id]
		val pad = (16 * activity.resources.displayMetrics.density).toInt()
		val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }

		val sizeLabel = TextView(activity).apply { text = "Size: ${(l.scale * 100).toInt()}%" }
		val sizeBar = seek(250, ((l.scale * 100).toInt() - 30).coerceIn(0, 250)) { p ->
			l.scale = (p + 30) / 100f
			e.view.scaleX = l.scale; e.view.scaleY = l.scale
			sizeLabel.text = "Size: ${p + 30}%"
		}
		val alphaLabel = TextView(activity).apply { text = "Opacity: ${(l.alpha * 100).toInt()}%" }
		val alphaBar = seek(90, ((l.alpha * 100).toInt() - 10).coerceIn(0, 90)) { p ->
			l.alpha = (p + 10) / 100f
			e.view.alpha = maxOf(l.alpha, 0.5f)
			alphaLabel.text = "Opacity: ${p + 10}%"
		}
		box.addView(sizeLabel); box.addView(sizeBar); box.addView(alphaLabel); box.addView(alphaBar)

		val b = AlertDialog.Builder(activity)
			.setTitle(id)
			.setView(box)
			.setPositiveButton("OK", null)
			.setNeutralButton("Reset") { _, _ -> store.reset(id); applyAll() }
			.setNegativeButton("Exit edit mode") { _, _ -> setEditMode(false) }
			.setOnDismissListener { store.save() }
		val extraLabel = e.extraLabel
		val extraAction = e.extraAction
		if(extraLabel != null && extraAction != null)
			b.setNeutralButton(extraLabel) { _, _ -> extraAction() }
		b.show()
	}
}
