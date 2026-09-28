package com.livingvillagers.behavior;

import com.livingvillagers.registry.ModProfessions;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Поведение "Лесоруб рубит деревья".
 *
 * ВАЖНО про архитектуру: это НЕ настоящая ванильная задача мозга (Brain/
 * Activity/Task), а отдельный, полностью самописный конечный автомат,
 * который тикает независимо и напрямую дёргает навигацию/анимацию
 * жителя. Так сильно проще и надёжнее для старта (не нужно лезть миксинами
 * в приватные внутренности VillagerTaskListProvider), но у подхода есть
 * известная цена: ванильный "мозг" жителя тоже иногда пытается управлять
 * его хождением (например, отправить спать ночью или подойти к рабочему
 * месту) — в редких случаях это может привести к небольшим "заминкам"
 * или рывкам в движении, если оба источника решений не совпадают. Если
 * это будет заметно мешать в игре — в следующей волне стоит переписать
 * на честную Brain-задачу.
 *
 * Схема состояний на одного жителя:
 *   IDLE     — ищет ближайшее дерево (с паузой между попытками, если не нашёл).
 *   MOVING   — идёт к найденному стволу.
 *   CHOPPING — рубит стволы дерева снизу вверх, брёвна кладёт в свой инвентарь.
 *   COOLDOWN — короткая пауза после срубленного дерева перед поиском нового.
 */
public final class LumberjackBehavior {

	private static final int HORIZONTAL_SEARCH_RADIUS = 20;
	private static final int VERTICAL_SEARCH_DOWN = 4;
	private static final int VERTICAL_SEARCH_UP = 10;

	private static final int SEARCH_RETRY_COOLDOWN_TICKS = 100;   // 5 секунд между попытками найти дерево
	private static final int POST_TREE_COOLDOWN_TICKS = 60;       // 3 секунды отдыха после срубленного дерева
	private static final int CHOP_TICKS_PER_LOG = 30;              // ~1.5 секунды на одно бревно
	private static final int MAX_MOVE_TICKS = 200;                 // 10 секунд — если за это время не дошёл, сдаёмся
	private static final double REACH_DISTANCE_SQUARED = 3.5 * 3.5;

	/** Какое бревно на какой саженец заменяем после вырубки. */
	private static final Map<Block, Block> SAPLING_BY_LOG = new HashMap<>();

	static {
		SAPLING_BY_LOG.put(Blocks.OAK_LOG, Blocks.OAK_SAPLING);
		SAPLING_BY_LOG.put(Blocks.SPRUCE_LOG, Blocks.SPRUCE_SAPLING);
		SAPLING_BY_LOG.put(Blocks.BIRCH_LOG, Blocks.BIRCH_SAPLING);
		SAPLING_BY_LOG.put(Blocks.JUNGLE_LOG, Blocks.JUNGLE_SAPLING);
		SAPLING_BY_LOG.put(Blocks.ACACIA_LOG, Blocks.ACACIA_SAPLING);
		SAPLING_BY_LOG.put(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_SAPLING);
		SAPLING_BY_LOG.put(Blocks.CHERRY_LOG, Blocks.CHERRY_SAPLING);
	}

	private enum Phase { IDLE, MOVING, CHOPPING, COOLDOWN }

	private static final class State {
		Phase phase = Phase.IDLE;
		Block treeLogType;
		BlockPos treeBasePos;
		final Deque<BlockPos> remainingLogs = new ArrayDeque<>();
		int timer;
	}

	// Состояние на каждого жителя. Не сохраняется на диск между запусками
	// сервера — это осознанно: в худшем случае после рестарта лесоруб
	// просто заново начнёт искать дерево, ничего страшного не произойдёт.
	private static final Map<UUID, State> STATES = new HashMap<>();

	private LumberjackBehavior() {
	}

	public static void tick(ServerWorld world, VillagerEntity villager) {
		if (villager.getVillagerData().getProfession() != ModProfessions.LUMBERJACK) {
			STATES.remove(villager.getUuid());
			return;
		}

		State state = STATES.computeIfAbsent(villager.getUuid(), id -> new State());

		switch (state.phase) {
			case IDLE -> tickIdle(world, villager, state);
			case MOVING -> tickMoving(world, villager, state);
			case CHOPPING -> tickChopping(world, villager, state);
			case COOLDOWN -> tickCooldown(state);
		}
	}

	/** Убирает состояние жителя, если он был удалён из мира (умер/выгружен навсегда). */
	public static void forget(UUID villagerId) {
		STATES.remove(villagerId);
	}

	private static void tickCooldown(State state) {
		state.timer--;
		if (state.timer <= 0) {
			state.phase = Phase.IDLE;
		}
	}

	private static void tickIdle(ServerWorld world, VillagerEntity villager, State state) {
		if (state.timer > 0) {
			state.timer--;
			return;
		}

		BlockPos found = findNearestTreeBase(world, villager.getBlockPos());
		if (found == null) {
			state.timer = SEARCH_RETRY_COOLDOWN_TICKS;
			return;
		}

		state.treeBasePos = found;
		state.treeLogType = world.getBlockState(found).getBlock();
		state.remainingLogs.clear();
		state.remainingLogs.addAll(collectTrunk(world, found, state.treeLogType));
		state.timer = 0;
		state.phase = Phase.MOVING;
	}

