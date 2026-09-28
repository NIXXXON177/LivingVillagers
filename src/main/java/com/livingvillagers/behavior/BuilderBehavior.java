package com.livingvillagers.behavior;

import com.livingvillagers.registry.ModBlocks;
import com.livingvillagers.registry.ModProfessions;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Поведение "Строитель чинит и строит дома, а также возводит склад деревни".
 *
 * Архитектурно устроено так же, как {@link LumberjackBehavior}: отдельный
 * самописный конечный автомат (НЕ ванильная Brain-задача), тикающий из
 * общего цикла в LivingVillagersMod. См. подробный комментарий в
 * LumberjackBehavior про известные ограничения такого подхода (возможные
 * мелкие конфликты с ванильным "мозгом" жителя) — здесь всё то же самое.
 *
 * Три вида работы, по приоритету:
 *  1. WAREHOUSE — построить склад деревни (один раз на "деревню"), если его
 *     ещё нет. Склад — деревянное (из брёвен) здание с 4 сундуками, над
 *     каждым висит рамка с предметом-подписью (что должно храниться внутри).
 *  2. REPAIR — почитить повреждения в домах, которые мод один раз
 *     "сфотографировал" (снял снимок всех непустых блоков вокруг каждой
 *     кровати в деревне). Если текущий блок стал воздухом там, где на
 *     снимке был не-воздух — значит блок кто-то/что-то сломало, чиним.
 *     Работает для ЛЮБЫХ домов деревни (в т.ч. сгенерированных игрой),
 *     не только построенных этим модом.
 *  3. NEW_HOUSE — построить новый дом по собственному (не ванильному)
 *     шаблону, если жителей в деревне больше, чем кроватей.
 *
 * УПРОЩЕНИЯ (сознательно, ради реализуемости на этом этапе):
 *  - "Деревня" = условная зона вокруг конкретного builder_post (рабочего
 *     места этого Строителя). Полноценный реестр деревень по колокольному
 *     радиусу — отдельная будущая задача по ТЗ; два поста рядом друг с
 *     другом сейчас считались бы "разными деревнями".
 *  - Материалы для стен/пола/крыши (брёвна) реально забираются из
 *     инвентаря ближайших жителей (см. ResourceKind.ANY_LOG) — как ты и
 *     просил. А вот сундуки, рамки, кровать и факел пока даются "бесплатно":
 *     их пока никто в моде не производит (нет ни шахтёра на камень, ни
 *     овцевода на шерсть для кровати) — как только появится экономика этих
 *     ресурсов, будет что доработать.
 *  - Ремонт домов тоже "бесплатный" — заранее неизвестно, из чего сделан
 *     случайный ванильный дом (камень, доски, стекло...), а экономики под
 *     все эти материалы пока нет.
 *  - Все данные (снимки домов, прогресс стройки) хранятся только в памяти
 *     сервера, не сохраняются на диск — при рестарте сервера прогресс
 *     "деревни" начинается заново (снимок домов переснимается автоматически).
 */
public final class BuilderBehavior {

	private static final Logger DEBUG_LOG = LoggerFactory.getLogger("livingvillagers-builder-debug");

	// ---------- Геометрия построек ----------
	private static final int FOOTPRINT = 5;      // 5x5 в плане (локальные x,z: 0..4)
	private static final int WALL_HEIGHT = 3;    // стены: y = 1..3
	private static final int FLOOR_Y = 0;

	// ---------- Радиусы поиска ----------
	private static final int ANCHOR_SEARCH_RADIUS_H = 32;
	private static final int ANCHOR_SEARCH_RADIUS_V = 8;
	private static final int ANCHOR_SEARCH_RETRY_TICKS = 100;

	private static final int VILLAGE_RADIUS_H = 32;
	private static final int VILLAGE_RADIUS_DOWN = 6;
	private static final int VILLAGE_RADIUS_UP = 16;

	private static final int MATERIAL_SEARCH_RADIUS_H = 48;
	private static final int MATERIAL_SEARCH_RADIUS_V = 16;

	private static final int HOUSE_SNAPSHOT_RADIUS_H = 7;
	private static final int HOUSE_SNAPSHOT_DOWN = 3;
	private static final int HOUSE_SNAPSHOT_UP = 8;

	private static final int[] SITE_SEARCH_RADII = {8, 14, 20, 26, 32};
	private static final int MIN_SITE_SEPARATION = 8;

