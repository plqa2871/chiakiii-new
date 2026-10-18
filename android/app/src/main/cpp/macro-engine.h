// Native macro engine: runs a sequence of steps (press a set of buttons TOGETHER for hold_ns, release, wait gap_ns,
// next step ...) optionally looping, on a dedicated thread with absolute nanosecond timing.

#ifndef CHIAKI_MACRO_ENGINE_H
#define CHIAKI_MACRO_ENGINE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct macro_engine_t MacroEngine;

/** Called from the engine thread. buttons = button mask to inject (0 = release everything). */
typedef void (*MacroEngineCallback)(uint32_t buttons, void *user);

MacroEngine *macro_engine_new(MacroEngineCallback cb, void *user);

/**
 * Starts (or restarts) a macro with `count` steps. For step i: press buttons[i] for hold_ns[i], then release and wait
 * gap_ns[i] (gap 0 = go straight to the next step without releasing). If loop != 0 the sequence repeats until stopped.
 * hold is clamped to >= 1 ms. Arrays are copied.
 */
void macro_engine_start(MacroEngine *engine, const uint32_t *buttons, const int64_t *hold_ns, const int64_t *gap_ns, int count, int loop);

/** Stops the macro, waits for the thread and releases the injected buttons. */
void macro_engine_stop(MacroEngine *engine);

void macro_engine_free(MacroEngine *engine);

#ifdef __cplusplus
}
#endif

#endif
