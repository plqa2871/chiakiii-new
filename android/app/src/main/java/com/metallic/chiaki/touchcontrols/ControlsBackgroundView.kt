// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

class ControlsBackgroundView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val pointers = HashMap<Int, Set<ButtonView>>()

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val shade = Color.argb(120, 5, 10, 14)
		fadePaint.shader = LinearGradient(0f, 0f, 0f, height * 0.22f,
			intArrayOf(shade, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, width.toFloat(), height * 0.22f, fadePaint)
		fadePaint.shader = LinearGradient(0f, height * 0.56f, 0f, height.toFloat(),
			intArrayOf(Color.TRANSPARENT, shade), null, Shader.TileMode.CLAMP)
		canvas.drawRect(0f, height * 0.56f, width.toFloat(), height.toFloat(), fadePaint)
		fadePaint.shader = null
	}

	private fun updatePointer(index: Int, event: MotionEvent)
	{
		val root = parent as? ViewGroup ?: return
		val id = event.getPointerId(index)
		val now = buttonsAtPoint(root, event.getX(index) + left, event.getY(index) + top)
		val previous = pointers[id] ?: emptySet()
		for(button in previous)
			if(button !in now)
				button.removePresser(id)
		for(button in now)
			if(button !in previous)
				button.addExternalPresser(id)
		if(now.isEmpty())
			pointers.remove(id)
		else
			pointers[id] = now
	}

	private fun releasePointers()
	{
		for((id, buttons) in pointers)
			buttons.forEach { it.removePresser(id) }
		pointers.clear()
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> updatePointer(event.actionIndex, event)
			MotionEvent.ACTION_MOVE ->
				for(i in 0 until event.pointerCount)
					if(event.getPointerId(i) in pointers)
						updatePointer(i, event)
			MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
				pointers.remove(event.getPointerId(event.actionIndex))?.forEach { it.removePresser(event.getPointerId(event.actionIndex)) }
			MotionEvent.ACTION_CANCEL -> releasePointers()
		}
		return true
	}

	override fun onDetachedFromWindow()
	{
		releasePointers()
		super.onDetachedFromWindow()
	}
}