	// ---------- Тайминги поведения ----------
	private static final int WORK_TICKS_PER_STEP = 10;
	private static final int MAX_MOVE_TICKS = 200;
	private static final int MATERIAL_WAIT_RETRY_TICKS = 40;
	// Если материал (бревно у соседей) не нашёлся за это суммарное время ожидания —
	// бросаем текущую задачу и уходим в IDLE, чтобы не зависать навечно
	// (например, если поблизости совсем нет лесоруба с брёвнами в инвентаре).
	private static final int MAX_MATERIAL_WAIT_TICKS = 20 * 60; // 60 секунд
	private static final int IDLE_RETRY_TICKS = 100;
	private static final int SITE_SEARCH_RETRY_TICKS = 200;
	private static final int REPAIR_SCAN_BATCH = 400;
	// Увеличено с 3.5 (используется и по вертикали тоже): пятиблочная застройка
	// имеет крышу на 4 блока выше пола, а житель ходит только по земле и не
	// забирается на стены — без большого вертикального допуска до крыши
	// физически не дотянуться, и стройка зацикливалась (см. баг, найденный
	// при первом реальном тесте: постройка бесконечно начиналась заново,
	// не успевая положить крышу за 200 тиков движения).
	private static final double REACH_DISTANCE_SQUARED = 8.0 * 8.0;

	private enum Phase { IDLE, MOVING, WORKING }

	private enum TaskType { WAREHOUSE, REPAIR, NEW_HOUSE }

	/** Откуда шаг берёт материал. */
	private enum ResourceKind {
		FREE,             // не требует ресурса (пока не производится в моде)
		ANY_LOG,          // нужно ЛЮБОЕ бревно — забирается у ближайшего жителя и кладётся как есть (брёвна каркаса)
		ANY_LOG_AS_PLANKS // тоже забирается бревно у соседа, но кладётся как доски того же вида дерева (обшивка стен/пол)
	}

	/** true для видов ресурса, которые нужно предварительно "забрать" у соседнего жителя (см. tryTakeLogFromNearbyVillager). */
	private static boolean needsResolvedLog(ResourceKind kind) {
		return kind == ResourceKind.ANY_LOG || kind == ResourceKind.ANY_LOG_AS_PLANKS;
	}

	private static final class BuildStep {
		enum Kind { BLOCK, FRAME }

		final Kind kind;
		final BlockPos pos;
		final BlockState blockState;   // для BLOCK
		final ResourceKind resource;   // для BLOCK
		final Direction frameFacing;   // для FRAME
		final ItemStack frameItem;     // для FRAME

		private BuildStep(Kind kind, BlockPos pos, BlockState blockState, ResourceKind resource,
						   Direction frameFacing, ItemStack frameItem) {
			this.kind = kind;
			this.pos = pos;
			this.blockState = blockState;
			this.resource = resource;
			this.frameFacing = frameFacing;
			this.frameItem = frameItem;
		}

		static BuildStep block(BlockPos pos, BlockState state, ResourceKind resource) {
			return new BuildStep(Kind.BLOCK, pos, state, resource, null, null);
		}

		static BuildStep frame(BlockPos attachTo, Direction facing, ItemStack item) {
			return new BuildStep(Kind.FRAME, attachTo, null, ResourceKind.FREE, facing, item);
		}
	}

	private static final class SnapshotEntry {
		final BlockPos pos;
		final BlockState state;

		SnapshotEntry(BlockPos pos, BlockState state) {
			this.pos = pos;
			this.state = state;
		}
	}

	private static final class HouseRecord {
		final BlockPos bedPos;
		final List<SnapshotEntry> snapshot;
		int scanCursor = 0;

		HouseRecord(BlockPos bedPos, List<SnapshotEntry> snapshot) {
			this.bedPos = bedPos;
			this.snapshot = snapshot;
		}
	}

	private static final class VillageProgress {
		boolean housesCaptured = false;
		final List<HouseRecord> houses = new ArrayList<>();
		int houseCursor = 0;

		boolean warehouseBuilt = false;
		boolean warehouseInProgress = false;
		BlockPos warehouseOrigin;

		boolean newHouseInProgress = false;
		BlockPos newHouseOrigin;
	}

	private static final class BuilderState {
		BlockPos anchor;
		int anchorSearchCooldown = 0;

		Phase phase = Phase.IDLE;
		TaskType task;
		Deque<BuildStep> steps = new ArrayDeque<>();
		BuildStep currentStep;
		BlockState resolvedBlockState; // для ANY_LOG: какой именно блок реально кладём

