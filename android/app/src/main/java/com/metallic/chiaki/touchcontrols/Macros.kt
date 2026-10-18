package com.metallic.chiaki.touchcontrols

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.metallic.chiaki.lib.ControllerState
import org.json.JSONArray
import org.json.JSONObject

/**
 * One step of a macro: press [buttons] TOGETHER (same instant) for [holdMs], release, wait [gapMs],
 * then continue with the next step. gapMs = 0 -> the next step follows directly without a release.
 * 16.7 ms = 1 frame at 60 fps.
 */
data class MacroStep(var buttons: UInt, var holdMs: Float = 16.7f, var gapMs: Float = 16.7f)

/**
 * A macro is a list of steps.
 *  - loop = true : the sequence repeats (toggle: tap to start/stop, or hold mode: active while held)
 *  - loop = false: the sequence runs once per tap (e.g. X, then 50 ms later Triangle)
 */
data class Macro(
	val id: String,
	var name: String,
	var steps: MutableList<MacroStep>,
	var loop: Boolean = true,
	var toggle: Boolean = true
)

private const val MIN_MACRO_HOLD_MS = 8f

object MacroButtons
{
	val all = listOf(
		"✕ Cross" to ControllerState.BUTTON_CROSS,
		"○ Circle" to ControllerState.BUTTON_MOON,
		"□ Square" to ControllerState.BUTTON_BOX,
		"△ Triangle" to ControllerState.BUTTON_PYRAMID,
		"L1" to ControllerState.BUTTON_L1,
		"R1" to ControllerState.BUTTON_R1,
		"L3" to ControllerState.BUTTON_L3,
		"R3" to ControllerState.BUTTON_R3,
		"D-Pad Up" to ControllerState.BUTTON_DPAD_UP,
		"D-Pad Down" to ControllerState.BUTTON_DPAD_DOWN,
		"D-Pad Left" to ControllerState.BUTTON_DPAD_LEFT,
		"D-Pad Right" to ControllerState.BUTTON_DPAD_RIGHT,
		"Options" to ControllerState.BUTTON_OPTIONS,
		"Share" to ControllerState.BUTTON_SHARE
	)

	fun describe(mask: UInt): String
	{
		val names = all.filter { (mask and it.second) != 0U }.map { it.first.substringBefore(' ') }
		return if(names.isEmpty()) "(none)" else names.joinToString(" + ")
	}
}

class MacroStore(context: Context)
{
	private val prefs = context.getSharedPreferences("macros", Context.MODE_PRIVATE)
	val macros = mutableListOf<Macro>()

	init
	{
		try
		{
			val a = JSONArray(prefs.getString("list", "[]"))
			for(i in 0 until a.length())
			{
				val o = a.getJSONObject(i)
				val steps = mutableListOf<MacroStep>()
				val sa = o.optJSONArray("steps")
				if(sa != null)
				{
					for(j in 0 until sa.length())
					{
						val so = sa.getJSONObject(j)
						steps.add(MacroStep(so.getLong("b").toUInt(), so.optDouble("hold", 16.7).toFloat(), so.optDouble("gap", 16.7).toFloat()))
					}
				}
				else // macros saved by older versions: one step, looping
					steps.add(MacroStep(o.getLong("buttons").toUInt(), o.optDouble("press", 16.7).toFloat(), o.optDouble("release", 16.7).toFloat()))
				if(steps.isNotEmpty())
					macros.add(Macro(o.getString("id"), o.getString("name"), steps, o.optBoolean("loop", true), o.optBoolean("toggle", true)))
			}
		}
		catch(_: Exception) {}
	}

	fun save()
	{
		val a = JSONArray()
		for(m in macros)
		{
			val sa = JSONArray()
			for(s in m.steps)
				sa.put(JSONObject().put("b", s.buttons.toLong()).put("hold", s.holdMs.toDouble()).put("gap", s.gapMs.toDouble()))
			a.put(JSONObject().put("id", m.id).put("name", m.name).put("loop", m.loop).put("toggle", m.toggle).put("steps", sa))
		}
		prefs.edit().putString("list", a.toString()).apply()
	}
}

/** Where macros are executed: implemented by the stream screen, forwards to the native C++ engine. */
interface MacroBackend
{
	fun start(buttons: IntArray, holdNs: LongArray, gapNs: LongArray, loop: Boolean)
	fun stop()
}

/**
 * Drives one macro at a time. The timing loop runs in C++ (macro-engine.cpp): its own thread,
 * absolute CLOCK_MONOTONIC deadlines in nanoseconds, so it is not affected by UI-thread jitter.
 */
class MacroRunner(private val backend: () -> MacroBackend?, private val onFinished: () -> Unit)
{
	private val handler = Handler(Looper.getMainLooper())
	private val finishCheck = Runnable {
		current = null
		onFinished()
	}

