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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.plugins.Plugin;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;
import static org.mockito.Mockito.mock;

public class TroubleshooterSessionTest
{
	@Test
	public void testEmptySuspects()
	{
		TroubleshooterSession session = createSession(0);

		assertEquals(TroubleshooterState.NOT_FOUND, session.getState());
		assertTrue(session.isTerminal());
		assertEquals(0, session.getTotalSteps());
	}

	@Test
	public void testSinglePlugin()
	{
		TroubleshooterSession session = createSession(1);

		assertEquals(TroubleshooterState.RUNNING, session.getState());
		assertEquals(1, session.getTotalSteps());
		assertEquals(1, session.getEnabledHalf().size());
		assertEquals(0, session.getDisabledHalf().size());

		session.reportBad();
		assertEquals(TroubleshooterState.FOUND, session.getState());
		assertSame(session.getSuspects().get(0), session.getResult());
	}

	@Test
	public void testSinglePluginGood()
	{
		TroubleshooterSession session = createSession(1);

		session.reportGood();
		assertEquals(TroubleshooterState.NOT_FOUND, session.getState());
		assertTrue(session.isTerminal());
	}

	@Test
	public void testTwoPluginsBadFirst()
	{
		TroubleshooterSession session = createSession(2);
		Plugin expected = session.getSuspects().get(0);

		assertEquals(1, session.getEnabledHalf().size());
		assertEquals(1, session.getDisabledHalf().size());

		session.reportBad();
		assertEquals(TroubleshooterState.FOUND, session.getState());
		assertSame(expected, session.getResult());
	}

	@Test
	public void testTwoPluginsGoodFirst()
	{
		TroubleshooterSession session = createSession(2);
		Plugin expected = session.getSuspects().get(1);

		session.reportGood();
		assertEquals(TroubleshooterState.FOUND, session.getState());
		assertSame(expected, session.getResult());
	}

	@Test
	public void testFourPluginsFindsThird()
	{
		TroubleshooterSession session = createSession(4);
		Plugin expected = session.getSuspects().get(2);

		assertEquals(2, session.getEnabledHalf().size());
		assertEquals(2, session.getDisabledHalf().size());
		session.reportGood();

		assertEquals(TroubleshooterState.RUNNING, session.getState());
		assertEquals(2, session.getLow());
		assertEquals(3, session.getHigh());

		assertEquals(1, session.getEnabledHalf().size());
		assertEquals(1, session.getDisabledHalf().size());
		session.reportBad();

		assertEquals(TroubleshooterState.FOUND, session.getState());
		assertSame(expected, session.getResult());
	}

	@Test
	public void testEightPluginsFindsCorrect()
	{
		TroubleshooterSession session = createSession(8);
		Plugin expected = session.getSuspects().get(5);

		session.reportGood();
		assertEquals(4, session.getLow());
		assertEquals(7, session.getHigh());

		session.reportBad();
		assertEquals(4, session.getLow());
		assertEquals(5, session.getHigh());

		session.reportGood();

		assertEquals(TroubleshooterState.FOUND, session.getState());
		assertSame(expected, session.getResult());
		assertEquals(4, session.getStep());
	}

	@Test
	public void testNonPowerOfTwo()
	{
		TroubleshooterSession session = createSession(5);
		Plugin expected = session.getSuspects().get(3);

		assertEquals(3, session.getEnabledHalf().size());
		assertEquals(2, session.getDisabledHalf().size());
		session.reportGood();

		session.reportBad();

		assertEquals(TroubleshooterState.FOUND, session.getState());
		assertSame(expected, session.getResult());
	}

	@Test
	public void testCancel()
	{
		TroubleshooterSession session = createSession(8);

		session.reportBad();
		assertFalse(session.isTerminal());

		session.cancel();
		assertEquals(TroubleshooterState.CANCELLED, session.getState());
		assertTrue(session.isTerminal());
	}

	@Test(expected = IllegalStateException.class)
	public void testReportAfterCancel()
	{
		TroubleshooterSession session = createSession(4);
		session.cancel();
		session.reportBad();
	}

	@Test(expected = IllegalStateException.class)
	public void testGetResultBeforeFound()
	{
		TroubleshooterSession session = createSession(4);
		session.getResult();
	}

	@Test
	public void testComputeTotalSteps()
	{
		assertEquals(0, TroubleshooterSession.computeTotalSteps(0));
		assertEquals(1, TroubleshooterSession.computeTotalSteps(1));
		assertEquals(2, TroubleshooterSession.computeTotalSteps(2));
		assertEquals(3, TroubleshooterSession.computeTotalSteps(3));
		assertEquals(3, TroubleshooterSession.computeTotalSteps(4));
		assertEquals(4, TroubleshooterSession.computeTotalSteps(5));
		assertEquals(4, TroubleshooterSession.computeTotalSteps(8));
		assertEquals(5, TroubleshooterSession.computeTotalSteps(9));
		assertEquals(5, TroubleshooterSession.computeTotalSteps(16));
		assertEquals(7, TroubleshooterSession.computeTotalSteps(64));
		assertEquals(8, TroubleshooterSession.computeTotalSteps(128));
	}

	@Test
	public void testOriginalStatesPreserved()
	{
		List<Plugin> suspects = createMockPlugins(3);
		Map<Plugin, Boolean> originals = new HashMap<>();
		originals.put(suspects.get(0), true);
		originals.put(suspects.get(1), false);
		originals.put(suspects.get(2), true);

		TroubleshooterSession session = new TroubleshooterSession(suspects, originals);

		assertEquals(3, session.getOriginalStates().size());
		assertTrue(session.getOriginalStates().get(suspects.get(0)));
		assertFalse(session.getOriginalStates().get(suspects.get(1)));
	}

	@Test
	public void testSuspectsUnmodifiable()
	{
		TroubleshooterSession session = createSession(3);

		try
		{
			session.getSuspects().add(mock(Plugin.class));
			fail("Expected UnsupportedOperationException");
		}
		catch (UnsupportedOperationException e)
		{
			// expected
		}
	}

	@Test
	public void testFullBisectAllBad()
	{
		TroubleshooterSession session = createSession(8);
		Plugin expected = session.getSuspects().get(0);

		while (session.getState() == TroubleshooterState.RUNNING)
		{
			session.reportBad();
		}

		assertEquals(TroubleshooterState.FOUND, session.getState());
		assertSame(expected, session.getResult());
	}

	@Test
	public void testFullBisectAllGoodExceptLast()
	{
		TroubleshooterSession session = createSession(8);
		Plugin expected = session.getSuspects().get(7);

		while (session.getState() == TroubleshooterState.RUNNING)
		{
			session.reportGood();
		}

		assertEquals(TroubleshooterState.FOUND, session.getState());
		assertSame(expected, session.getResult());
	}

	// ---- Helpers ----

	private static TroubleshooterSession createSession(int suspectCount)
	{
		List<Plugin> suspects = createMockPlugins(suspectCount);
		Map<Plugin, Boolean> originalStates = new HashMap<>();
		for (Plugin p : suspects)
		{
			originalStates.put(p, true);
		}
		return new TroubleshooterSession(suspects, originalStates);
	}

	private static List<Plugin> createMockPlugins(int count)
	{
		List<Plugin> plugins = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			plugins.add(mock(Plugin.class));
		}
		return plugins;
	}
}