		int timer;
		int idleCooldown;
		int materialWaitCooldown;
		int materialWaitTotalTicks; // сколько всего тиков ждём материал на этом шаге — чтобы не зависать навечно
	}

	private static final Map<UUID, BuilderState> STATES = new HashMap<>();
	private static final Map<BlockPos, VillageProgress> VILLAGES = new HashMap<>();

	private BuilderBehavior() {
	}

	public static void tick(ServerWorld world, VillagerEntity villager) {
		if (villager.getVillagerData().getProfession() != ModProfessions.BUILDER) {
			STATES.remove(villager.getUuid());
			return;
		}

		BuilderState state = STATES.computeIfAbsent(villager.getUuid(), id -> new BuilderState());

		if (state.anchor == null) {
			if (state.anchorSearchCooldown > 0) {
				state.anchorSearchCooldown--;
				return;
			}
			state.anchor = findNearbyBuilderPost(world, villager.getBlockPos());
			if (state.anchor == null) {
				state.anchorSearchCooldown = ANCHOR_SEARCH_RETRY_TICKS;
				return;
			}
		}

		VillageProgress progress = VILLAGES.computeIfAbsent(state.anchor, p -> new VillageProgress());

		switch (state.phase) {
			case IDLE -> tickIdle(world, villager, state, progress);
			case MOVING -> tickMoving(world, villager, state, progress);
			case WORKING -> tickWorking(world, villager, state, progress);
		}
	}

	public static void forget(UUID villagerId) {
		STATES.remove(villagerId);
	}

	// ==================== IDLE: выбор следующей задачи ====================

	private static void tickIdle(ServerWorld world, VillagerEntity villager, BuilderState state, VillageProgress progress) {
		if (state.idleCooldown > 0) {
			state.idleCooldown--;
			return;
		}

		if (!progress.housesCaptured) {
			captureVillageHouses(world, state.anchor, progress);
			progress.housesCaptured = true;
		}

		// 1. Склад — приоритет №1: без него ресурсы девать некуда.
		if (!progress.warehouseBuilt && !progress.warehouseInProgress) {
			BlockPos origin = findBuildSite(world, state.anchor, progress);
			if (origin != null) {
				DEBUG_LOG.info("[LV-DEBUG] Найдено место под склад: {} (anchor={})", origin, state.anchor);
				progress.warehouseInProgress = true;
				progress.warehouseOrigin = origin;
				state.task = TaskType.WAREHOUSE;
				state.steps = buildWarehouseSteps(origin);
				DEBUG_LOG.info("[LV-DEBUG] Шагов в очереди на склад: {}", state.steps.size());
				advanceToNextStep(world, villager, state, progress);
				return;
			}
			DEBUG_LOG.info("[LV-DEBUG] Место под склад НЕ найдено (anchor={})", state.anchor);
			state.idleCooldown = SITE_SEARCH_RETRY_TICKS;
			return;
		}

		// 2. Ремонт повреждённых домов (любых, не только построенных модом).
		SnapshotEntry damaged = findNextDamagedBlock(world, progress);
		if (damaged != null) {
			state.task = TaskType.REPAIR;
			state.steps = new ArrayDeque<>();
			state.steps.addLast(BuildStep.block(damaged.pos, damaged.state, ResourceKind.FREE));
			advanceToNextStep(world, villager, state, progress);
			return;
		}

		// 3. Новый дом — если жителей больше, чем кроватей в деревне.
		if (!progress.newHouseInProgress && needsNewHouse(world, state.anchor, progress)) {
			BlockPos origin = findBuildSite(world, state.anchor, progress);
			if (origin != null) {
				progress.newHouseInProgress = true;
				progress.newHouseOrigin = origin;
				state.task = TaskType.NEW_HOUSE;
				state.steps = buildHouseSteps(origin);
				advanceToNextStep(world, villager, state, progress);
				return;
			}
		}

		// Делать нечего — отдыхаем.
		state.idleCooldown = IDLE_RETRY_TICKS;
	}

	private static boolean needsNewHouse(ServerWorld world, BlockPos anchor, VillageProgress progress) {
		int population = countPopulation(world, anchor);
		return population > progress.houses.size();
	}

	private static int countPopulation(ServerWorld world, BlockPos anchor) {
		Box box = boxAround(anchor, VILLAGE_RADIUS_H, VILLAGE_RADIUS_DOWN, VILLAGE_RADIUS_UP);
		return world.getEntitiesByType(TypeFilter.instanceOf(VillagerEntity.class), box, v -> true).size();
	}

