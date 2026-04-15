/*
 * Copyright (c) 2026, swirle13
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.github.swirle13.plugintroubleshooter;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import net.runelite.client.plugins.Plugin;

/**
 * Encapsulates the mutable state for one troubleshooter run.
 * <p>
 * The session performs a binary search over the suspect plugin list.
 * At each step, the <em>first half</em> of the current range is kept enabled
 * and the <em>second half</em> is disabled. The user then reports whether the
 * issue persists, which narrows the search range by half each iteration.
 */
class TroubleshooterSession
{
	/**
	 * The plugins under investigation, in a fixed order established at session start.
	 */
	@Getter
	private final List<Plugin> suspects;

	/**
	 * Snapshot of every plugin's enabled state at session start, for full restoration.
	 */
	@Getter
	private final Map<Plugin, Boolean> originalStates;

	/**
	 * Current inclusive lower bound of the binary search range.
	 */
	@Getter
	private int low;

	/**
	 * Current inclusive upper bound of the binary search range.
	 */
	@Getter
	private int high;

	/**
	 * Human-readable step counter (1-based).
	 */
	@Getter
	private int step;

	/**
	 * The maximum number of steps this session will require.
	 */
	@Getter
	private final int totalSteps;

	/**
	 * Current lifecycle state.
	 */
	@Getter
	private TroubleshooterState state;

	/**
	 * Creates a new troubleshooter session.
	 *
	 * @param suspects       the active, non-hidden plugins to bisect; must not be modified externally
	 * @param originalStates snapshot of every plugin's enabled state for later restoration
	 */
	TroubleshooterSession(List<Plugin> suspects, Map<Plugin, Boolean> originalStates)
	{
		this.suspects = Collections.unmodifiableList(suspects);
		this.originalStates = Collections.unmodifiableMap(originalStates);
		this.step = 1;
		this.low = 0;

		if (suspects.isEmpty())
		{
			this.high = 0;
			this.totalSteps = 0;
			this.state = TroubleshooterState.NOT_FOUND;
		}
		else if (suspects.size() == 1)
		{
			this.high = 0;
			this.totalSteps = 1;
			this.state = TroubleshooterState.RUNNING;
		}
		else
		{
			this.high = suspects.size() - 1;
			this.totalSteps = computeTotalSteps(suspects.size());
			this.state = TroubleshooterState.RUNNING;
		}
	}

	/**
	 * Returns the midpoint index used to partition the current search range.
	 */
	int getMid()
	{
		return low + (high - low) / 2;
	}

	/**
	 * Returns the plugins that should remain enabled for the current step.
	 * This is the first half of the current range: {@code [low, mid]}.
	 */
	List<Plugin> getEnabledHalf()
	{
		return suspects.subList(low, getMid() + 1);
	}

	/**
	 * Returns the plugins that should be disabled for the current step.
	 * This is the second half of the current range: {@code (mid, high]}.
	 */
	List<Plugin> getDisabledHalf()
	{
		return suspects.subList(getMid() + 1, high + 1);
	}

	/**
	 * The user reports the issue still occurs.
	 * The offending plugin is in the currently-enabled half.
	 */
	void reportBad()
	{
		assertRunning();
		high = getMid();
		advanceOrFinish();
	}

	/**
	 * The user reports the issue is gone.
	 * The offending plugin is in the currently-disabled half.
	 */
	void reportGood()
	{
		assertRunning();
		low = getMid() + 1;
		advanceOrFinish();
	}

	/**
	 * The user cancels the session.
	 */
	void cancel()
	{
		state = TroubleshooterState.CANCELLED;
	}

	/**
	 * Returns the plugin identified as the cause. Only valid when {@code state == FOUND}.
	 *
	 * @throws IllegalStateException if the session has not reached the FOUND state
	 */
	Plugin getResult()
	{
		if (state != TroubleshooterState.FOUND)
		{
			throw new IllegalStateException("No result available; state is " + state);
		}
		return suspects.get(low);
	}

	/**
	 * Whether the session has reached a terminal state.
	 */
	boolean isTerminal()
	{
		return state == TroubleshooterState.FOUND
			|| state == TroubleshooterState.NOT_FOUND
			|| state == TroubleshooterState.CANCELLED;
	}

	private void advanceOrFinish()
	{
		step++;

		if (low == high)
		{
			state = TroubleshooterState.FOUND;
		}
		else if (low > high)
		{
			state = TroubleshooterState.NOT_FOUND;
		}
	}

	private void assertRunning()
	{
		if (state != TroubleshooterState.RUNNING)
		{
			throw new IllegalStateException("Session is not running; state is " + state);
		}
	}

	/**
	 * Computes the maximum number of binary search steps for a given suspect count.
	 * This is {@code ceil(log2(n)) + 1}: the +1 accounts for the final confirmation step
	 * when the range narrows to a single element.
	 */
	static int computeTotalSteps(int suspectCount)
	{
		if (suspectCount <= 1)
		{
			return suspectCount;
		}
		// ceil(log2(n)) = 32 - numberOfLeadingZeros(n - 1) for n > 1
		return 32 - Integer.numberOfLeadingZeros(suspectCount - 1) + 1;
	}
}

