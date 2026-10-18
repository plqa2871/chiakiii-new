package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.view.View
import org.json.JSONObject

/**
 * Per-element layout.
 * cx/cy: where the CENTER of the element is, as a fraction (0..1) of the screen. NaN = default position from the XML.
 * Storing the absolute center (instead of an offset from the default position) keeps buttons exactly where they were
 * dropped, whatever the default layout position is.
 */
data class ElementLayout(
	var cx: Float = Float.NaN,
	var cy: Float = Float.NaN,
	var scale: Float = 1f,
	var alpha: Float = 1f
)

class TouchLayoutStore(context: Context)
{
	private val prefs = context.getSharedPreferences("touch_layout_v2", Context.MODE_PRIVATE)
	private val items = HashMap<String, ElementLayout>()

	init { load() }

	private fun load()
	{
		val s = prefs.getString("layout", null) ?: return
		try
		{
			val o = JSONObject(s)
			for(k in o.keys())
			{
				val e = o.getJSONObject(k)
				items[k] = ElementLayout(
					if(e.has("cx")) e.getDouble("cx").toFloat() else Float.NaN,
					if(e.has("cy")) e.getDouble("cy").toFloat() else Float.NaN,
					e.optDouble("scale", 1.0).toFloat(),
					e.optDouble("alpha", 1.0).toFloat()
				)
			}
		}
		catch(_: Exception) {}
	}

	fun save()
	{
		val o = JSONObject()
		for((k, v) in items)
		{
			val e = JSONObject().put("scale", v.scale.toDouble()).put("alpha", v.alpha.toDouble())
			if(!v.cx.isNaN() && !v.cy.isNaN())
				e.put("cx", v.cx.toDouble()).put("cy", v.cy.toDouble())
			o.put(k, e)
		}
		prefs.edit().putString("layout", o.toString()).apply()
	}

	fun has(id: String) = items.containsKey(id)
	operator fun get(id: String): ElementLayout = items.getOrPut(id) { ElementLayout() }
	fun reset(id: String) { items[id] = ElementLayout(); save() }
	fun remove(id: String) { items.remove(id); save() }

	fun apply(id: String, view: View, parent: View, minAlpha: Float = 0f)
	{
		val l = this[id]
		if(l.cx.isNaN() || l.cy.isNaN())
		{
			view.translationX = 0f
			view.translationY = 0f
		}
		else if(parent.width > 0 && parent.height > 0 && view.width > 0 && view.height > 0)
		{
			view.translationX = l.cx * parent.width - (view.left + view.width / 2f)
			view.translationY = l.cy * parent.height - (view.top + view.height / 2f)
		}
		view.scaleX = l.scale
		view.scaleY = l.scale
		view.alpha = maxOf(l.alpha, minAlpha)
	}
}