	// ==================== MOVING / WORKING ====================

	private static void advanceToNextStep(ServerWorld world, VillagerEntity villager, BuilderState state, VillageProgress progress) {
		if (state.steps.isEmpty()) {
			finishTask(world, state, progress);
			state.phase = Phase.IDLE;
			state.idleCooldown = IDLE_RETRY_TICKS;
			return;
		}
		state.currentStep = state.steps.pollFirst();
		state.phase = Phase.MOVING;
		state.timer = 0;
		state.materialWaitTotalTicks = 0;
		state.materialWaitCooldown = 0;
	}

	private static void finishTask(ServerWorld world, BuilderState state, VillageProgress progress) {
		switch (state.task) {
			case WAREHOUSE -> {
				progress.warehouseBuilt = true;
				progress.warehouseInProgress = false;
			}
			case NEW_HOUSE -> {
				progress.newHouseInProgress = false;
				BlockPos bedPos = progress.newHouseOrigin.add(1, 1, 2); // голова кровати из шаблона
				progress.houses.add(captureHouseSnapshot(world, bedPos));
			}
			case REPAIR -> {
				// ничего дополнительно делать не нужно
			}
		}
	}

	private static void abandonCurrentTask(BuilderState state, VillageProgress progress) {
		if (state.task == TaskType.WAREHOUSE) {
			progress.warehouseInProgress = false;
		} else if (state.task == TaskType.NEW_HOUSE) {
			progress.newHouseInProgress = false;
		}
		state.steps.clear();
		state.currentStep = null;
		state.phase = Phase.IDLE;
		state.idleCooldown = SITE_SEARCH_RETRY_TICKS;
		state.materialWaitTotalTicks = 0;
		state.materialWaitCooldown = 0;
	}

	private static void tickMoving(ServerWorld world, VillagerEntity villager, BuilderState state, VillageProgress progress) {
		BlockPos target = state.currentStep.pos;

		villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
		villager.getBrain().forget(MemoryModuleType.LOOK_TARGET);

		double distanceSquared = villager.squaredDistanceTo(Vec3d.ofCenter(target));
		if (distanceSquared <= REACH_DISTANCE_SQUARED) {
			villager.getNavigation().stop();
			villager.getLookControl().lookAt(Vec3d.ofCenter(target));
			state.phase = Phase.WORKING;
			state.timer = WORK_TICKS_PER_STEP;
			state.materialWaitCooldown = 0;
			return;
		}

		state.timer++;
		if (state.timer > MAX_MOVE_TICKS) {
			abandonCurrentTask(state, progress);
			return;
		}

		if (villager.getNavigation().isIdle()) {
			boolean started = villager.getNavigation().startMovingTo(
					target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.5D);
			if (!started) {
				abandonCurrentTask(state, progress);
			}
		}
	}

	private static void tickWorking(ServerWorld world, VillagerEntity villager, BuilderState state, VillageProgress progress) {
		BuildStep step = state.currentStep;

		if (step.kind == BuildStep.Kind.BLOCK && needsResolvedLog(step.resource) && state.resolvedBlockState == null) {
			if (state.materialWaitCooldown > 0) {
				state.materialWaitCooldown--;
				state.materialWaitTotalTicks++;
				if (state.materialWaitTotalTicks > MAX_MATERIAL_WAIT_TICKS) {
					// Материала так и не нашлось — не зависаем навечно, откладываем задачу.
					abandonCurrentTask(state, progress);
				}
				return;
			}
			BlockState resolved = tryTakeLogFromNearbyVillager(world, state.anchor);
			if (resolved == null) {
				state.materialWaitCooldown = MATERIAL_WAIT_RETRY_TICKS;
				state.materialWaitTotalTicks += MATERIAL_WAIT_RETRY_TICKS;
				if (state.materialWaitTotalTicks > MAX_MATERIAL_WAIT_TICKS) {
					abandonCurrentTask(state, progress);
				}
				return;
			}
			state.resolvedBlockState = resolved;
			state.materialWaitTotalTicks = 0;
		}

		villager.getBrain().forget(MemoryModuleType.WALK_TARGET);

		if (state.timer % 5 == 0) {
			villager.swingHand(Hand.MAIN_HAND);
		}

		state.timer--;
		if (state.timer > 0) {
			return;
		}

		applyStep(world, state);
		advanceToNextStep(world, villager, state, progress);
	}

