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

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * Sidebar panel for the Plugin Troubleshooter. Uses a {@link CardLayout} to
 * present a guided, wizard-style workflow that walks the user through a
 * binary-search bisect of their active plugins.
 *
 * <p>Card flow:
 * <pre>
 *   IDLE  -->  RUNNING  -->  FOUND
 *                  |            |
 *                  v            v
 *              TERMINAL      (finish)
 * </pre>
 */
@Slf4j
@Singleton
class PluginTroubleshooterPanel extends PluginPanel
{
	// -- Card identifiers --

	private static final String CARD_IDLE = "IDLE";
	private static final String CARD_RUNNING = "RUNNING";
	private static final String CARD_FOUND = "FOUND";
	private static final String CARD_TERMINAL = "TERMINAL";

	// -- Colour palette --

	private static final Color COLOR_BAD = new Color(0xBE2828);
	private static final Color COLOR_GOOD = new Color(0x1F621F);
	private static final Color COLOR_NEUTRAL = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final Color COLOR_PROGRESS_BG = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color COLOR_PROGRESS_FG = ColorScheme.BRAND_ORANGE;

	// -- Config keys for internal state --

	private static final String CONFIG_GROUP = "plugin-troubleshooter";
	private static final String CONFIG_KEY_SAVED_STATES = "savedPluginStates";

	private static final Gson GSON = new Gson();

	// -- Dependencies --

	private final PluginManager pluginManager;
	private final EventBus eventBus;
	private final ConfigManager configManager;
	private final PluginTroubleshooterConfig config;

	// -- Layout --

	private final CardLayout cardLayout;
	private final JPanel cardContainer;

	private JPanel idleCard;
	private JPanel runningCard;
	private JPanel foundCard;
	private JPanel terminalCard;

	// -- Session state --

	private TroubleshooterSession session;

	/**
	 * Guard flag to prevent re-entrant clicks while plugin operations
	 * are running on a background thread.
	 */
	private volatile boolean operationInProgress;

	@Inject
	PluginTroubleshooterPanel(PluginManager pluginManager, EventBus eventBus,
		ConfigManager configManager, PluginTroubleshooterConfig config)
	{
		super(false);

		this.pluginManager = pluginManager;
		this.eventBus = eventBus;
		this.configManager = configManager;
		this.config = config;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		cardLayout = new CardLayout();
		cardContainer = new JPanel(cardLayout);
		cardContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(cardContainer, BorderLayout.CENTER);

		buildIdleCard();
		showCard(CARD_IDLE);

		// Defer recovery so it doesn't run during Guice injection when
		// PluginManager may not be in a stable state (re-entrancy hazard).
		SwingUtilities.invokeLater(this::recoverFromInterruptedSession);
	}

	// -- Panel lifecycle --

	@Override
	public void onActivate()
	{
		if (session == null)
		{
			rebuildIdleCard();
			showCard(CARD_IDLE);
		}
	}

	/**
	 * Registers the panel with the EventBus. Called by the plugin's
	 * {@code startUp()} so that events are received regardless of
	 * whether the panel tab is currently visible.
	 */
	void registerEventBus()
	{
		eventBus.register(this);
	}

	/**
	 * Unregisters the panel from the EventBus. Called by the plugin's
	 * {@code shutDown()}.
	 */
	void unregisterEventBus()
	{
		eventBus.unregister(this);
	}

