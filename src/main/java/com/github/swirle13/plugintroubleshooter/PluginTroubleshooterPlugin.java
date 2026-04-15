
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

import java.awt.image.BufferedImage;
import javax.inject.Inject;
import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
	name = "Plugin Troubleshooter",
	description = "Quickly find which plugin is causing a problem using binary search",
	tags = {"help", "fix", "problem", "issue", "trouble", "troubleshoot",
		"debug", "support", "error", "broken", "diagnose", "find", "bisect"}
)
public class PluginTroubleshooterPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	private NavigationButton navButton;
	private PluginTroubleshooterPanel panel;

	@Provides
	PluginTroubleshooterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PluginTroubleshooterConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = injector.getInstance(PluginTroubleshooterPanel.class);

		final BufferedImage icon = ImageUtil.loadImageResource(
			getClass(), "troubleshooter_icon.png");

		navButton = NavigationButton.builder()
			.tooltip("Plugin Troubleshooter")
			.icon(icon)
			.priority(9)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		panel.setNavigationButton(navButton);
		panel.registerEventBus();
	}

	@Override
	protected void shutDown()
	{
		if (panel != null)
		{
			panel.unregisterEventBus();
			panel.shutdown();
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
	}
}