	private static void applyStep(ServerWorld world, BuilderState state) {
		BuildStep step = state.currentStep;

		if (step.kind == BuildStep.Kind.FRAME) {
			BlockPos framePos = step.pos.offset(step.frameFacing);
			ItemFrameEntity frame = new ItemFrameEntity(world, framePos, step.frameFacing);
			frame.setHeldItemStack(step.frameItem.copy());
			boolean spawned = world.spawnEntity(frame);
			DEBUG_LOG.info("[LV-DEBUG] frame wallPos={} framePos={} facing={} item={} spawned={}",
					step.pos, framePos, step.frameFacing, step.frameItem, spawned);
			return;
		}

		BlockState toPlace;
		if (step.resource == ResourceKind.ANY_LOG && state.resolvedBlockState != null) {
			toPlace = state.resolvedBlockState;
		} else if (step.resource == ResourceKind.ANY_LOG_AS_PLANKS && state.resolvedBlockState != null) {
			toPlace = logToPlanks(state.resolvedBlockState);
		} else {
			toPlace = step.blockState;
		}

		world.setBlockState(step.pos, toPlace);
		if (!toPlace.isAir()) {
			world.playSound(null, step.pos, toPlace.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 1.0f);
		}
		state.resolvedBlockState = null;
	}

	// ==================== Материалы ====================

	/**
	 * Превращает взятое у соседа бревно (любого вида: oak_log, stripped_birch_log,
	 * crimson_stem и т.д.) в доски того же вида дерева — для обшивки стен и пола,
	 * чтобы дом не выглядел сплошным бревенчатым коробом.
	 */
	private static BlockState logToPlanks(BlockState logState) {
		Identifier logId = Registries.BLOCK.getId(logState.getBlock());
		String path = logId.getPath();
		if (path.startsWith("stripped_")) {
			path = path.substring("stripped_".length());
		}
		String species = path.replace("_log", "").replace("_wood", "")
				.replace("_stem", "").replace("_hyphae", "");
		Identifier planksId = Identifier.of(logId.getNamespace(), species + "_planks");
		Block planks = Registries.BLOCK.get(planksId);
		return planks.getDefaultState();
	}

	/** Ищет ближайшего (по деревне) жителя с бревном в инвентаре, забирает 1 штуку. */
	private static BlockState tryTakeLogFromNearbyVillager(ServerWorld world, BlockPos anchor) {
		Box box = boxAround(anchor, MATERIAL_SEARCH_RADIUS_H, MATERIAL_SEARCH_RADIUS_V, MATERIAL_SEARCH_RADIUS_V);
		List<VillagerEntity> nearby = world.getEntitiesByType(TypeFilter.instanceOf(VillagerEntity.class), box, v -> true);

		for (VillagerEntity source : nearby) {
			SimpleInventory inventory = source.getInventory();
			for (int slot = 0; slot < inventory.size(); slot++) {
				ItemStack stack = inventory.getStack(slot);
				if (stack.isEmpty()) {
					continue;
				}
				Item item = stack.getItem();
				if (!(item instanceof BlockItem blockItem)) {
					continue;
				}
				Block block = blockItem.getBlock();
				if (!block.getDefaultState().isIn(BlockTags.LOGS)) {
					continue;
				}
				inventory.removeStack(slot, 1);
				return block.getDefaultState();
			}
		}
		return null;
	}

	// ==================== Поиск рабочего места (анкера "деревни") ====================

