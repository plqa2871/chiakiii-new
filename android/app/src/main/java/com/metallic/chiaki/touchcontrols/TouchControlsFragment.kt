// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.metallic.chiaki.databinding.FragmentControlsBinding
import com.metallic.chiaki.lib.ControllerState
import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables.combineLatest
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject

abstract class TouchControlsFragment : Fragment()
{
	/** Set by the stream screen: executes macros in the native (C++) engine. */
	var macroBackend: MacroBackend? = null

	protected var ownControllerState = ControllerState()
		set(value)
		{
			val diff = field != value
			field = value
			if(diff)
				ownControllerStateSubject.onNext(ownControllerState)
		}

	protected val ownControllerStateSubject: Subject<ControllerState>
			= BehaviorSubject.create<ControllerState>().also { it.onNext(ownControllerState) }

	// to delay attaching to the touchpadView until it's available
	protected val controllerStateProxy: Subject<Observable<ControllerState>>
			= BehaviorSubject.create<Observable<ControllerState>>().also { it.onNext(ownControllerStateSubject) }
	val controllerState: Observable<ControllerState> get() =
		controllerStateProxy.flatMap { it }

	var onScreenControlsEnabled: LiveData<Boolean>? = null
}

class DefaultTouchControlsFragment : TouchControlsFragment()
{
	private var _binding: FragmentControlsBinding? = null
	private val binding get() = _binding!!

	// --- mod: layout editor + macros ---
	private var layoutEditor: TouchLayoutEditor? = null
	private var macroRunner: MacroRunner? = null
	private var macroUi: MacroUi? = null

	val isEditing: Boolean get() = layoutEditor?.isEditing == true

	fun toggleEditMode()
	{
		val editor = layoutEditor ?: return
		if(!editor.editMode)
		{
			macroRunner?.stop()
			macroUi?.refreshStates()
		}
		editor.setEditMode(!editor.editMode)
	}

	fun exitEditMode()
	{
		layoutEditor?.setEditMode(false)
	}

	fun addMacro()
	{
		val editor = layoutEditor ?: return
		if(!editor.editMode)
			editor.setEditMode(true)
		macroUi?.createNew()
	}

	private fun setupModifications(root: ViewGroup)
	{
		val activity = requireActivity()
		val editor = TouchLayoutEditor(activity, root, TouchLayoutStore(activity))
		layoutEditor = editor

		editor.register("dpad", binding.dpadView)
		editor.register("cross", binding.crossButtonView)
		editor.register("circle", binding.moonButtonView)
		editor.register("triangle", binding.pyramidButtonView)
		editor.register("square", binding.boxButtonView)
		editor.register("l1", binding.l1ButtonView)
		editor.register("r1", binding.r1ButtonView)
		editor.register("l2", binding.l2ButtonView)
		editor.register("r2", binding.r2ButtonView)
		editor.register("l3", binding.l3ButtonView)
		editor.register("r3", binding.r3ButtonView)
		editor.register("options", binding.optionsButtonView)
		editor.register("share", binding.shareButtonView)
		editor.register("ps", binding.psButtonView)
		editor.register("touchpad", binding.touchpadView)
		editor.register("left_stick", binding.leftAnalogStickView)
		editor.register("right_stick", binding.rightAnalogStickView)

		val runner = MacroRunner({ macroBackend }, { macroUi?.refreshStates() })
		macroRunner = runner
		macroUi = MacroUi(activity, root, editor, runner).also { it.showAll() }
	}

	override fun onPause()
	{
		releaseControls()
		super.onPause()
	}

	fun releaseControls()
	{
		macroRunner?.stop()
		macroUi?.refreshStates()
		view?.let { root ->
			val now = SystemClock.uptimeMillis()
			val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
			root.dispatchTouchEvent(cancel)
			cancel.recycle()
		}
		ownControllerState = ControllerState()
	}

	override fun onDestroyView()
	{
		macroRunner?.stop()
		layoutEditor = null
		macroUi = null
		macroRunner = null
		super.onDestroyView()
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		FragmentControlsBinding.inflate(inflater, container, false).let {
			_binding = it
			controllerStateProxy.onNext(
				combineLatest(ownControllerStateSubject, binding.touchpadView.controllerState) { a, b -> a or b }
			)
			it.root
		}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?)
	{
		super.onViewCreated(view, savedInstanceState)
		binding.dpadView.stateChangeCallback = this::dpadStateChanged
		binding.crossButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_CROSS)
		binding.moonButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_MOON)
		binding.pyramidButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PYRAMID)
		binding.boxButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_BOX)
		binding.l1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L1)
		binding.r1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R1)
		binding.l3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L3)
		binding.r3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R3)
		binding.optionsButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_OPTIONS)
		binding.shareButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_SHARE)
		binding.psButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PS)

		binding.l2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { l2State = if(it) 255U else 0U } }
		binding.r2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { r2State = if(it) 255U else 0U } }

		val quantizeStick = { f: Float ->
			(Short.MAX_VALUE * f).toInt().toShort()
		}

		binding.leftAnalogStickView.stateChangedCallback = { ownControllerState = ownControllerState.copy().apply {
			leftX = quantizeStick(it.x)
			leftY = quantizeStick(it.y)
		}}

		binding.rightAnalogStickView.stateChangedCallback = { ownControllerState = ownControllerState.copy().apply {
			rightX = quantizeStick(it.x)
			rightY = quantizeStick(it.y)
		}}

		setupModifications(view as ViewGroup)

		onScreenControlsEnabled?.observe(viewLifecycleOwner, Observer {
			view.visibility = if(it) View.VISIBLE else View.GONE
			if(!it)
			{
				macroRunner?.stop()
				macroUi?.refreshStates()
				layoutEditor?.setEditMode(false)
			}
		})
	}

	private fun dpadStateChanged(direction: DPadView.Direction?)
	{
		ownControllerState = ownControllerState.copy().apply {
			buttons = ((buttons
						and ControllerState.BUTTON_DPAD_LEFT.inv()
						and ControllerState.BUTTON_DPAD_RIGHT.inv()
						and ControllerState.BUTTON_DPAD_UP.inv()
						and ControllerState.BUTTON_DPAD_DOWN.inv())
					or when(direction)
					{
						DPadView.Direction.UP -> ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.DOWN -> ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.LEFT -> ControllerState.BUTTON_DPAD_LEFT
						DPadView.Direction.RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
						DPadView.Direction.LEFT_UP -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.LEFT_DOWN -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.RIGHT_UP -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.RIGHT_DOWN -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_DOWN
						null -> 0U
					})
		}
	}

	private fun buttonStateChanged(buttonMask: UInt) = { pressed: Boolean ->
		ownControllerState = ownControllerState.copy().apply {
			buttons =
				if(pressed)
					buttons or buttonMask
				else
					buttons and buttonMask.inv()

		}
	}
}