	/**
	 * Called by the plugin's {@code shutDown()} to ensure any in-progress
	 * session is cancelled and all plugin states are restored before the
	 * troubleshooter is torn down.
	 */
	void shutdown()
	{
		if (session != null)
		{
			session.cancel();
			restoreAll();
			session = null;
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		if (session != null)
		{
			cancelSession();
		}
	}

	// -- Session recovery failsafe --

	/**
	 * Checks if there was an incomplete troubleshooting session and restores plugin states.
	 * This handles the case where the application exited during troubleshooting.
	 */
	private void recoverFromInterruptedSession()
	{
		String savedStates = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_SAVED_STATES);
		if (savedStates == null || savedStates.isEmpty())
		{
			return;
		}

		log.info("Recovering from interrupted troubleshooting session");

		try
		{
			Map<String, Boolean> states = parsePluginStates(savedStates);
			restorePluginStatesFromMap(states);
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_SAVED_STATES);
			log.info("Successfully restored {} plugins", states.size());
		}
		catch (Exception e)
		{
			log.warn("Failed to recover plugin states", e);
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_SAVED_STATES);
		}
	}

	/**
	 * Saves the current plugin states to config for recovery in case of interruption.
	 */
	private void savePluginStatesToConfig(Map<Plugin, Boolean> states)
	{
		if (states.isEmpty())
		{
			return;
		}

		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_SAVED_STATES, encodePluginStates(states));
	}

	/**
	 * Clears any saved plugin states from config.
	 */
	private void clearSavedPluginStates()
	{
		configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_SAVED_STATES);
	}

	/**
	 * Encodes a map of plugin states into a JSON string using Gson.
	 */
	private String encodePluginStates(Map<Plugin, Boolean> states)
	{
		Map<String, Boolean> classNameStates = new LinkedHashMap<>();
		for (Map.Entry<Plugin, Boolean> entry : states.entrySet())
		{
			classNameStates.put(entry.getKey().getClass().getName(), entry.getValue());
		}
		return GSON.toJson(classNameStates);
	}

	/**
	 * Decodes a plugin states JSON string back into a map.
	 * Falls back to the legacy colon-delimited format for backward compatibility.
	 */
	private Map<String, Boolean> parsePluginStates(String encoded)
	{
		if (encoded.isEmpty())
		{
			return new HashMap<>();
		}

		try
		{
			Type type = new TypeToken<Map<String, Boolean>>(){}.getType();
			Map<String, Boolean> result = GSON.fromJson(encoded, type);
			return result != null ? result : new HashMap<>();
		}
		catch (Exception e)
		{
			// Fall back to legacy format: "ClassName:true,ClassName:false,..."
			log.debug("Parsing plugin states with legacy format", e);
			Map<String, Boolean> states = new HashMap<>();
			String[] pairs = encoded.split(",");
			for (String pair : pairs)
			{
				String[] parts = pair.split(":");
				if (parts.length == 2)
				{
					states.put(parts[0], Boolean.parseBoolean(parts[1]));
				}
			}
			return states;
		}
	}

	/**
	 * Restores plugin states from a map of class names to enabled state.
	 */
	private void restorePluginStatesFromMap(Map<String, Boolean> statesByClass)
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			String className = plugin.getClass().getName();
			if (statesByClass.containsKey(className))
			{
				boolean shouldBeEnabled = statesByClass.get(className);
				boolean currentlyEnabled = pluginManager.isPluginEnabled(plugin);

				if (shouldBeEnabled != currentlyEnabled)
				{
					setPluginState(plugin, shouldBeEnabled);
				}
			}
		}
	}

	// -- Session lifecycle --

	private void startSession()
	{
		if (session != null || operationInProgress)
		{
			return;
		}

		List<Plugin> candidates = collectCandidates();

		Map<Plugin, Boolean> snapshot = new LinkedHashMap<>();
		for (Plugin plugin : pluginManager.getPlugins())
		{
			snapshot.put(plugin, pluginManager.isPluginEnabled(plugin));
		}

		session = new TroubleshooterSession(candidates, snapshot);

		// Save plugin states for recovery in case of interruption
		savePluginStatesToConfig(snapshot);

		if (session.getState() == TroubleshooterState.NOT_FOUND)
		{
			session = null;
			clearSavedPluginStates();
			showTerminal("No plugins to troubleshoot",
				"There are no active, user-toggleable plugins to test.");
			return;
		}

		runPluginOpsInBackground(
			this::applySessionStep,
			() ->
			{
				buildRunningCard();
				showCard(CARD_RUNNING);
			});
	}

	private void advanceBad()
	{
		if (operationInProgress)
		{
			return;
		}
		session.reportBad();
		afterAdvance();
	}

	private void advanceGood()
	{
		if (operationInProgress)
		{
			return;
		}
		session.reportGood();
		afterAdvance();
	}

	private void afterAdvance()
	{
		switch (session.getState())
		{
			case FOUND:
				runPluginOpsInBackground(
					() -> restoreExcept(session.getResult()),
					() ->
					{
						clearSavedPluginStates();
						buildFoundCard();
						showCard(CARD_FOUND);
					});
				break;

			case NOT_FOUND:
				runPluginOpsInBackground(
					this::restoreAll,
					() ->
					{
						clearSavedPluginStates();
						session = null;
						showTerminal("Could not identify the problem",
							"<html>The troubleshooter could not narrow it down to a"
								+ " single plugin. This can happen when multiple plugins"
								+ " interact to cause the issue.<br><br>"
								+ "Try running the troubleshooter again, or ask for help"
								+ " on the RuneLite Discord.</html>");
					});
				break;

			default:
				runPluginOpsInBackground(
					this::applySessionStep,
					() ->
					{
						buildRunningCard();
						showCard(CARD_RUNNING);
					});
				break;
		}
	}

	private void cancelSession()
	{
		if (operationInProgress)
		{
			return;
		}

		if (session != null)
		{
			session.cancel();
			runPluginOpsInBackground(
				this::restoreAll,
				() ->
				{
					clearSavedPluginStates();
					session = null;
					rebuildIdleCard();
					showCard(CARD_IDLE);
				});
		}
		else
		{
			rebuildIdleCard();
			showCard(CARD_IDLE);
		}
	}

	private void finishKeepDisabled()
	{
		clearSavedPluginStates();
		session = null;
		rebuildIdleCard();
		showCard(CARD_IDLE);
	}

	private void finishReenableAll()
	{
		if (operationInProgress)
		{
			return;
		}

		runPluginOpsInBackground(
			this::restoreAll,
			() ->
			{
				clearSavedPluginStates();
				session = null;
				rebuildIdleCard();
				showCard(CARD_IDLE);
			});
	}

	// -- Candidate collection --

	/**
	 * Returns the set of plugin classes that other plugins declare as
	 * {@link PluginDependency} targets. Disabling these could cascade-break
	 * dependent plugins, so they are excluded from the bisect.
	 */
	private Set<Class<? extends Plugin>> getDependencyRoots()
	{
		return pluginManager.getPlugins().stream()
			.flatMap(p -> Arrays.stream(p.getClass().getAnnotationsByType(PluginDependency.class))
				.map(PluginDependency::value))
			.collect(Collectors.toSet());
	}

	/**
	 * Returns {@code true} if the given plugin should be considered a
	 * troubleshooting candidate.
	 * <p>
	 * Excludes this plugin itself, hidden core plugins, and plugins that
	 * serve as {@link PluginDependency} roots for other plugins.
	 *
	 * @param plugin          the plugin to evaluate
	 * @param dependencyRoots pre-computed set from {@link #getDependencyRoots()}
	 */
	private boolean isTroubleshootable(Plugin plugin, Set<Class<? extends Plugin>> dependencyRoots)
	{
		if (plugin instanceof PluginTroubleshooterPlugin)
		{
			return false;
		}

		PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
		if (descriptor == null || descriptor.hidden())
		{
			return false;
		}

		return !dependencyRoots.contains(plugin.getClass());
	}

	/**
	 * Collects the plugins eligible for troubleshooting.
	 */
	private List<Plugin> collectCandidates()
	{
		Set<Class<? extends Plugin>> dependencyRoots = getDependencyRoots();

		return pluginManager.getPlugins().stream()
			.filter(pluginManager::isPluginActive)
			.filter(p -> isTroubleshootable(p, dependencyRoots))
			.collect(Collectors.toList());
	}


	// -- Plugin state management --

	/**
	 * Applies plugin states for the current bisect step.
	 * <p>
	 * Proper bisect behaviour:
	 * <ul>
	 *   <li>Suspects in the enabled half {@code [low, mid]} — kept enabled</li>
	 *   <li>Suspects in the disabled half {@code (mid, high]} — disabled for testing</li>
	 *   <li>Suspects outside the current range — already cleared, re-enabled
	 *       (restored to original state)</li>
	 * </ul>
	 */
	private void applySessionStep()
	{
		int low = session.getLow();
		int high = session.getHigh();
		int mid = session.getMid();
		List<Plugin> suspects = session.getSuspects();

		for (int i = 0; i < suspects.size(); i++)
		{
			Plugin plugin = suspects.get(i);
			boolean isEnabled = pluginManager.isPluginEnabled(plugin);

			if (i < low || i > high)
			{
				// Outside current search range — cleared, restore to original
				Boolean originalState = session.getOriginalStates().get(plugin);
				boolean shouldBeEnabled = originalState != null && originalState;
				if (shouldBeEnabled != isEnabled)
				{
					setPluginState(plugin, shouldBeEnabled);
				}
			}
			else if (i <= mid)
			{
				// Enabled half [low, mid] — should be on
				if (!isEnabled)
				{
					setPluginState(plugin, true);
				}
			}
			else
			{
				// Disabled half (mid, high] — should be off
				if (isEnabled)
				{
					setPluginState(plugin, false);
				}
			}
		}
	}

	private void restoreAll()
	{
		if (session == null)
		{
			return;
		}

		for (Map.Entry<Plugin, Boolean> entry : session.getOriginalStates().entrySet())
		{
			boolean wasEnabled = entry.getValue();
			boolean isEnabled = pluginManager.isPluginEnabled(entry.getKey());

			if (wasEnabled != isEnabled)
			{
				setPluginState(entry.getKey(), wasEnabled);
			}
		}
	}

	private void restoreExcept(Plugin exclude)
	{
		if (session == null)
		{
			return;
		}

		for (Map.Entry<Plugin, Boolean> entry : session.getOriginalStates().entrySet())
		{
			Plugin plugin = entry.getKey();
			boolean isEnabled = pluginManager.isPluginEnabled(plugin);

			if (plugin == exclude)
			{
				if (isEnabled)
				{
					setPluginState(plugin, false);
				}
				continue;
			}

			if (entry.getValue() != isEnabled)
			{
				setPluginState(plugin, entry.getValue());
			}
		}
	}

	/**
	 * Runs plugin operations on a background thread to avoid blocking the EDT,
	 * then executes the given callback on the EDT when complete.
	 * Sets {@link #operationInProgress} to prevent re-entrant clicks.
	 */
	private void runPluginOpsInBackground(Runnable pluginOps, Runnable edtCallback)
	{
		operationInProgress = true;
		new SwingWorker<Void, Void>()
		{
			@Override
			protected Void doInBackground()
			{
				pluginOps.run();
				return null;
			}

			@Override
			protected void done()
			{
				try
				{
					get();
				}
				catch (Exception e)
				{
					log.warn("Background plugin operation failed", e);
				}
				operationInProgress = false;
				edtCallback.run();
			}
		}.execute();
	}

	private void setPluginState(Plugin plugin, boolean enabled)
	{
		pluginManager.setPluginEnabled(plugin, enabled);
		try
		{
			if (enabled)
			{
				pluginManager.startPlugin(plugin);
			}
			else
			{
				pluginManager.stopPlugin(plugin);
			}
		}
		catch (PluginInstantiationException e)
		{
			log.warn("Failed to {} plugin {} during troubleshoot",
				enabled ? "start" : "stop",
				plugin.getClass().getSimpleName(), e);
		}
	}

	// -- Card presentation --

	private void showCard(String card)
	{
		cardLayout.show(cardContainer, card);
		cardContainer.revalidate();
		cardContainer.repaint();
	}

	private void showTerminal(String header, String body)
	{
		buildTerminalCard(header, body);
		showCard(CARD_TERMINAL);
	}

	// -- Card: IDLE --

	private void buildIdleCard()
	{
		idleCard = createBasePanel();
		populateIdleCard(idleCard);
		cardContainer.add(idleCard, CARD_IDLE);
	}

	private void rebuildIdleCard()
	{
		idleCard.removeAll();
		populateIdleCard(idleCard);
		idleCard.revalidate();
		idleCard.repaint();
	}

	private void populateIdleCard(JPanel panel)
	{
		panel.add(createTitle("Plugin Troubleshooter"));
		panel.add(Box.createVerticalStrut(12));

		panel.add(createWrappedLabel(
			"<html>Having a problem? This tool helps you quickly"
				+ " find which plugin is causing it.<br><br>"
				+ "How it works:<br>"
				+ "1. Some plugins will be temporarily disabled<br>"
				+ "2. You check if the issue still occurs<br>"
				+ "3. We repeat until the culprit is found</html>"));
		panel.add(Box.createVerticalStrut(16));

		List<Plugin> candidates = collectCandidates();
		int candidateCount = candidates.size();
		int estimatedSteps = TroubleshooterSession.computeTotalSteps(candidateCount);

		JLabel stats = createWrappedLabel(
			"<html>" + candidateCount + " plugin"
				+ (candidateCount != 1 ? "s are" : " is")
				+ " currently active.<br>"
				+ "Estimated steps: " + estimatedSteps + "</html>");
		stats.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(stats);
		panel.add(Box.createVerticalStrut(20));

		JButton startButton = createButton("Start Troubleshooting", COLOR_GOOD);
		startButton.addActionListener(e -> startSession());
		startButton.setEnabled(candidateCount > 0);
		panel.add(startButton);
	}

	// -- Card: RUNNING --

	private void buildRunningCard()
	{
		if (runningCard != null)
		{
			cardContainer.remove(runningCard);
		}

		runningCard = createBasePanel();

		// Progress header
		JLabel stepLabel = createTitle("Step " + session.getStep() + " of " + session.getTotalSteps());
		runningCard.add(stepLabel);
		runningCard.add(Box.createVerticalStrut(8));

		// Progress bar
		double progress = (double) session.getStep() / session.getTotalSteps();
		runningCard.add(createProgressBar(progress));
		runningCard.add(Box.createVerticalStrut(12));

		// Enabled/disabled counts
		List<Plugin> enabledPlugins = session.getEnabledHalf();
		List<Plugin> disabledPlugins = session.getDisabledHalf();
		int remaining = enabledPlugins.size() + disabledPlugins.size();
		int cleared = session.getSuspects().size() - remaining;

		StringBuilder infoHtml = new StringBuilder("<html>");
		infoHtml.append("<b>").append(remaining).append("</b> suspect")
			.append(remaining != 1 ? "s" : "")
			.append(" remaining: <b>").append(enabledPlugins.size()).append("</b> enabled, <b>")
			.append(disabledPlugins.size()).append("</b> disabled for this test.");
		if (cleared > 0)
		{
			infoHtml.append("<br><b>").append(cleared).append("</b> cleared plugin")
				.append(cleared != 1 ? "s" : "")
				.append(" re-enabled.");
		}
		infoHtml.append("<br><br>Try to reproduce the issue now.</html>");

		JLabel info = createWrappedLabel(infoHtml.toString());
		runningCard.add(info);
		runningCard.add(Box.createVerticalStrut(12));

		// Prompt
		JLabel prompt = new JLabel("Does the problem still occur?");
		prompt.setForeground(Color.WHITE);
		prompt.setFont(FontManager.getRunescapeBoldFont());
		prompt.setAlignmentX(LEFT_ALIGNMENT);
		runningCard.add(prompt);
		runningCard.add(Box.createVerticalStrut(10));

		// Action buttons
		JButton badButton = createButton("Yes, still broken", COLOR_BAD);
		badButton.addActionListener(e -> advanceBad());
		runningCard.add(badButton);
		runningCard.add(Box.createVerticalStrut(6));

		JButton goodButton = createButton("No, it's fixed now", COLOR_GOOD);
		goodButton.addActionListener(e -> advanceGood());
		runningCard.add(goodButton);
		runningCard.add(Box.createVerticalStrut(16));

		JButton cancelButton = createButton("Cancel", COLOR_NEUTRAL);
		cancelButton.addActionListener(e -> cancelSession());
		runningCard.add(cancelButton);
		runningCard.add(Box.createVerticalStrut(16));

		// Plugin lists (hidden by default, toggled via config)
		if (config.showPluginLists())
		{
			addPluginList(runningCard, "Enabled suspects:", enabledPlugins, ColorScheme.PROGRESS_COMPLETE_COLOR);
			runningCard.add(Box.createVerticalStrut(8));
			addPluginList(runningCard, "Disabled suspects:", disabledPlugins, COLOR_BAD);
		}

		cardContainer.add(runningCard, CARD_RUNNING);
	}

	// -- Card: FOUND --

	private void buildFoundCard()
	{
		if (foundCard != null)
		{
			cardContainer.remove(foundCard);
		}

		foundCard = createBasePanel();
		Plugin result = session.getResult();

		JLabel title = createTitle("Found the problem!");
		title.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		foundCard.add(title);
		foundCard.add(Box.createVerticalStrut(12));

		foundCard.add(createWrappedLabel(
			"<html><b>\"" + result.getName() + "\"</b> is likely"
				+ " causing your issue.</html>"));
		foundCard.add(Box.createVerticalStrut(4));

		String pluginSource = ExternalPluginManager.getInternalName(result.getClass()) != null
			? "Plugin Hub plugin"
			: "Core plugin";
		JLabel sourceLabel = new JLabel("(" + pluginSource + ")");
		sourceLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sourceLabel.setFont(FontManager.getRunescapeSmallFont());
		sourceLabel.setAlignmentX(LEFT_ALIGNMENT);
		foundCard.add(sourceLabel);
		foundCard.add(Box.createVerticalStrut(20));

		JButton keepDisabled = createButton("Keep it disabled", COLOR_BAD);
		keepDisabled.addActionListener(e -> finishKeepDisabled());
		foundCard.add(keepDisabled);
		foundCard.add(Box.createVerticalStrut(6));

		JButton reenableAll = createButton("Re-enable all plugins", COLOR_GOOD);
		reenableAll.addActionListener(e -> finishReenableAll());
		foundCard.add(reenableAll);
		foundCard.add(Box.createVerticalStrut(6));

		JButton reportBug = createButton("Report a bug", COLOR_NEUTRAL);
		reportBug.addActionListener(e -> openSupportPage(result));
		foundCard.add(reportBug);

		cardContainer.add(foundCard, CARD_FOUND);
	}

	// -- Card: TERMINAL --

	private void buildTerminalCard(String headerText, String bodyText)
	{
		if (terminalCard != null)
		{
			cardContainer.remove(terminalCard);
		}

		terminalCard = createBasePanel();

		JLabel title = createTitle(headerText);
		title.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		terminalCard.add(title);
		terminalCard.add(Box.createVerticalStrut(12));

		terminalCard.add(createWrappedLabel(bodyText));
		terminalCard.add(Box.createVerticalStrut(20));

		JButton backButton = createButton("Back to start", COLOR_NEUTRAL);
		backButton.addActionListener(e ->
		{
			session = null;
			rebuildIdleCard();
			showCard(CARD_IDLE);
		});
		terminalCard.add(backButton);

		cardContainer.add(terminalCard, CARD_TERMINAL);
	}

	// -- Support link --

	private static void openSupportPage(Plugin plugin)
	{
		String internalName = ExternalPluginManager.getInternalName(plugin.getClass());
		if (internalName != null)
		{
			LinkBrowser.browse("https://runelite.net/plugin-hub/show/" + internalName);
		}
		else
		{
			LinkBrowser.browse("https://github.com/runelite/runelite/wiki/"
				+ plugin.getName().replace(' ', '-'));
		}
	}

	// -- UI component factories --

	private static JPanel createBasePanel()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(10, 10, 10, 10));
		return panel;
	}

	private static JLabel createTitle(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	private static JLabel createWrappedLabel(String html)
	{
		String wrapped = html.startsWith("<html>") ? html : "<html>" + html + "</html>";
		JLabel label = new JLabel(wrapped);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	private static JButton createButton(String text, Color background)
	{
		JButton button = new JButton(text);
		button.setAlignmentX(LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		button.setFocusPainted(false);
		button.setBackground(background);
		button.setForeground(Color.WHITE);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(background.darker(), 1),
			new EmptyBorder(4, 8, 4, 8)));
		return button;
	}

	private static JPanel createProgressBar(double fraction)
	{
		JPanel bar = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

				int w = getWidth();
				int h = getHeight();

				g2.setColor(COLOR_PROGRESS_BG);
				g2.fillRoundRect(0, 0, w, h, 6, 6);

				int fillWidth = Math.max(1, (int) (w * fraction));
				g2.setColor(COLOR_PROGRESS_FG);
				g2.fillRoundRect(0, 0, fillWidth, h, 6, 6);

				g2.dispose();
			}
		};
		bar.setOpaque(false);
		bar.setAlignmentX(LEFT_ALIGNMENT);
		bar.setPreferredSize(new Dimension(Integer.MAX_VALUE, 8));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
		return bar;
	}

	private static void addPluginList(JPanel parent, String header, List<Plugin> plugins, Color bulletColor)
	{
		JLabel headerLabel = new JLabel(header + " (" + plugins.size() + ")");
		headerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		headerLabel.setFont(FontManager.getRunescapeSmallFont());
		headerLabel.setAlignmentX(LEFT_ALIGNMENT);
		parent.add(headerLabel);
		parent.add(Box.createVerticalStrut(4));

		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		list.setBorder(new EmptyBorder(6, 8, 6, 8));

		for (Plugin p : plugins)
		{
			JLabel pluginLabel = new JLabel("- " + p.getName());
			pluginLabel.setForeground(bulletColor);
			pluginLabel.setFont(FontManager.getRunescapeSmallFont());
			list.add(pluginLabel);
		}

		JScrollPane scrollPane = new JScrollPane(list);
		scrollPane.setAlignmentX(LEFT_ALIGNMENT);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		scrollPane.setBorder(null);

		// Cap height at 150px so the panel doesn't grow unbounded
		int maxHeight = 150;
		Dimension listPref = list.getPreferredSize();
		int height = Math.min(listPref.height, maxHeight);
		scrollPane.setPreferredSize(new Dimension(0, height));
		scrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxHeight));

		parent.add(scrollPane);
	}
}
