package com.livingvillagers.registry;

import com.livingvillagers.LivingVillagersMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.poi.PointOfInterestType;

/**
 * Точки интереса (POI) мода — блоки, которые жители умеют находить и
 * "застолбить" за собой как рабочее место.
 */
public final class ModPointsOfInterest {

	// Регистрационный ключ создаём заранее и переиспользуем в ModProfessions —
	// так профессия и POI всегда ссылаются друг на друга по одному и тому же id.
	public static final RegistryKey<PointOfInterestType> LUMBERJACK_POST_KEY =
			RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, Identifier.of(LivingVillagersMod.MOD_ID, "lumberjack_post"));

	public static final PointOfInterestType LUMBERJACK_POST = register(
			LUMBERJACK_POST_KEY,
			ModBlocks.LUMBERJACK_POST
	);

	private ModPointsOfInterest() {
	}

	private static PointOfInterestType register(RegistryKey<PointOfInterestType> key, net.minecraft.block.Block block) {
		// ticketCount = 1: рабочее место занимает ровно один житель одновременно.
		// searchDistance = 1: как у большинства ванильных рабочих мест (композтер и т.п.).
		PointOfInterestType type = new PointOfInterestType(
				java.util.Set.copyOf(block.getStateManager().getStates()),
				1,
				1
		);
		return Registry.register(Registries.POINT_OF_INTEREST_TYPE, key, type);
	}

	public static void init() {
	}
}
