package com.regenmeterenhanced;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class RegenMeterEnhancedPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RegenMeterEnhancedPlugin.class);
		RuneLite.main(args);
	}
}
