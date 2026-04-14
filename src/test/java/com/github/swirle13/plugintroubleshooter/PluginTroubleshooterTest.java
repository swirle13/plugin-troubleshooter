package com.github.swirle13.plugintroubleshooter;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PluginTroubleshooterTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PluginTroubleshooterPlugin.class);
		RuneLite.main(args);
	}
}

