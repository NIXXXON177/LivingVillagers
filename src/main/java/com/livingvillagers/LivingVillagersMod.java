package com.livingvillagers;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Главная точка входа мода LivingVillagers.
 *
 * Это код, который выполняется и на сервере, и на клиенте (общая логика).
 * Чисто клиентский код (рендер, экран диалога) находится в LivingVillagersModClient
 * (см. src/client) — так и сервер, и клиент собираются раздельно, что защищает
 * от случайного вызова клиентского кода на сервере.
 *
 * На Этапе 0 мод ничего не делает, кроме как подтверждает своё успешное
 * подключение — это нужно, чтобы убедиться, что окружение (Gradle, Fabric
 * Loader, Minecraft) настроено верно, прежде чем начинать Волну 1 из ТЗ.
 */
public class LivingVillagersMod implements ModInitializer {

	// Mod ID — должен совпадать со значением "id" в fabric.mod.json
	public static final String MOD_ID = "livingvillagers";

	// Общий логгер мода — используем его вместо System.out.println везде в проекте
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Эта строка появится в логе Minecraft (logs/latest.log) при старте игры,
		// если мод корректно загрузился.
		LOGGER.info("LivingVillagers: мод загружен, окружение настроено верно (Этап 0 пройден).");
	}
}
