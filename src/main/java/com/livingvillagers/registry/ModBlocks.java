package com.livingvillagers.registry;

import com.livingvillagers.LivingVillagersMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/**
 * Кастомные блоки мода.
 *
 * Пока их всего один — «Пост лесоруба»: рабочее место (job site) для новой
 * профессии Лесоруб (см. ModProfessions/ModPointsOfInterest). Это заглушка
 * Волны 1 (раздел 9 ТЗ: «Окружение → POI/профессия-заглушка → ...») — блок
 * пока не расставляется миром сам (не встраивается в структуру деревень),
 * его нужно поставить руками рядом с безработным жителем, чтобы проверить,
 * что он корректно становится Лесорубом.
 */
public final class ModBlocks {

	public static final Block LUMBERJACK_POST = register("lumberjack_post");

	private ModBlocks() {
	}

	private static Block register(String path) {
		Identifier id = Identifier.of(LivingVillagersMod.MOD_ID, path);
		RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, id);
		RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, id);

		Block block = new Block(AbstractBlock.Settings.create()
				.mapColor(MapColor.OAK_TAN)
				.strength(2.0f)
				.sounds(BlockSoundGroup.WOOD));
		Registry.register(Registries.BLOCK, blockKey, block);

		BlockItem item = new BlockItem(block, new Item.Settings());
		Registry.register(Registries.ITEM, itemKey, item);

		return block;
	}

	// Вызывается из LivingVillagersMod.onInitialize(), чтобы гарантированно
	// "тронуть" класс и выполнить статическую инициализацию полей выше —
	// без явного обращения к классу статические поля в Java не создадутся.
	public static void init() {
	}
}
