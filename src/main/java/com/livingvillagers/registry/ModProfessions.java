package com.livingvillagers.registry;

import com.google.common.collect.ImmutableSet;
import com.livingvillagers.LivingVillagersMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerProfession;

/**
 * Кастомные профессии жителей.
 *
 * ЛЕСОРУБ — первая настоящая роль из ТЗ (раздел 9, Волна 1). Пока это ещё
 * "заглушка": профессия и рабочее место (см. ModPointsOfInterest) уже
 * работают по-настоящему — безработный житель сам находит и занимает пост
 * лесоруба, — но собственно рубку деревьев мы добавим следующим шагом
 * (FR из раздела 6 ТЗ, роль "Лесоруб").
 */
public final class ModProfessions {

	public static final VillagerProfession LUMBERJACK = register(
			"lumberjack",
			ModPointsOfInterest.LUMBERJACK_POST_KEY,
			SoundEvents.ENTITY_VILLAGER_WORK_FLETCHER
	);

	private ModProfessions() {
	}

	private static VillagerProfession register(String path, RegistryKey<net.minecraft.world.poi.PointOfInterestType> workstation, net.minecraft.sound.SoundEvent workSound) {
		Identifier id = Identifier.of(LivingVillagersMod.MOD_ID, path);
		RegistryKey<VillagerProfession> key = RegistryKey.of(RegistryKeys.VILLAGER_PROFESSION, id);

		VillagerProfession profession = new VillagerProfession(
				path,
				entry -> entry.matchesKey(workstation),
				entry -> entry.matchesKey(workstation),
				ImmutableSet.of(),
				ImmutableSet.of(),
				workSound
		);

		return Registry.register(Registries.VILLAGER_PROFESSION, key, profession);
	}

	public static void init() {
	}
}
