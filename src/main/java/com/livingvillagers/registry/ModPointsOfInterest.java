package com.livingvillagers.registry;

import com.livingvillagers.LivingVillagersMod;
import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.poi.PointOfInterestType;

/**
 * Точки интереса (POI) мода — блоки, которые жители умеют находить и
 * "застолбить" за собой как рабочее место.
 *
 * ВАЖНО (найдено на практике, потратив время на отладку): простого
 * `Registry.register(Registries.POINT_OF_INTEREST_TYPE, ...)` НЕДОСТАТОЧНО,
 * чтобы движок реально распознавал блок как точку интереса! Внутри
 * ванильного `PointOfInterestStorage` есть отдельная приватная карта
 * `PointOfInterestTypes.POI_STATES_TO_TYPE` (BlockState -> тип POI),
 * которую заполняет ТОЛЬКО ванильный бутстрап-код для своих же 13 профессий
 * (фермер, кузнец и т.д.). Обычная регистрация в реестр эту карту не
 * трогает — из-за этого житель физически не видел наш блок как рабочее
 * место, даже если блок стоял рядом и тег acquirable_job_site был на
 * месте. Правильный способ — воспользоваться помощником из Fabric API
 * `PointOfInterestHelper.register(...)`, который (через access widener)
 * одновременно регистрирует тип В РЕЕСТРЕ и прописывает блокстейты в ту
 * самую служебную карту движка.
 */
public final class ModPointsOfInterest {

	public static final RegistryKey<PointOfInterestType> LUMBERJACK_POST_KEY =
			RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, Identifier.of(LivingVillagersMod.MOD_ID, "lumberjack_post"));

	public static final PointOfInterestType LUMBERJACK_POST = PointOfInterestHelper.register(
			LUMBERJACK_POST_KEY.getValue(),
			1,  // ticketCount: рабочее место занимает ровно один житель одновременно
			1,  // searchDistance: как у большинства ванильных рабочих мест
			ModBlocks.LUMBERJACK_POST
	);

	private ModPointsOfInterest() {
	}

	public static void init() {
	}
}
