#include "macro-engine.h"

#include <atomic>
#include <mutex>
#include <thread>
#include <vector>

#include <errno.h>
#include <time.h>
#include <unistd.h>
#include <sys/resource.h>

struct macro_engine_t
{
	MacroEngineCallback cb = nullptr;
	void *user = nullptr;

	std::mutex api_mutex;            // serializes start/stop calls
	std::thread worker;
	std::atomic<bool> stop_req { false };
};

namespace
{
constexpr int64_t NS_PER_SEC = 1000000000LL;
constexpr int64_t MIN_HOLD_NS = 1000000LL;        // 1 ms
constexpr int64_t SPIN_NS = 300000LL;             // busy-wait for the last 0.3 ms => microsecond accuracy
constexpr int64_t SLEEP_CHUNK_NS = 2000000LL;     // sleep in <= 2 ms chunks so stop() reacts quickly
constexpr int64_t STALL_NS = 50000000LL;          // if we are >50 ms late (thread starved) don't burst to catch up

struct Step
{
	uint32_t buttons;
	int64_t hold_ns;
	int64_t gap_ns;
};

int64_t now_ns()
{
	timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (int64_t)ts.tv_sec * NS_PER_SEC + ts.tv_nsec;
}

void sleep_until_ns(int64_t target)
{
	timespec ts;
	ts.tv_sec = (time_t)(target / NS_PER_SEC);
	ts.tv_nsec = (long)(target % NS_PER_SEC);
	while(clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, nullptr) == EINTR)
		;
}

// Waits (absolute deadline `next`) - returns false if stop was requested meanwhile
bool wait_until(MacroEngine *e, int64_t &next)
{
	int64_t t = now_ns();
	if(next < t - STALL_NS)
		next = t;

	// coarse, interruptible sleep until shortly before the deadline
	while(!e->stop_req.load(std::memory_order_acquire))
	{
		int64_t remaining = next - SPIN_NS - now_ns();
		if(remaining <= 0)
			break;
		sleep_until_ns(now_ns() + (remaining < SLEEP_CHUNK_NS ? remaining : SLEEP_CHUNK_NS));
	}
	// precise busy-wait for the remainder
	while(now_ns() < next)
	{
		if(e->stop_req.load(std::memory_order_acquire))
			return false;
	}
	return !e->stop_req.load(std::memory_order_acquire);
}

void worker_main(MacroEngine *e, std::vector<Step> steps, bool loop)
{
	// Try to get scheduled quickly; this simply fails (ignored) when not permitted.
	setpriority(PRIO_PROCESS, 0, -16);

	int64_t next = now_ns();
	size_t i = 0;
	bool completed = false;

	while(!e->stop_req.load(std::memory_order_acquire))
	{
		const Step &s = steps[i];

		e->cb(s.buttons, e->user);                 // press all buttons of the step at the same instant
		next += s.hold_ns;
		if(!wait_until(e, next))
			break;

		if(s.gap_ns > 0)
		{
			e->cb(0u, e->user);                    // release
			next += s.gap_ns;
			if(!wait_until(e, next))
				break;
		}
		// gap == 0: the next step replaces this one directly (no release in between)

		if(++i >= steps.size())
		{
			if(!loop)
			{
				completed = true;
				break;
			}
			i = 0;
		}
	}

	if(completed)
		e->cb(0u, e->user);
}
}

extern "C" {

MacroEngine *macro_engine_new(MacroEngineCallback cb, void *user)
{
	MacroEngine *e = new MacroEngine();
	e->cb = cb;
	e->user = user;
	return e;
}

static void stop_locked(MacroEngine *e)
{
	if(!e->worker.joinable())
		return;
	e->stop_req.store(true, std::memory_order_release);
	e->worker.join();
	e->stop_req.store(false, std::memory_order_release);
	e->cb(0u, e->user); // make sure nothing stays pressed
}

void macro_engine_start(MacroEngine *e, const uint32_t *buttons, const int64_t *hold_ns, const int64_t *gap_ns, int count, int loop)
{
	if(!e)
		return;
	std::lock_guard<std::mutex> lock(e->api_mutex);
	stop_locked(e);
	if(!buttons || !hold_ns || !gap_ns || count <= 0)
		return;

	std::vector<Step> steps;
	steps.reserve((size_t)count);
	for(int i = 0; i < count; i++)
	{
		Step s;
		s.buttons = buttons[i];
		s.hold_ns = hold_ns[i] < MIN_HOLD_NS ? MIN_HOLD_NS : hold_ns[i];
		s.gap_ns = gap_ns[i] < 0 ? 0 : gap_ns[i];
		steps.push_back(s);
	}
	// a looping single step with no gap would be "pressed forever": keep a minimal release
	if(loop && steps.size() == 1 && steps[0].gap_ns < MIN_HOLD_NS)
		steps[0].gap_ns = MIN_HOLD_NS;

	e->stop_req.store(false, std::memory_order_release);
	e->worker = std::thread(worker_main, e, std::move(steps), loop != 0);
}

void macro_engine_stop(MacroEngine *e)
{
	if(!e)
		return;
	std::lock_guard<std::mutex> lock(e->api_mutex);
	stop_locked(e);
}

void macro_engine_free(MacroEngine *e)
{
	if(!e)
		return;
	macro_engine_stop(e);
	delete e;
}

}