	private static BlockPos findNearbyBuilderPost(ServerWorld world, BlockPos from) {
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int dx = -ANCHOR_SEARCH_RADIUS_H; dx <= ANCHOR_SEARCH_RADIUS_H; dx++) {
			for (int dz = -ANCHOR_SEARCH_RADIUS_H; dz <= ANCHOR_SEARCH_RADIUS_H; dz++) {
				for (int dy = -ANCHOR_SEARCH_RADIUS_V; dy <= ANCHOR_SEARCH_RADIUS_V; dy++) {
					cursor.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
					if (world.getBlockState(cursor).isOf(ModBlocks.BUILDER_POST)) {
						return cursor.toImmutable();
					}
				}
			}
		}
		return null;
	}

	// ==================== Снимки домов и ремонт ====================

	private static void captureVillageHouses(ServerWorld world, BlockPos anchor, VillageProgress progress) {
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int dx = -VILLAGE_RADIUS_H; dx <= VILLAGE_RADIUS_H; dx++) {
			for (int dz = -VILLAGE_RADIUS_H; dz <= VILLAGE_RADIUS_H; dz++) {
				for (int dy = -VILLAGE_RADIUS_DOWN; dy <= VILLAGE_RADIUS_UP; dy++) {
					cursor.set(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz);
					BlockState state = world.getBlockState(cursor);
					if (state.isIn(BlockTags.BEDS) && state.get(BedBlock.PART) == BedPart.HEAD) {
						progress.houses.add(captureHouseSnapshot(world, cursor.toImmutable()));
					}
				}
			}
		}
	}

	private static HouseRecord captureHouseSnapshot(ServerWorld world, BlockPos bedPos) {
		List<SnapshotEntry> snapshot = new ArrayList<>();
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int dx = -HOUSE_SNAPSHOT_RADIUS_H; dx <= HOUSE_SNAPSHOT_RADIUS_H; dx++) {
			for (int dz = -HOUSE_SNAPSHOT_RADIUS_H; dz <= HOUSE_SNAPSHOT_RADIUS_H; dz++) {
				for (int dy = -HOUSE_SNAPSHOT_DOWN; dy <= HOUSE_SNAPSHOT_UP; dy++) {
					cursor.set(bedPos.getX() + dx, bedPos.getY() + dy, bedPos.getZ() + dz);
					BlockState state = world.getBlockState(cursor);
					if (!state.isAir()) {
						snapshot.add(new SnapshotEntry(cursor.toImmutable(), state));
					}
				}
			}
		}
		return new HouseRecord(bedPos, snapshot);
	}

	/** Сканирует известные дома порциями (не больше REPAIR_SCAN_BATCH за вызов), возвращает первое найденное повреждение. */
	private static SnapshotEntry findNextDamagedBlock(ServerWorld world, VillageProgress progress) {
		if (progress.houses.isEmpty()) {
			return null;
		}

		int housesVisited = 0;
		while (housesVisited < progress.houses.size()) {
			HouseRecord house = progress.houses.get(progress.houseCursor);
			int checked = 0;
			while (checked < REPAIR_SCAN_BATCH && house.scanCursor < house.snapshot.size()) {
				SnapshotEntry entry = house.snapshot.get(house.scanCursor);
				house.scanCursor++;
				checked++;
				BlockState current = world.getBlockState(entry.pos);
				if (current.isAir() && !entry.state.isAir()) {
					return entry;
				}
			}
			if (house.scanCursor >= house.snapshot.size()) {
				house.scanCursor = 0;
				progress.houseCursor = (progress.houseCursor + 1) % progress.houses.size();
				housesVisited++;
			} else {
				break; // упёрлись в лимит батча — продолжим с этого места в следующий раз
			}
		}
		return null;
	}

	// ==================== Поиск места под новую постройку ====================

	private static BlockPos findBuildSite(ServerWorld world, BlockPos anchor, VillageProgress progress) {
		for (int radius : SITE_SEARCH_RADII) {
			for (int i = 0; i < 8; i++) {
				double angle = i * (Math.PI * 2 / 8);
				int dx = (int) Math.round(Math.cos(angle) * radius);
				int dz = (int) Math.round(Math.sin(angle) * radius);
				BlockPos roughCenter = anchor.add(dx, 0, dz);
				BlockPos corner = findFlatGroundCorner(world, roughCenter);
				if (corner != null && !overlapsExisting(corner, progress)) {
					return corner;
				}
			}
		}
		return null;
	}

	private static BlockPos findFlatGroundCorner(ServerWorld world, BlockPos roughCenter) {
		int half = FOOTPRINT / 2;
		int cornerX = roughCenter.getX() - half;
		int cornerZ = roughCenter.getZ() - half;

		int h0 = surfaceHeight(world, cornerX, cornerZ);
		int h1 = surfaceHeight(world, cornerX + FOOTPRINT - 1, cornerZ);
		int h2 = surfaceHeight(world, cornerX, cornerZ + FOOTPRINT - 1);
		int h3 = surfaceHeight(world, cornerX + FOOTPRINT - 1, cornerZ + FOOTPRINT - 1);

		int min = Math.min(Math.min(h0, h1), Math.min(h2, h3));
		int max = Math.max(Math.max(h0, h1), Math.max(h2, h3));
		if (max - min > 2) {
			return null; // слишком неровно для нашего простого шаблона
		}
		return new BlockPos(cornerX, min, cornerZ);
	}

	private static int surfaceHeight(ServerWorld world, int x, int z) {
		BlockPos.Mutable cursor = new BlockPos.Mutable(x, world.getTopY() - 1, z);
		while (cursor.getY() > world.getBottomY() && world.getBlockState(cursor).isAir()) {
			cursor.move(Direction.DOWN);
		}
		return cursor.getY() + 1;
	}

	private static boolean overlapsExisting(BlockPos corner, VillageProgress progress) {
		BlockPos center = corner.add(FOOTPRINT / 2, 0, FOOTPRINT / 2);
		long minDistSquared = (long) MIN_SITE_SEPARATION * MIN_SITE_SEPARATION;

		if (progress.warehouseOrigin != null) {
			BlockPos warehouseCenter = progress.warehouseOrigin.add(FOOTPRINT / 2, 0, FOOTPRINT / 2);
			if (horizontalDistanceSquared(center, warehouseCenter) < minDistSquared) {
				return true;
			}
		}
		for (HouseRecord house : progress.houses) {
			if (horizontalDistanceSquared(center, house.bedPos) < minDistSquared) {
				return true;
			}
		}
		return false;
	}

	private static long horizontalDistanceSquared(BlockPos a, BlockPos b) {
		long dx = a.getX() - b.getX();
		long dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	private static Box boxAround(BlockPos center, int radiusH, int down, int up) {
		return Box.enclosing(
				center.add(-radiusH, -down, -radiusH),
				center.add(radiusH, up, radiusH));
	}

	// ==================== Шаблоны построек ====================

	private static Deque<BuildStep> buildWarehouseSteps(BlockPos origin) {
		Deque<BuildStep> steps = new ArrayDeque<>();
		addFloorWallsAndRoof(steps, origin);

		addChestWithFrame(steps, origin, 1, 1, 1, Direction.EAST, new ItemStack(Items.OAK_LOG));
		addChestWithFrame(steps, origin, 3, 1, 1, Direction.WEST, new ItemStack(Items.COBBLESTONE));
		addChestWithFrame(steps, origin, 1, 1, 3, Direction.EAST, new ItemStack(Items.WHEAT));
		addChestWithFrame(steps, origin, 3, 1, 3, Direction.WEST, new ItemStack(Items.IRON_INGOT));

		return steps;
	}

	private static Deque<BuildStep> buildHouseSteps(BlockPos origin) {
		Deque<BuildStep> steps = new ArrayDeque<>();
		addFloorWallsAndRoof(steps, origin);

		// Кровать — бесплатно (в экономике мода пока нет шерсти/Овцевода).
		BlockPos footPos = origin.add(1, 1, 1);
		BlockPos headPos = origin.add(1, 1, 2);
		BlockState bedFoot = Blocks.RED_BED.getDefaultState()
				.with(HorizontalFacingBlock.FACING, Direction.SOUTH)
				.with(BedBlock.PART, BedPart.FOOT);
		BlockState bedHead = Blocks.RED_BED.getDefaultState()
				.with(HorizontalFacingBlock.FACING, Direction.SOUTH)
				.with(BedBlock.PART, BedPart.HEAD);
		steps.addLast(BuildStep.block(footPos, bedFoot, ResourceKind.FREE));
		steps.addLast(BuildStep.block(headPos, bedHead, ResourceKind.FREE));

		// Факел для уюта — тоже бесплатно, ставим на пол у стены.
		steps.addLast(BuildStep.block(origin.add(3, 1, 3), Blocks.TORCH.getDefaultState(), ResourceKind.FREE));

		return steps;
	}

	/**
	 * Дом/склад 5x5: пол и обшивка стен — доски (из забранного бревна),
	 * угловые столбы — цельные брёвна (каркас), одна настоящая дверь,
	 * три окна из стекла и двускатная крыша со стропилами (см. addGableRoof).
	 * Раньше это был сплошной бревенчатый короб без окон и с плоской
	 * крышей — по замечанию игрока переделано на нормальный дом.
	 */
	private static void addFloorWallsAndRoof(Deque<BuildStep> steps, BlockPos origin) {
		BlockState logPlaceholder = Blocks.OAK_LOG.getDefaultState();     // угловые столбы каркаса
		BlockState plankPlaceholder = Blocks.OAK_PLANKS.getDefaultState(); // обшивка (вид уточнится по факту забора бревна)

		// Пол — доски.
		for (int x = 0; x < FOOTPRINT; x++) {
			for (int z = 0; z < FOOTPRINT; z++) {
				steps.addLast(BuildStep.block(origin.add(x, FLOOR_Y, z), plankPlaceholder, ResourceKind.ANY_LOG_AS_PLANKS));
			}
		}

		int lastIdx = FOOTPRINT - 1;
		int doorX = FOOTPRINT / 2;
		int windowZ = FOOTPRINT / 2;

		for (int y = 1; y <= WALL_HEIGHT; y++) {
			for (int x = 0; x < FOOTPRINT; x++) {
				for (int z = 0; z < FOOTPRINT; z++) {
					boolean perimeter = (x == 0 || x == lastIdx || z == 0 || z == lastIdx);
					if (!perimeter) {
						continue;
					}

					boolean corner = (x == 0 || x == lastIdx) && (z == 0 || z == lastIdx);
					boolean doorway = (x == doorX && z == 0 && y <= 2);
					boolean sideWindow = (y == 2) && (z == windowZ) && (x == 0 || x == lastIdx);
					boolean backWindow = (y == 2) && (x == doorX) && (z == lastIdx);

					BlockPos pos = origin.add(x, y, z);

					if (doorway) {
						BlockState doorState = Blocks.OAK_DOOR.getDefaultState()
								.with(DoorBlock.FACING, Direction.SOUTH)
								.with(DoorBlock.HINGE, DoorHinge.LEFT)
								.with(DoorBlock.OPEN, false)
								.with(DoorBlock.POWERED, false)
								.with(DoorBlock.HALF, y == 1 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
						steps.addLast(BuildStep.block(pos, doorState, ResourceKind.FREE));
					} else if (sideWindow || backWindow) {
						steps.addLast(BuildStep.block(pos, Blocks.GLASS.getDefaultState(), ResourceKind.FREE));
					} else if (corner) {
						steps.addLast(BuildStep.block(pos, logPlaceholder, ResourceKind.ANY_LOG));
					} else {
						steps.addLast(BuildStep.block(pos, plankPlaceholder, ResourceKind.ANY_LOG_AS_PLANKS));
					}
				}
			}
		}

		addGableRoof(steps, origin);
	}

	/**
	 * Двускатная крыша: конёк вдоль оси X по центру (z = FOOTPRINT/2),
	 * скаты из лестниц спускаются к переднему (z=0) и заднему (z=FOOTPRINT-1)
	 * карнизам. Торцы (x=0 и x=FOOTPRINT-1) зашиваются треугольным фронтоном
	 * из досок, чтобы под крышей не было дыр на чердак.
	 */
	private static void addGableRoof(Deque<BuildStep> steps, BlockPos origin) {
		BlockState plankPlaceholder = Blocks.OAK_PLANKS.getDefaultState();
		int lastIdx = FOOTPRINT - 1;
		int ridgeZ = FOOTPRINT / 2;
		int wallTopY = WALL_HEIGHT;

		for (int z = 0; z < FOOTPRINT; z++) {
			int distance = Math.abs(z - ridgeZ);
			int roofY = wallTopY + 1 + (ridgeZ - distance); // чем ближе к коньку, тем выше

			for (int x = 0; x < FOOTPRINT; x++) {
				BlockPos roofPos = origin.add(x, roofY, z);
				if (distance == 0) {
					steps.addLast(BuildStep.block(roofPos, plankPlaceholder, ResourceKind.FREE)); // конёк
				} else {
					Direction facing = (z < ridgeZ) ? Direction.SOUTH : Direction.NORTH;
					BlockState stairs = Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, facing);
					steps.addLast(BuildStep.block(roofPos, stairs, ResourceKind.FREE));
				}

				if (x == 0 || x == lastIdx) {
					for (int y = wallTopY + 1; y < roofY; y++) {
						steps.addLast(BuildStep.block(origin.add(x, y, z), plankPlaceholder, ResourceKind.FREE));
					}
				}
			}
		}
	}

	private static void addChestWithFrame(Deque<BuildStep> steps, BlockPos origin, int x, int y, int z,
										   Direction facing, ItemStack displayItem) {
		BlockPos chestPos = origin.add(x, y, z);
		BlockState chestState = Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, facing);
		steps.addLast(BuildStep.block(chestPos, chestState, ResourceKind.FREE));

		// Рамку крепим на стену на клетку выше сундука (та же стена, y+1),
		// иначе точка крепления рамки (wallPos.offset(facing)) совпадает
		// с позицией самого сундука и рамка "прячется" внутри него.
		BlockPos wallPos = chestPos.up().offset(facing.getOpposite());
		steps.addLast(BuildStep.frame(wallPos, facing, displayItem));
	}
}
