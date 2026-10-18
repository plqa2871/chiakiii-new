// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.metallic.chiaki.R
import kotlin.math.hypot

class ButtonView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	private val haptics = ButtonHaptics(context)

	var buttonPressed = false
		private set(value)
		{
			val diff = field != value
			field = value
			if(diff)
			{
				if(value)
					haptics.trigger()
				invalidate()
				buttonPressedCallback?.let { it(field) }
			}
		}

	var buttonPressedCallback: ((Boolean) -> Unit)? = null

	private val drawableIdle: Drawable?
	private val drawablePressed: Drawable?

	init
	{
		context.theme.obtainStyledAttributes(attrs, R.styleable.ButtonView, 0, 0).apply {
			drawableIdle = getDrawable(R.styleable.ButtonView_drawableIdle)
			drawablePressed = getDrawable(R.styleable.ButtonView_drawablePressed)
			recycle()
		}

		isClickable = true
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val drawable = if(buttonPressed) drawablePressed else drawableIdle
		drawable?.setBounds(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
		drawable?.draw(canvas)
	}

	// ---------------------------------------------------------------------------------------------
	// Touch handling
	//  - a touch only counts on the VISIBLE button (drawable area without the padding; round buttons = circle)
	//  - a finger can slide from one button to another without lifting: the first is released, the new one pressed
	//  - chords: a touch right on the seam between two buttons that are (nearly) touching presses BOTH
	// ---------------------------------------------------------------------------------------------

	/** pointer ids currently pressing this button */
	private val pressers = HashSet<Int>()

	/** only for the view that received the touch: pointer id -> buttons currently pressed by that finger */
	private val owned = HashMap<Int, Set<ButtonView>>()

	private fun addPresser(id: Int)
	{
		pressers.add(id)
		buttonPressed = true
	}

	private fun removePresser(id: Int)
	{
		pressers.remove(id)
		buttonPressed = pressers.isNotEmpty()
	}

	private fun releaseAll()
	{
		for((id, set) in owned)
			for(v in set)
				v.removePresser(id)
		owned.clear()
		pressers.clear()
		buttonPressed = false
	}

	/** Distance in px (screen scale) from a point in this view's local coordinates to the visible button; 0 = on it. */
	private fun distanceTo(x: Float, y: Float): Float
	{
		val l = paddingLeft.toFloat()
		val t = paddingTop.toFloat()
		val r = (width - paddingRight).toFloat()
		val b = (height - paddingBottom).toFloat()
		if(r <= l || b <= t)
			return Float.MAX_VALUE
		val w = r - l
		val h = b - t
		val d: Float
		if(w / h in 0.8f..1.25f)
		{
			// (nearly) square content => round button
			val radius = (w + h) / 4f
			d = maxOf(0f, hypot(x - (l + r) / 2f, y - (t + b) / 2f) - radius)
		}
		else
		{
			val dx = maxOf(l - x, 0f, x - r)
			val dy = maxOf(t - y, 0f, y - b)
			d = hypot(dx, dy)
		}
		return d * scaleX
	}

	private fun fromParent(px: Float, py: Float): FloatArray?
	{
		val inverse = Matrix()
		if(!matrix.invert(inverse))
			return null
		val pts = floatArrayOf(px - left, py - top)
		inverse.mapPoints(pts)
		return pts
	}

	/**
	 * Buttons pressed by the given pointer:
	 *  - every button the finger is on, plus every button within CHORD_SLOP_DP of the finger ("seam" press)
	 *  - or, if the finger is on no button, the buttons within CHORD_SLOP_DP only when there are at least two of them
	 *    (touching buttons); a single button never reacts outside its visible shape
	 */
	private fun buttonsUnder(pointerIndex: Int, event: MotionEvent): Set<ButtonView>
	{
		val group = parent as? ViewGroup ?: return emptySet()
		val local = floatArrayOf(event.getX(pointerIndex), event.getY(pointerIndex))
		matrix.mapPoints(local)
		val px = local[0] + left
		val py = local[1] + top
		val slop = CHORD_SLOP_DP * resources.displayMetrics.density

		val exact = ArrayList<ButtonView>()
		val near = ArrayList<ButtonView>()
		for(i in 0 until group.childCount)
		{
			val c = group.getChildAt(i) as? ButtonView ?: continue
			if(c.visibility != VISIBLE)
				continue
			val p = c.fromParent(px, py) ?: continue
			val d = c.distanceTo(p[0], p[1])
			if(d <= 0f)
				exact.add(c)
			else if(d <= slop)
				near.add(c)
		}
		return when
		{
			exact.isNotEmpty() -> (exact + near).toSet()
			near.size >= 2 -> near.toSet()
			else -> emptySet()
		}
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
			{
				val index = event.actionIndex
				val set = buttonsUnder(index, event)
				if(!set.contains(this))
					return false // not on this button: let the next view (maybe another button) handle it
				val id = event.getPointerId(index)
				owned[id] = set
				for(v in set)
					v.addPresser(id)
			}
			MotionEvent.ACTION_MOVE ->
			{
				for(i in 0 until event.pointerCount)
				{
					val id = event.getPointerId(i)
					val current = owned[id] ?: continue
					val now = buttonsUnder(i, event)
					if(now != current)
					{
						for(v in current)
							if(v !in now)
								v.removePresser(id)
						for(v in now)
							if(v !in current)
								v.addPresser(id)
						owned[id] = now
					}
				}
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
			{
				val id = event.getPointerId(event.actionIndex)
				owned.remove(id)?.forEach { it.removePresser(id) }
			}
			MotionEvent.ACTION_CANCEL -> releaseAll()
		}
		return true
	}

	override fun onDetachedFromWindow()
	{
		releaseAll()
		super.onDetachedFromWindow()
	}

	companion object
	{
		/** Two buttons whose visible edges are closer than 2x this value are pressed together when you touch between them. */
		private const val CHORD_SLOP_DP = 6f
	}
}
