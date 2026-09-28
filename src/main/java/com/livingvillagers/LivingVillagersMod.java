package com.livingvillagers;

import com.livingvillagers.registry.ModBlocks;
import com.livingvillagers.registry.ModPointsOfInterest;
import com.livingvillagers.registry.ModProfessions;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterestStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;



/**
 * Главная точка входа мода LivingVillagers.
 *
 * Это код, который выполняется и на сервере, и на клиенте (общая логика).
 * Чисто клиентский код (рендер, экран диалога) находится в LivingVillagersModClient
 * (см. src/client) — так и сервер, и клиент собираются раздельно, что защищает
 * от случайного вызова клиентского кода на сервере.
 *
 * Диагностический шаг перед Волной 1: подписываем каждому ванильному жителю
 * его текущую (пока ещё ванильную) профессию прямо над головой — это первое
 * видимое в игре доказательство, что мод реально исполняется и может влиять
 * на мир. Сама логика ролей/POI из ТЗ (FR-1, FR-2) появится позже — здесь
 * подпись читает то, что уже даёт vanilla Minecraft, без наших кастомных
 * профессий.
 */
public class LivingVillagersMod implements ModInitializer {

	// Mod ID — должен совпадать со значением "id" в fabric.mod.json
	public static final String MOD_ID = "livingvillagers";

	// Общий логгер мода — используем его вместо System.out.println везде в проекте
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Человекочитаемые русские названия ванильных профессий (id профессии -> подпись).
	private static final Map<String, String> PROFESSION_LABELS = new HashMap<>();

	static {
		PROFESSION_LABELS.put("none", "Безработный");
		PROFESSION_LABELS.put("armorer", "Бронник");
		PROFESSION_LABELS.put("butcher", "Мясник");
		PROFESSION_LABELS.put("cartographer", "Картограф");
		PROFESSION_LABELS.put("cleric", "Священник");
		PROFESSION_LABELS.put("farmer", "Фермер");
		PROFESSION_LABELS.put("fisherman", "Рыбак");
		PROFESSION_LABELS.put("fletcher", "Лучных дел мастер");
		PROFESSION_LABELS.put("leatherworker", "Кожевник");
		PROFESSION_LABELS.put("librarian", "Библиотекарь");
		PROFESSION_LABELS.put("mason", "Каменщик");
		PROFESSION_LABELS.put("nitwit", "Простак");
		PROFESSION_LABELS.put("shepherd", "Пастух");
		PROFESSION_LABELS.put("toolsmith", "Инструментальщик");
		PROFESSION_LABELS.put("weaponsmith", "Оружейник");

		// Наши собственные роли из ТЗ (раздел 9, Волна 1 и далее).
		PROFESSION_LABELS.put("lumberjack", "Лесоруб");
	}

	// Считаем тики, чтобы не пересчитывать подписи каждый такт (это дорого) —
	// достаточно проверять раз в секунду (20 тиков).
	private int tickCounter = 0;

	@Override
	public void onInitialize() {
		// Эта строка появится в логе Minecraft (logs/latest.log) при старте игры,
		// если мод корректно загрузился.
		LOGGER.info("LivingVillagers: мод загружен, окружение настроено верно (Этап 0 пройден).");

		// Порядок важен: сначала блок, потом POI (ему нужен блок), потом
		// профессия (ей нужен POI). См. пакет com.livingvillagers.registry.
		ModBlocks.init();
		ModPointsOfInterest.init();
		ModProfessions.init();

		// Кладём "Пост лесоруба" во вкладку творческого инвентаря "Функциональные
		// блоки" — чтобы его было легко найти и поставить рядом с жителем без команд.
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries ->
				entries.add(new ItemStack(ModBlocks.LUMBERJACK_POST)));

		LOGGER.info("LivingVillagers: зарегистрирована первая роль — Лесоруб (POI: lumberjack_post).");

		// Отладочная команда: /lvpoi [радиус] — печатает все точки интереса
		// (POI), которые движок реально видит вокруг игрока, и их статус
		// занятости. Полезна для диагностики любой будущей роли (Строитель,
		// Кузнец, Шахтёр): если новый рабочий блок не появляется в списке —
		// значит где-то ошибка в регистрации POI, а не в чём-то другом.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("lvpoi")
						.executes(ctx -> runLvPoiCommand(ctx.getSource(), 16))
						.then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 128))
								.executes(ctx -> runLvPoiCommand(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))))));

		// Раз в секунду проходим по всем жителям в каждом загруженном мире
		// и обновляем им подпись над головой в соответствии с профессией.
		ServerTickEvents.END_WORLD_TICK.register(world -> {
			tickCounter++;
			if (tickCounter % 20 != 0) {
				return;
			}

			for (Entity entity : world.iterateEntities()) {
				if (entity instanceof VillagerEntity villager) {
					updateNameTag(villager);
				}
			}
		});
	}

	private int runLvPoiCommand(net.minecraft.server.command.ServerCommandSource source, int radius) {
		net.minecraft.server.world.ServerWorld world = source.getWorld();
		BlockPos center = BlockPos.ofFloored(source.getPosition());
		PointOfInterestStorage storage = world.getPointOfInterestStorage();

		var found = storage.getInSquare(entry -> true, center, radius, PointOfInterestStorage.OccupationStatus.ANY).toList();

		source.sendFeedback(() -> Text.literal("[LV] POI в радиусе " + radius + " блоков от " + center + ": " + found.size()), false);
		for (var poi : found) {
			BlockPos pos = poi.getPos();
			String typeId = String.valueOf(poi.getType().getKey().map(k -> k.getValue().toString()).orElse("???"));
			source.sendFeedback(() -> Text.literal(" - " + pos + " тип=" + typeId
					+ " занято=" + poi.hasSpace() + " (свободно тикетов: " + poi.getFreeTickets() + ")"), false);
		}

		// Дополнительно: что вернёт getType() для блока прямо перед игроком —
		// самый прямой способ проверить, видит ли хранилище POI наш блок вообще.
		BlockPos ahead = center;
		var direct = storage.getType(ahead);
		source.sendFeedback(() -> Text.literal("[LV] getType() на позиции игрока " + ahead + ": "
				+ direct.map(e -> e.getKey().map(Object::toString).orElse("без ключа")).orElse("ПУСТО (нет POI)")), false);

		return found.size();
	}

	private void updateNameTag(VillagerEntity villager) {
		VillagerProfession profession = villager.getVillagerData().getProfession();
		String label = PROFESSION_LABELS.getOrDefault(profession.id(), profession.id());

		Text nameTag = Text.literal("[LV] " + label);

		// Обновляем имя, только если оно реально изменилось — не шлём лишние
		// пакеты на клиент каждую секунду без нужды.
		if (!nameTag.equals(villager.getCustomName())) {
			villager.setCustomName(nameTag);
			villager.setCustomNameVisible(true);
		}
	}
}
