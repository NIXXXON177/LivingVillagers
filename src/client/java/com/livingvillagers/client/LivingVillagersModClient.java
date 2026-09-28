package com.livingvillagers.client;

import net.fabricmc.api.ClientModInitializer;
import com.livingvillagers.LivingVillagersMod;

/**
 * Точка входа клиентской части мода.
 *
 * Здесь в будущем появится всё, что должно работать только у игрока:
 * GUI-экран диалога с жителем (раздел 5.2 ТЗ), рендер многоуровневой брони
 * и т.п. Серверу (и логике singleplayer-мира) этот код недоступен.
 */
public class LivingVillagersModClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		LivingVillagersMod.LOGGER.info("LivingVillagers: клиентская часть мода загружена.");
	}
}