	private static void tickMoving(ServerWorld world, VillagerEntity villager, State state) {
		BlockPos target = state.remainingLogs.peekFirst();
		if (target == null || !world.getBlockState(target).isOf(state.treeLogType)) {
			abandon(state);
			return;
		}

		// Не даём ванильному "мозгу" жителя одновременно тащить его к
		// рабочему месту, пока мы сами ведём его к дереву.
		villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
		villager.getBrain().forget(MemoryModuleType.LOOK_TARGET);

		double distanceSquared = villager.squaredDistanceTo(Vec3d.ofCenter(target));
		if (distanceSquared <= REACH_DISTANCE_SQUARED) {
			villager.getNavigation().stop();
			villager.getLookControl().lookAt(Vec3d.ofCenter(target));
			state.phase = Phase.CHOPPING;
			state.timer = CHOP_TICKS_PER_LOG;
			return;
		}

		state.timer++;
		if (state.timer > MAX_MOVE_TICKS) {
			// Не смог дойти (застрял/нет пути) — сдаёмся и попробуем другое дерево позже.
			abandon(state);
			return;
		}

		if (villager.getNavigation().isIdle()) {
			boolean started = villager.getNavigation().startMovingTo(
					target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.5D);
			if (!started) {
				abandon(state);
			}
		}
	}

	private static void tickChopping(ServerWorld world, VillagerEntity villager, State state) {
		BlockPos target = state.remainingLogs.peekFirst();
		if (target == null) {
			finishTree(world, state);
			return;
		}

		BlockState currentState = world.getBlockState(target);
		if (!currentState.isOf(state.treeLogType)) {
			// Кто-то (игрок?) уже сломал это бревно раньше нас — пропускаем.
			state.remainingLogs.pollFirst();
			return;
		}

		villager.getBrain().forget(MemoryModuleType.WALK_TARGET);

		if (state.timer % 6 == 0) {
			villager.swingHand(Hand.MAIN_HAND);
			world.playSound(null, target, currentState.getSoundGroup().getHitSound(),
					SoundCategory.BLOCKS, 0.6f, 1.0f);
		}

		state.timer--;
		if (state.timer > 0) {
			return;
		}

		ItemStack drop = new ItemStack(currentState.getBlock().asItem());
		world.breakBlock(target, false, villager, 512);
		villager.getInventory().addStack(drop);

		state.remainingLogs.pollFirst();
		if (state.remainingLogs.isEmpty()) {
			finishTree(world, state);
		} else {
			// Продолжаем рубить следующее бревно того же ствола без повторного
			// подхода — считаем это абстракцией "лесоруб валит дерево целиком".
			state.timer = CHOP_TICKS_PER_LOG;
		}
	}

	private static void finishTree(ServerWorld world, State state) {
		Block sapling = SAPLING_BY_LOG.get(state.treeLogType);
		if (sapling != null && state.treeBasePos != null) {
			BlockState saplingState = sapling.getDefaultState();
			BlockPos plantPos = state.treeBasePos;
			if (world.getBlockState(plantPos).isAir() && saplingState.canPlaceAt(world, plantPos)) {
				world.setBlockState(plantPos, saplingState);
			}
		}
		abandon(state);
	}

	private static void abandon(State state) {
		state.phase = Phase.COOLDOWN;
		state.timer = POST_TREE_COOLDOWN_TICKS;
		state.remainingLogs.clear();
		state.treeBasePos = null;
		state.treeLogType = null;
	}

	/** Ищет ближайшее основание ствола (бревно, под которым нет другого бревна). */
	private static BlockPos findNearestTreeBase(ServerWorld world, BlockPos origin) {
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		BlockPos best = null;
		double bestDistanceSquared = Double.MAX_VALUE;

		for (int dx = -HORIZONTAL_SEARCH_RADIUS; dx <= HORIZONTAL_SEARCH_RADIUS; dx++) {
			for (int dz = -HORIZONTAL_SEARCH_RADIUS; dz <= HORIZONTAL_SEARCH_RADIUS; dz++) {
				for (int dy = -VERTICAL_SEARCH_DOWN; dy <= VERTICAL_SEARCH_UP; dy++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);

					BlockState state = world.getBlockState(cursor);
					if (!state.isIn(BlockTags.OVERWORLD_NATURAL_LOGS)) {
						continue;
					}
					if (!SAPLING_BY_LOG.containsKey(state.getBlock())) {
						continue; // неизвестный нам тип дерева — пропускаем, не знаем, чем засадить обратно
					}
					BlockState below = world.getBlockState(cursor.down());
					if (below.isIn(BlockTags.OVERWORLD_NATURAL_LOGS)) {
						continue; // это не основание ствола, а бревно повыше
					}

					double distanceSquared = cursor.getSquaredDistance(origin);
					if (distanceSquared < bestDistanceSquared) {
						bestDistanceSquared = distanceSquared;
						best = cursor.toImmutable();
					}
				}
			}
		}

		return best;
	}

	/** Собирает подряд идущие вверх бревна одного типа, начиная с основания ствола. */
	private static Deque<BlockPos> collectTrunk(ServerWorld world, BlockPos base, Block logType) {
		Deque<BlockPos> logs = new ArrayDeque<>();
		BlockPos.Mutable cursor = base.mutableCopy();
		while (world.getBlockState(cursor).isOf(logType)) {
			logs.addLast(cursor.toImmutable());
			cursor.move(0, 1, 0);
			if (logs.size() > 32) {
				break; // защита от аномально высоких "стволов" (на всякий случай)
			}
		}
		return logs;
	}
}
