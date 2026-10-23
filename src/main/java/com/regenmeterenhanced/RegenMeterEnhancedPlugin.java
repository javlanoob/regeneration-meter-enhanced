/*
 * Copyright (c) 2026, javlanoob
 * Copyright (c) 2019, Sean Dewar <https://github.com/seandewar>
 * Copyright (c) 2018, Abex
 * Copyright (c) 2018, Zimaya <https://github.com/Zimaya>
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
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
package com.regenmeterenhanced;

import com.google.inject.Provides;
import java.util.Locale;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Regeneration Meter Enhanced",
	description = "Regeneration meter that holds when the game is not regenerating and resets on every regen",
	tags = {"combat", "health", "hitpoints", "special", "attack", "overlay", "regen", "colosseum"}
)
public class RegenMeterEnhancedPlugin extends Plugin
{
	private static final int HP_CYCLE = 100;
	private static final int HP_CYCLE_FAST = 50;
	private static final int SPEC_CYCLE = 50;
	private static final int SPEC_CYCLE_LIGHTBEARER = 25;

	// Energy is held in tenths of a percent, so a regen is 10% of the bar
	private static final int SPEC_FULL = 1000;
	private static final int SPEC_PER_REGEN = 100;

	private static final int REGION_COLOSSEUM = 7216;

	/**
	 * The delve arenas: the first one, then the one the next few are in, then the one from delve 5 on.
	 */
	private static final int[] REGIONS_DOOM = {5269, 13668, 14180};

	/**
	 * The Doom in each of the three forms it swaps between over a fight.
	 */
	private static final int[] NPCS_DOOM = {NpcID.DOM_BOSS, NpcID.DOM_BOSS_SHIELDED, NpcID.DOM_BOSS_BURROWED};

	/**
	 * Ticks the Doom has to be gone for before it is taken as gone. It drops out of the arena for a
	 * tick or two while swapping forms mid fight, which is not a floor ending.
	 */
	private static final int DOOM_GRACE = 3;

	/**
	 * Every cycle length either timer is known to run at. A gap between two regens that is not one of
	 * these is a regen that went unseen rather than a different cycle, so it is never believed.
	 * <p>
	 * Besides the ordinary rates there is Rejuvenation at about twenty seconds, and the special attack
	 * rate doubled by Yama's contract of familiar acquisition, which lands on twenty five ticks bare
	 * and half that with a Lightbearer.
	 */
	private static final int[] CYCLES = {HP_CYCLE, HP_CYCLE_FAST, 33, SPEC_CYCLE_LIGHTBEARER, 13, 12};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Notifier notifier;

	@Inject
	private RegenMeterEnhancedOverlay overlay;

	@Inject
	private RegenMeterEnhancedConfig config;

	@Getter
	private double hitpointsPercentage;

	@Getter
	private double specialPercentage;

	// Whether a meter is sat on a regen that has not come, which is what being paused looks like
	@Getter
	private boolean hitpointsHeld;

	@Getter
	private boolean specialHeld;

	private int hpTicks;
	private int specTicks;

	// The tick each timer last fired on and the gap before it, which is how a cycle is measured
	private int hpTick = -1;
	private int specTick = -1;
	private int hpGap;
	private int specGap;
	private int hpMeasured;
	private int specMeasured;

	private int lastHp = -1;
	private int lastSpec = -1;

	// Ticks something other than the timer moved your hitpoints on, so those moves are not read as one
	private int consumeTick = -1;
	private int dreamTick = -1;
	private int healTick = -1;
	private int damageTick = -1;

	private boolean lightbearer;
	private boolean bracelet;
	private boolean hitpointsCape;

	private boolean inColosseum;
	private boolean waveStarted;

	private boolean inDoom;
	private boolean doomSeen;
	private int doomGone;

	@Provides
	RegenMeterEnhancedConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RegenMeterEnhancedConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		clientThread.invokeLater(() -> readWorn(client.getItemContainer(InventoryID.WORN)));
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		reset();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.HOPPING || event.getGameState() == GameState.LOGIN_SCREEN)
		{
			reset();
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() == VarbitID.PRAYER_RAPIDHEAL)
		{
			// Turning Rapid Heal on or off starts the hitpoints cycle over on the server
			hpTicks = 0;
			forgetHpCycle();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.WORN)
		{
			readWorn(event.getItemContainer());
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		Hitsplat hitsplat = event.getHitsplat();
		if (hitsplat.getHitsplatType() == HitsplatID.HEAL)
		{
			healTick = client.getTickCount();
		}
		else if (hitsplat.getAmount() > 0)
		{
			damageTick = client.getTickCount();
		}
	}

	/**
	 * Whether a Colosseum wave is running, which is what its special attack pause turns on. The pause
	 * lasts from the wave ending to the next one starting, so neither the interface being up nor it
	 * being closed marks either end of it: closing it to walk about between waves leaves it running.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE || !inColosseum)
		{
			return;
		}

		String message = event.getMessage();
		if (message.contains("Wave: ") || message.contains("Sol Heredit jumps down from his seat"))
		{
			waveStarted = true;
		}
		else if (message.startsWith("Wave ") && message.contains("completed!"))
		{
			waveStarted = false;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		int tick = client.getTickCount();

		Player player = client.getLocalPlayer();
		if (player != null && player.getAnimation() == AnimationID.HUMAN_EAT)
		{
			consumeTick = tick;
		}

		// Dream heals on a timer of its own, running alongside the usual one rather than replacing it,
		// so its points are not a regen and must not put the cycle back to the start
		if (player != null && player.hasSpotAnim(SpotanimID.DREAM_ZEDS_SPOTANIM))
		{
			dreamTick = tick;
		}

		readPause(player);

		tickSpecial(tick);
		tickHitpoints(tick);
	}

	/**
	 * The special attack timer, read off the energy itself.
	 * <p>
	 * Energy arrives in whole tenths, so every regen shows up in the varp and the timer is put back to
	 * the start of its cycle on the tick it lands on. Nothing else is taken for a regen: a potion, or
	 * the restore on entering the Colosseum, comes in a different size and leaves the cycle alone.
	 */
	private void tickSpecial(int tick)
	{
		int spec = client.getVarpValue(VarPlayerID.SA_ENERGY);
		int delta = lastSpec == -1 ? 0 : spec - lastSpec;
		lastSpec = spec;

		int cycle = specMeasured != 0 ? specMeasured : (lightbearer ? SPEC_CYCLE_LIGHTBEARER : SPEC_CYCLE);
		boolean paused = (inColosseum && !waveStarted) || (inDoom && doomSeen && doomGone >= DOOM_GRACE);

		if (spec == SPEC_FULL)
		{
			// The cycle does not run at full energy. It starts over when you spend some.
			restartSpecCycle();
		}
		else if (delta == SPEC_PER_REGEN)
		{
			// Energy arriving means the fight is on, whatever the arena was read as
			waveStarted = true;
			doomGone = 0;
			paused = false;

			specMeasured = measure(tick, specTick, specGap, specMeasured);
			specGap = specTick < 0 ? 0 : tick - specTick;
			specTick = tick;
			specTicks = 0;
		}
		else if (paused)
		{
			// A pause does not hold the cycle where it stood, it starts it over, so the first energy
			// after a wave or a floor is always a whole cycle away however far through the pause began
			restartSpecCycle();
		}
		else if (delta != 0 && specTicks + 1 >= cycle)
		{
			// Energy spent or drunk on the same tick the cycle pays hides that tenth in the change, so
			// a cycle that runs out on a tick the energy moved is taken as paid rather than held back
			specGap = 0;
			specTick = tick;
			specTicks = 0;
		}
		else
		{
			specTicks = advance(specTicks, cycle, true);
		}

		specialPercentage = clamp(specTicks / (double) cycle);
		specialHeld = spec != SPEC_FULL && !paused && specTicks >= cycle;
	}

	/**
	 * The hitpoints timer, read off your hitpoints.
	 * <p>
	 * Nothing is sent to the client about where the server is in the cycle, so it is counted out here
	 * and put back to the start every time a point actually lands. However far out of step the count
	 * has drifted, one regen brings it back. A point you were given rather than regenerated, by food or
	 * a heal, is left out, and so is a point lost to damage, since neither moves the cycle.
	 */
	private void tickHitpoints(int tick)
	{
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int max = client.getRealSkillLevel(Skill.HITPOINTS);
		int delta = lastHp == -1 ? 0 : hp - lastHp;
		lastHp = hp;

		int cycle = cycle();
		boolean fed = consumeTick >= tick - 1 || healTick >= tick - 1 || dreamTick >= tick - 1;
		boolean hurt = damageTick >= tick - 1;

		// A regen puts up as many points as the cycle pays, or fewer if that is all the room there is
		boolean regen = delta > 0 && delta <= amount() && hp <= max && !fed;
		// Above your maximum the same cycle takes a point back off you instead of putting one on
		boolean decay = delta == -1 && hp >= max && !hurt;
		// A point that lands on the same tick as food, a potion or a hit is lost in the change, so a
		// cycle that runs out on a tick your hitpoints moved for another reason is taken as paid. Held
		// back instead, one unseen point sits the meter full until the point after it
		boolean masked = !regen && !decay && hp != max && hpTicks + 1 >= cycle && (delta != 0 || fed || hurt);

		if (regen || decay)
		{
			hpMeasured = measure(tick, hpTick, hpGap, hpMeasured);
			hpGap = hpTick < 0 ? 0 : tick - hpTick;
			hpTick = tick;
			hpTicks = 0;
		}
		else if (masked)
		{
			// A tick that only might have paid cannot time the cycle, so it breaks the run of gaps
			// rather than being measured as one
			hpGap = 0;
			hpTick = tick;
			hpTicks = 0;
		}
		else
		{
			// At your maximum there is no regen to see, so there is nothing to hold the cycle on either
			hpTicks = advance(hpTicks, cycle, hp != max);
		}

		hitpointsPercentage = clamp(hpTicks / (double) cycle);
		hitpointsHeld = hp != max && hpTicks >= cycle;

		if (hp == max && !config.showWhenNoChange())
		{
			hitpointsPercentage = 0;
		}
		else if (hp > max)
		{
			// Show it going down
			hitpointsPercentage = 1 - hitpointsPercentage;
		}

		if (config.getNotifyBeforeHpRegenSeconds() > 0 && hp < max && notifyThisTick(cycle))
		{
			notifier.notify("Your next hitpoint will regenerate soon!");
		}
	}

	/**
	 * How long the hitpoints cycle runs for. The Rapid Heal prayer and the hitpoints cape perk each
	 * halve it and do not stack with one another, so either one on its own is as fast as it gets.
	 */
	private int cycle()
	{
		if (hpMeasured != 0)
		{
			return hpMeasured;
		}

		return hitpointsCape || client.getVarbitValue(VarbitID.PRAYER_RAPIDHEAL) == 1 ? HP_CYCLE_FAST : HP_CYCLE;
	}

	/**
	 * How many points a cycle pays. The regen bracelet doubles the points rather than the rate, which
	 * is why it stacks with Rapid Heal and with the cape for four points a minute instead of two.
	 */
	private int amount()
	{
		return bracelet ? 2 : 1;
	}

	/**
	 * Takes the gap between two regens as the real cycle length, but only once two gaps running have
	 * agreed on it. A regen that went unseen leaves a gap of its own, and one of those can land on a
	 * cycle length the game does use, so a single gap is never enough to go on.
	 */
	private static int measure(int tick, int lastTick, int lastGap, int measured)
	{
		if (lastTick < 0)
		{
			return measured;
		}

		int gap = tick - lastTick;
		if (gap != lastGap)
		{
			return measured;
		}

		for (int cycle : CYCLES)
		{
			if (gap == cycle)
			{
				return gap;
			}
		}

		return measured;
	}

	/**
	 * Keeps a meter inside its circle. Putting on a hitpoints cape, or measuring a faster cycle than
	 * was assumed, shortens the cycle under a count already past the new length.
	 */
	private static double clamp(double percent)
	{
		return Math.max(0d, Math.min(1d, percent));
	}

	/**
	 * One tick of a cycle. A cycle that runs out without its regen landing is held at the end rather
	 * than started over, so the meter sits full where it would otherwise fill a second time over.
	 */
	private static int advance(int ticks, int cycle, boolean holdable)
	{
		int next = ticks + 1;
		if (next < cycle)
		{
			return next;
		}

		return holdable ? cycle : next - cycle;
	}

	/**
	 * The two places the server stops restoring special attack energy: the Fortis Colosseum between
	 * waves, and a delve in the Doom of Mokhaiotl while the Doom is not in the arena. Neither pause is
	 * sent to the client, so both are read off what the arena is doing.
	 * <p>
	 * The Doom is read from the boss itself rather than the floor messages because it marks both ends
	 * of the pause: it leaves the arena when the floor is cleared and is back the moment the next one
	 * starts. The lobby is in a delve region as well, so an arena only counts while it is instanced
	 * and only once the Doom has been seen in it.
	 */
	private void readPause(Player player)
	{
		WorldView view = player == null ? null : player.getWorldView();
		LocalPoint local = player == null ? null : player.getLocalLocation();
		boolean instance = local != null && view != null && view.isInstance();

		inColosseum = instance
			&& WorldPoint.fromLocalInstance(client, local).getRegionID() == REGION_COLOSSEUM;

		if (!inColosseum)
		{
			waveStarted = false;
		}

		inDoom = instance && inAnyRegion(view, REGIONS_DOOM);

		if (!inDoom)
		{
			doomSeen = false;
			doomGone = 0;
		}
		else if (isDoomPresent(view))
		{
			doomSeen = true;
			doomGone = 0;
		}
		else if (doomGone < DOOM_GRACE)
		{
			doomGone++;
		}
	}

	private static boolean inAnyRegion(WorldView view, int[] regions)
	{
		for (int loaded : view.getMapRegions())
		{
			for (int region : regions)
			{
				if (loaded == region)
				{
					return true;
				}
			}
		}

		return false;
	}

	private static boolean isDoomPresent(WorldView view)
	{
		for (NPC npc : view.npcs())
		{
			for (int id : NPCS_DOOM)
			{
				if (npc.getId() == id)
				{
					return true;
				}
			}
		}

		return false;
	}

	private boolean notifyThisTick(int cycle)
	{
		// if the configured duration lies between two ticks, choose the earlier tick
		final int ticksBeforeHPRegen = cycle - hpTicks;
		final int notifyTick = (int) Math.ceil(config.getNotifyBeforeHpRegenSeconds() * 1000d / Constants.GAME_TICK_LENGTH);
		return ticksBeforeHPRegen == notifyTick;
	}

	private void readWorn(ItemContainer worn)
	{
		boolean hadLightbearer = lightbearer;
		boolean hadBracelet = bracelet;
		boolean hadCape = hitpointsCape;

		lightbearer = worn != null && worn.contains(ItemID.LIGHTBEARER);
		bracelet = worn != null && worn.contains(ItemID.JEWL_BRACELET_REGEN);
		hitpointsCape = worn != null && isHitpointsCape(worn.getItem(EquipmentInventorySlot.CAPE.getSlotIdx()));

		if (lightbearer != hadLightbearer)
		{
			// A Lightbearer switch keeps the time to the next regen if under 25 ticks of it are left,
			// and taking it off always leaves none
			specTicks = Math.max(0, specTicks - SPEC_CYCLE_LIGHTBEARER);
			specMeasured = 0;
			specGap = 0;
			specTick = -1;
		}

		if (bracelet != hadBracelet || hitpointsCape != hadCape)
		{
			forgetHpCycle();
		}
	}

	/**
	 * The hitpoints cape perk is on the cape and on every max cape, which is a long list of ids that
	 * grows with each new one, so the cape you are wearing is read by name instead.
	 */
	private boolean isHitpointsCape(Item cape)
	{
		if (cape == null || cape.getId() <= 0)
		{
			return false;
		}

		int id = cape.getId();
		if (id == ItemID.SKILLCAPE_HITPOINTS || id == ItemID.SKILLCAPE_HITPOINTS_TRIMMED)
		{
			return true;
		}

		String name = itemManager.getItemComposition(id).getName();
		return name != null && name.toLowerCase(Locale.ROOT).contains("max cape");
	}

	/**
	 * Puts the special attack cycle back to the start and drops the gap it was being measured from,
	 * since a cycle that was started over rather than paid cannot time the cycle before it.
	 */
	private void restartSpecCycle()
	{
		specTicks = 0;
		specTick = -1;
		specGap = 0;
	}

	private void forgetHpCycle()
	{
		hpMeasured = 0;
		hpGap = 0;
		hpTick = -1;
	}

	private void reset()
	{
		// Where a login leaves the hitpoints count, carried over from the original meter
		hpTicks = -2;
		hpGap = 0;
		hpMeasured = 0;
		hpTick = -1;
		restartSpecCycle();
		specMeasured = 0;
		hitpointsPercentage = 0;
		specialPercentage = 0;
		lastHp = -1;
		lastSpec = -1;
		consumeTick = -1;
		dreamTick = -1;
		healTick = -1;
		damageTick = -1;
		hitpointsHeld = false;
		specialHeld = false;
		inColosseum = false;
		waveStarted = false;
		inDoom = false;
		doomSeen = false;
		doomGone = 0;
	}
}