	var current: Macro? = null
		private set

	val running get() = current != null

	fun start(m: Macro)
	{
		val b = backend() ?: return
		handler.removeCallbacks(finishCheck)
		val n = m.steps.size
		b.start(
			IntArray(n) { m.steps[it].buttons.toInt() },
			LongArray(n) { (m.steps[it].holdMs.coerceAtLeast(MIN_MACRO_HOLD_MS) * 1_000_000.0).toLong() },
			LongArray(n) { (m.steps[it].gapMs * 1_000_000.0).toLong() },
			m.loop
		)
		current = m
		if(!m.loop) // one-shot: UI goes back to idle when the sequence is over
		{
			val totalMs = m.steps.sumOf { it.holdMs.coerceAtLeast(MIN_MACRO_HOLD_MS).toDouble() } +
				m.steps.dropLast(1).sumOf { it.gapMs.toDouble() }
			handler.postDelayed(finishCheck, totalMs.toLong() + 60)
		}
	}

	fun stop()
	{
		handler.removeCallbacks(finishCheck)
		backend()?.stop()
		current = null
	}
}

/** Creates floating macro buttons on the touch-control overlay and the dialogs to create/edit macros. */
class MacroUi(
	private val activity: Activity,
	private val root: ViewGroup,
	private val editor: TouchLayoutEditor,
	private val runner: MacroRunner
)
{
	private val macroStore = MacroStore(activity)
	private val views = HashMap<String, TextView>()
	private val dp = activity.resources.displayMetrics.density

	fun showAll() { macroStore.macros.forEach { addButton(it) } }

	fun createNew() = openDialog(null)

	/** Red = this macro is running. Call after the runner was stopped from outside (pause, edit mode...). */
	fun refreshStates()
	{
		for(m in macroStore.macros)
			views[m.id]?.background = bg(runner.current?.id == m.id)
	}

	private fun layoutId(m: Macro) = "macro:${m.id}"

	private fun addButton(m: Macro)
	{
		val size = (64 * dp).toInt()
		val tv = TextView(activity).apply {
			text = m.name
			gravity = Gravity.CENTER
			setTextColor(Color.WHITE)
			textSize = 12f
			background = bg(false)
		}
		val lp = if(root is androidx.constraintlayout.widget.ConstraintLayout)
			androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(size, size).apply { startToStart = 0; topToTop = 0 }
		else FrameLayout.LayoutParams(size, size)
		root.addView(tv, lp)
		views[m.id] = tv

		val id = layoutId(m)
		if(!editor.store.has(id))   // default position of a new macro button (center, as fraction of the screen)
		{
			val l = editor.store[id]
			l.cx = 0.12f
			l.cy = (0.3f + 0.17f * macroStore.macros.indexOf(m)).coerceAtMost(0.9f)
			l.alpha = 0.7f
		}

		val normal = View.OnTouchListener { v, e ->
			if(e.actionMasked == MotionEvent.ACTION_DOWN)
			{
				// the visible button is a circle: ignore touches in the corners of its square bounds
				val r = v.width / 2f
				val dx = e.x - r
				val dy = e.y - v.height / 2f
				if(dx * dx + dy * dy > r * r)
					return@OnTouchListener false
			}
			when(e.actionMasked)
			{
				MotionEvent.ACTION_DOWN ->
				{
					if(m.loop && m.toggle)
					{
						if(runner.current === m) runner.stop() else runner.start(m)
					}
					else runner.start(m)   // hold mode / one-shot sequence
					refreshStates()
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
				{
					if(m.loop && !m.toggle) runner.stop()
					refreshStates()
				}
			}
			true
		}
		editor.register(id, tv, normal, "Edit macro") { openDialog(m) }
	}

	private fun bg(active: Boolean) = GradientDrawable().apply {
		shape = GradientDrawable.OVAL
		setColor(if(active) Color.argb(220, 220, 60, 60) else Color.argb(200, 40, 40, 40))
		setStroke((2 * dp).toInt(), Color.WHITE)
	}

	private fun numberField(value: Float, allowZero: Boolean) = EditText(activity).apply {
		inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
		setText(value.toString())
		setSelectAllOnFocus(true)
		tag = allowZero
	}

	private fun labeled(label: String, field: View) = LinearLayout(activity).apply {
		orientation = LinearLayout.VERTICAL
		addView(TextView(activity).apply { text = label; textSize = 12f })
		addView(field)
	}

	private fun pickButtons(step: MacroStep, onDone: () -> Unit)
	{
		val labels = MacroButtons.all.map { it.first }.toTypedArray()
		val checked = BooleanArray(labels.size) { (step.buttons and MacroButtons.all[it].second) != 0U }
		AlertDialog.Builder(activity)
			.setTitle("Pressed together")
			.setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
			.setPositiveButton("OK") { _, _ ->
				var mask = 0U
				for(i in checked.indices)
					if(checked[i]) mask = mask or MacroButtons.all[i].second
				step.buttons = mask
				onDone()
			}
			.setNegativeButton("Cancel", null)
			.show()
	}

	private fun openDialog(existing: Macro?)
	{
		val pad = (16 * dp).toInt()
		val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
		val scroll = ScrollView(activity).apply { addView(box) }

		val name = EditText(activity).apply { hint = "Name"; setText(existing?.name ?: "M${macroStore.macros.size + 1}") }
		box.addView(name)

		box.addView(TextView(activity).apply {
			text = "Each step presses its buttons at the same instant for \"Hold\", releases, waits \"Then wait\", then the next step starts. Hold minimum: 8 ms. 16.7 ms = 1 frame @ 60 fps. \"Then wait\" = 0: go straight to the next step."
			textSize = 12f
			setPadding(0, pad / 2, 0, pad / 2)
		})

		val steps: MutableList<MacroStep> = existing?.steps?.map { it.copy() }?.toMutableList()
			?: mutableListOf(MacroStep(ControllerState.BUTTON_CROSS))
		val stepsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
		val fields = ArrayList<Pair<EditText, EditText>>()
		box.addView(stepsBox)

		fun syncFromFields()
		{
			for(i in fields.indices)
			{
				if(i >= steps.size) break
				steps[i].holdMs = fields[i].first.text.toString().toFloatOrNull()?.coerceAtLeast(MIN_MACRO_HOLD_MS) ?: steps[i].holdMs
				steps[i].gapMs = fields[i].second.text.toString().toFloatOrNull()?.coerceAtLeast(0f) ?: steps[i].gapMs
			}
		}

		lateinit var rebuild: () -> Unit
		rebuild = {
			stepsBox.removeAllViews()
			fields.clear()
			steps.forEachIndexed { index, step ->
				val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad / 2, 0, pad / 2) }
				card.addView(TextView(activity).apply { text = "Step ${index + 1}"; setTypeface(typeface, Typeface.BOLD) })
				card.addView(Button(activity).apply {
					text = "Buttons: " + MacroButtons.describe(step.buttons)
					setOnClickListener { syncFromFields(); pickButtons(step) { rebuild() } }
				})
				val hold = numberField(step.holdMs, false)
				val gap = numberField(step.gapMs, true)
				val times = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
				times.addView(labeled("Hold (ms)", hold), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
				times.addView(labeled("Then wait (ms)", gap), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
				card.addView(times)
				if(steps.size > 1)
					card.addView(Button(activity).apply {
						text = "Remove step ${index + 1}"
						setOnClickListener { syncFromFields(); steps.removeAt(index); rebuild() }
					})
				stepsBox.addView(card)
				fields.add(hold to gap)
			}
		}
		rebuild()

		box.addView(Button(activity).apply {
			text = "+ Add step"
			setOnClickListener {
				syncFromFields()
				steps.add(MacroStep(ControllerState.BUTTON_CROSS))
				rebuild()
			}
		})

		val loop = CheckBox(activity).apply { text = "Repeat the sequence (loop). Off = runs once per tap"; isChecked = existing?.loop ?: true }
		val toggle = CheckBox(activity).apply { text = "Loop mode: tap to start/stop. Off = active while held"; isChecked = existing?.toggle ?: true }
		box.addView(loop)
		box.addView(toggle)

		val b = AlertDialog.Builder(activity)
			.setTitle(if(existing == null) "New macro" else "Edit macro")
			.setView(scroll)
			.setPositiveButton("Save") { _, _ ->
				syncFromFields()
				if(steps.isEmpty() || steps.any { it.buttons == 0U })
				{
					Toast.makeText(activity, "Every step needs at least one button", Toast.LENGTH_LONG).show()
					return@setPositiveButton
				}
				if(existing == null)
				{
					val m = Macro(System.currentTimeMillis().toString(), name.text.toString(), steps, loop.isChecked, toggle.isChecked)
					macroStore.macros.add(m)
					addButton(m)
				}
				else
				{
					if(runner.current === existing) runner.stop()
					existing.name = name.text.toString()
					existing.steps = steps
					existing.loop = loop.isChecked
					existing.toggle = toggle.isChecked
					views[existing.id]?.text = existing.name
				}
				macroStore.save()
				editor.applyAll()
				refreshStates()
			}
			.setNegativeButton("Cancel", null)
		if(existing != null)
			b.setNeutralButton("Delete") { _, _ ->
				runner.stop()
				views.remove(existing.id)?.let { root.removeView(it) }
				editor.unregister(layoutId(existing))
				editor.store.remove(layoutId(existing))
				macroStore.macros.remove(existing)
				macroStore.save()
				refreshStates()
			}
		b.show()
	}
}
