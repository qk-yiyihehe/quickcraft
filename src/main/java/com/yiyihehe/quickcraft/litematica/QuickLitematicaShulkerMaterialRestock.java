package com.yiyihehe.quickcraft.litematica;

import com.yiyihehe.quickcraft.config.QuickCraftConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.MiningToolItem;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.StringJoiner;

/**
 * 在 Litematica 轻松放置缺少材料时，通过 Quick Shulker 取出背包内潜影盒中的材料。
 */
public final class QuickLitematicaShulkerMaterialRestock implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuickLitematicaShulkerMaterialRestock.class);
    private static final String LOG_PREFIX = "[投影潜影盒补料]";
    private static final int OPEN_TIMEOUT_TICKS = 40;
    private static final int CLOSE_TIMEOUT_TICKS = 20;
    private static final int CLOSE_CURSOR_RETRIES = 10;
    private static final Identifier QUICK_SHULKER_OPEN_PACKET = Identifier.of("quickshulker", "open_shulker_packet");

    private static final Deque<RetrievedMaterial> retrievedMaterials = new ArrayDeque<>();
    private static PendingRequest pendingRequest;
    private static ActiveAction activeAction;
    private static Operation operation = Operation.IDLE;
    private static int operationTicks;
    private static int closeCursorRetries;
    private static int restockCooldownTicks;
    private static boolean closeSent;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(QuickLitematicaShulkerMaterialRestock::onClientTick);
    }

    /**
     * @return true 表示已经接管本次缺料选取，调用方不应继续尝试普通换手。
     */
    public static boolean requestMissingMaterial(ItemStack requiredStack) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!canHandleRequest(client, requiredStack)) {
            return false;
        }

        if (hasPlayerMaterial(client.player.getInventory(), requiredStack)) {
            return false;
        }

        if (operation != Operation.IDLE || pendingRequest != null) {
            LOGGER.info("{} 忽略重复请求：需求={}，当前操作={}，待处理={}",
                    LOG_PREFIX, describeStack(requiredStack), operation, pendingRequest != null);
            return true;
        }

        PlayerInventory inventory = client.player.getInventory();
        LOGGER.info(
                "{} 收到缺料请求：需求={}，主手={}，选中快捷栏={}，快捷栏={}，背包空格={}/36，背包已满={}，有序存放={}，潜影盒={}",
                LOG_PREFIX,
                describeStack(requiredStack),
                describeStack(client.player.getMainHandStack()),
                inventory.selectedSlot,
                describeHotbar(inventory),
                countEmptyPlayerSlots(inventory),
                isInventoryFull(inventory),
                QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled(),
                describeShulkers(inventory, requiredStack)
        );
        if (restockCooldownTicks > 0) {
            boolean materialInShulker = findShulkerWithMaterial(inventory, requiredStack) != -1;
            LOGGER.info("{} 操作冷却剩余 {} tick，需求盒命中={}，本次不发开箱包",
                    LOG_PREFIX, restockCooldownTicks, materialInShulker);
            return materialInShulker;
        }

        pendingRequest = new PendingRequest(copyTemplate(requiredStack));
        if (startPendingRequest(client)) {
            return true;
        }

        boolean materialIsInShulker = findShulkerWithMaterial(inventory, pendingRequest.template()) != -1;
        LOGGER.warn("{} 未能启动补料：需求={}，潜影盒命中={}，背包空格={}，有序存放={}",
                LOG_PREFIX,
                describeStack(pendingRequest.template()),
                materialIsInShulker,
                countEmptyPlayerSlots(inventory),
                QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled());
        pendingRequest = null;
        return materialIsInShulker;
    }

    /**
     * 处理 Litematica 在真正执行 easy place 前的放置限制检查。
     * 此时主手可能为空，不能依赖 Litematica 后续的 pick block 回调来发现缺料。
     */
    public static boolean requestMaterialForEasyPlaceTarget(MinecraftClient client) {
        if (!QuickCraftConfigs.isLitematicaShulkerMaterialRestockWithQuickShulkerEnabled()
                || client == null
                || client.player == null
                || client.world == null
                || !(client.crosshairTarget instanceof BlockHitResult hitResult)
                || hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        ItemPlacementContext placementContext = new ItemPlacementContext(
                new ItemUsageContext(client.player, Hand.MAIN_HAND, hitResult)
        );
        BlockPos position = placementContext.getBlockPos();
        World schematicWorld = SchematicWorldHandler.getSchematicWorld();
        BlockState schematicState = schematicWorld.getBlockState(position);

        if (schematicState.isAir()
                || !DataManager.getRenderLayerRange().isPositionWithinRange(position)
                || !client.world.getBlockState(position).canReplace(placementContext)) {
            return false;
        }

        ItemStack requiredStack = MaterialCache.getInstance().getRequiredBuildItemForState(schematicState);
        LOGGER.info("{} 轻松放置目标：位置={}，投影状态={}，需求={}",
                LOG_PREFIX, position, schematicState, describeStack(requiredStack));
        return requestMissingMaterial(requiredStack);
    }

    private static void onClientTick(MinecraftClient client) {
        if (!QuickCraftConfigs.isLitematicaShulkerMaterialRestockEnabled()
                || client.player == null
                || client.world == null) {
            resetState(client);
            return;
        }

        if (restockCooldownTicks > 0) {
            restockCooldownTicks--;
        }

        switch (operation) {
            case IDLE -> {
                if (pendingRequest != null
                        && client.currentScreen == null
                        && !startPendingRequest(client)) {
                    pendingRequest = null;
                }
            }
            case WAITING_FOR_OPEN -> waitForOpenedShulker(client);
            case WAITING_FOR_CLOSE -> processClosingShulker(client);
        }
    }

    /**
     * Quick Shulker 打开的是后台 ScreenHandler；容器内容由网络包到达后再执行取放。
     */
    public static void onShulkerContentsReceived(int syncId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (operation != Operation.WAITING_FOR_OPEN
                || activeAction == null
                || client.player == null
                || client.currentScreen != null
                || !(client.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler)
                || handler.syncId != syncId) {
            if (operation == Operation.WAITING_FOR_OPEN && activeAction != null) {
                LOGGER.warn("{} 忽略潜影盒内容回调：期望操作={}、期望同步 ID={}，收到同步 ID={}，当前界面={}，当前处理器={}",
                        LOG_PREFIX,
                        activeAction.type(),
                        activeAction.syncId(),
                        syncId,
                        client.currentScreen == null ? "后台" : client.currentScreen.getClass().getSimpleName(),
                        client.player == null ? "无玩家" : client.player.currentScreenHandler.getClass().getSimpleName());
            }
            return;
        }

        LOGGER.info("{} 收到潜影盒内容回调：操作={}，同步 ID={}，需求={}，潜影盒占用={}",
                LOG_PREFIX,
                activeAction.type(),
                syncId,
                pendingRequest == null ? "无" : describeStack(pendingRequest.template()),
                getStoredStacksFromHandler(handler));
        operationTicks = 0;
        switch (activeAction.type()) {
            case EXTRACT -> extractMaterial(client, handler, activeAction);
            case REPLACE -> replaceHotbarMaterial(client, handler, activeAction);
            case STASH -> stashHotbarMaterial(client, handler, activeAction);
            case STASH_AND_EXTRACT -> stashHotbarAndExtractMaterial(client, handler, activeAction);
            case RETURN -> returnMaterial(client, handler, activeAction.material());
        }
        beginClosing(handler);
    }

    /**
     * 保留 Quick Shulker 的后台容器，避免自动补料时把潜影盒界面切到前台。
     */
    public static boolean shouldSuppressShulkerScreenOpen() {
        return operation == Operation.WAITING_FOR_OPEN && activeAction != null;
    }

    private static boolean canHandleRequest(MinecraftClient client, ItemStack requiredStack) {
        if (!QuickCraftConfigs.isLitematicaShulkerMaterialRestockEnabled()) {
            return false;
        }
        if (!QuickCraftConfigs.isLitematicaShulkerMaterialRestockWithQuickShulkerEnabled()) {
            LOGGER.info("{} 不接管缺料请求：Quick Shulker 联动开关关闭", LOG_PREFIX);
            return false;
        }
        if (client == null || client.player == null || client.world == null) {
            LOGGER.warn("{} 不接管缺料请求：客户端、玩家或世界不可用", LOG_PREFIX);
            return false;
        }
        if (client.player.isCreative()) {
            LOGGER.info("{} 不接管缺料请求：创造模式无需补料", LOG_PREFIX);
            return false;
        }
        if (requiredStack == null || requiredStack.isEmpty()) {
            LOGGER.warn("{} 不接管缺料请求：投影未解析出有效需求材料", LOG_PREFIX);
            return false;
        }
        if (!canUseQuickShulker()) {
            LOGGER.warn("{} 不接管缺料请求：Quick Shulker 未加载或服务端未声明开箱协议", LOG_PREFIX);
            return false;
        }
        return true;
    }

    private static boolean startPendingRequest(MinecraftClient client) {
        if (pendingRequest == null || client.player == null || client.currentScreen != null) {
            return false;
        }

        PlayerInventory inventory = client.player.getInventory();
        if (hasPlayerMaterial(inventory, pendingRequest.template())) {
            LOGGER.info("{} 待补材料 {} 已进入背包，取消开箱请求", LOG_PREFIX, describeStack(pendingRequest.template()));
            pendingRequest = null;
            return true;
        }

        if (isInventoryFull(inventory)) {
            LOGGER.info("{} 背包满，开始决策：需求={}，已跟踪待回存={}，有序存放={}",
                    LOG_PREFIX,
                    describeStack(pendingRequest.template()),
                    describeRetrievedMaterials(),
                    QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled());

            int sourceShulkerSlot = findShulkerWithMaterial(inventory, pendingRequest.template());
            int targetHotbarSlot = findLitematicaPickBlockTarget(inventory);
            if (sourceShulkerSlot == -1 || targetHotbarSlot == -1) {
                LOGGER.warn("{} 满背包不换货：来源盒槽={}，可替换快捷栏槽={}，需求={}。新材料不可用，保留当前主手物品。",
                        LOG_PREFIX, sourceShulkerSlot, targetHotbarSlot, describeStack(pendingRequest.template()));
                return false;
            }

            RetrievedMaterial returnCandidate = getReturnCandidate(inventory);
            if (QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled() && returnCandidate != null) {
                int returnShulkerSlot = findReturnShulker(inventory, returnCandidate);
                if (returnShulkerSlot != -1) {
                    LOGGER.info("{} 满背包先回存：材料={}，目标盒背包槽={}，来源记录={}",
                            LOG_PREFIX,
                            Registries.ITEM.getId(returnCandidate.item()),
                            returnShulkerSlot,
                            describeTrackedShulker(returnCandidate.source()));
                    return openShulker(client, returnShulkerSlot, ActionType.RETURN, returnCandidate, -1);
                }

                LOGGER.warn("{} 有序回存取消：材料={} 的来源盒不可用或没有空位，禁止混塞",
                        LOG_PREFIX, Registries.ITEM.getId(returnCandidate.item()));
                return false;
            }

            TrackedShulker source = TrackedShulker.from(inventory.getStack(sourceShulkerSlot), sourceShulkerSlot);
            if (QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled()) {
                RetrievedMaterial retrievedMaterial = new RetrievedMaterial(pendingRequest.template().getItem(), source);
                if (hasEmptyContainerSlot(inventory.getStack(sourceShulkerSlot))) {
                    LOGGER.info("{} 有序满背包换入：来源盒槽={} 有空位，先存快捷栏 {} 的 {}，再取 {}",
                            LOG_PREFIX,
                            sourceShulkerSlot,
                            targetHotbarSlot,
                            describeStack(inventory.getStack(targetHotbarSlot)),
                            describeStack(pendingRequest.template()));
                    return openShulker(
                            client,
                            sourceShulkerSlot,
                            ActionType.STASH_AND_EXTRACT,
                            retrievedMaterial,
                            targetHotbarSlot
                    );
                }

                int stashShulkerSlot = findShulkerWithSpace(inventory, sourceShulkerSlot);
                if (stashShulkerSlot != -1) {
                    LOGGER.info("{} 有序满背包换入：来源盒槽={} 已满，先把快捷栏 {} 的 {} 暂存到盒槽={}，再取 {}",
                            LOG_PREFIX,
                            sourceShulkerSlot,
                            targetHotbarSlot,
                            describeStack(inventory.getStack(targetHotbarSlot)),
                            stashShulkerSlot,
                            describeStack(pendingRequest.template()));
                    return openShulker(client, stashShulkerSlot, ActionType.STASH, null, targetHotbarSlot);
                }
                LOGGER.warn("{} 有序满背包换入取消：来源盒槽={} 已满，其他潜影盒也没有空位，需求={}",
                        LOG_PREFIX, sourceShulkerSlot, describeStack(pendingRequest.template()));
                return false;
            }

            LOGGER.info("{} 无序满背包直接交换：来源盒槽={}，快捷栏 {} 的 {} 将放入来源盒，换入 {}",
                    LOG_PREFIX,
                    sourceShulkerSlot,
                    targetHotbarSlot,
                    describeStack(inventory.getStack(targetHotbarSlot)),
                    describeStack(pendingRequest.template()));
            return openShulker(
                    client,
                    sourceShulkerSlot,
                    ActionType.REPLACE,
                    new RetrievedMaterial(pendingRequest.template().getItem(), source),
                    targetHotbarSlot
            );
        }

        int shulkerSlot = findShulkerWithMaterial(inventory, pendingRequest.template());
        int targetHotbarSlot = findLitematicaPickBlockTarget(inventory);
        if (shulkerSlot == -1 || targetHotbarSlot == -1) {
            LOGGER.warn("{} 非满背包无法取料：来源盒槽={}，可替换快捷栏槽={}，需求={}",
                    LOG_PREFIX, shulkerSlot, targetHotbarSlot, describeStack(pendingRequest.template()));
            return false;
        }

        TrackedShulker source = TrackedShulker.from(inventory.getStack(shulkerSlot), shulkerSlot);
        LOGGER.info("{} 普通取料：来源盒槽={}，目标快捷栏={}，需求={}，背包空格={}",
                LOG_PREFIX,
                shulkerSlot,
                targetHotbarSlot,
                describeStack(pendingRequest.template()),
                countEmptyPlayerSlots(inventory));
        return openShulker(
                client,
                shulkerSlot,
                ActionType.EXTRACT,
                new RetrievedMaterial(pendingRequest.template().getItem(), source),
                targetHotbarSlot
        );
    }

    private static void waitForOpenedShulker(MinecraftClient client) {
        if (activeAction == null || client.player == null) {
            LOGGER.warn("{} 等待开箱时上下文丢失，清理补料状态", LOG_PREFIX);
            resetState(client);
            return;
        }

        if (client.currentScreen != null) {
            LOGGER.warn("{} 等待后台开箱时检测到前台界面={}，取消操作={}，清理状态",
                    LOG_PREFIX, client.currentScreen.getClass().getSimpleName(), activeAction.type());
            resetState(client);
            return;
        }

        if (++operationTicks > OPEN_TIMEOUT_TICKS) {
            LOGGER.warn("{} 潜影盒开箱超时：操作={}，等待={} tick，来源材料={}，清理状态",
                    LOG_PREFIX,
                    activeAction.type(),
                    operationTicks,
                    activeAction.material() == null ? "无" : Registries.ITEM.getId(activeAction.material().item()));
            resetState(client);
        }
    }

    private static void extractMaterial(MinecraftClient client,
                                        ShulkerBoxScreenHandler handler,
                                        ActiveAction action) {
        if (pendingRequest == null
                || client.player == null
                || client.interactionManager == null
                || !handler.getCursorStack().isEmpty()
                || action.targetHotbarSlot() < 0
                || !isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot())) {
            LOGGER.warn("{} 取料前置条件失败：需求={}，目标快捷栏={}，鼠标光标={}，Litematica 允许替换={}，取消取料",
                    LOG_PREFIX,
                    pendingRequest == null ? "无" : describeStack(pendingRequest.template()),
                    action.targetHotbarSlot(),
                    describeStack(handler.getCursorStack()),
                    client.player != null
                            && action.targetHotbarSlot() >= 0
                            && isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot()));
            pendingRequest = null;
            return;
        }

        Slot sourceSlot = findContainerMaterialSlot(handler, pendingRequest.template());
        Slot destinationSlot = findEmptyPlayerStorageSlot(handler, action.targetHotbarSlot());
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.canTakeItems(client.player)) {
            LOGGER.warn("{} 取料失败：盒内材料槽={}，玩家空槽={}，需求={}，原因={}，盒内容={}",
                    LOG_PREFIX,
                    sourceSlot == null ? "无" : sourceSlot.id,
                    destinationSlot == null ? "无" : destinationSlot.id,
                    describeStack(pendingRequest.template()),
                    sourceSlot == null ? "盒内已无目标材料" : destinationSlot == null ? "背包没有可用空槽" : "材料槽不可取",
                    getStoredStacksFromHandler(handler));
            pendingRequest = null;
            return;
        }

        LOGGER.info("{} 执行取料点击：盒槽={} -> 玩家槽={}，目标快捷栏={}，材料={}",
                LOG_PREFIX, sourceSlot.id, destinationSlot.id, action.targetHotbarSlot(), describeStack(pendingRequest.template()));
        clickSlot(client, handler, sourceSlot.id);
        clickSlot(client, handler, destinationSlot.id);
        if (!destinationSlot.hasStack() || !destinationSlot.getStack().isOf(pendingRequest.template().getItem())) {
            LOGGER.warn("{} 取料点击后校验失败：玩家槽={} 当前={}，光标={}，需求={}",
                    LOG_PREFIX,
                    destinationSlot.id,
                    describeStack(destinationSlot.getStack()),
                    describeStack(handler.getCursorStack()),
                    describeStack(pendingRequest.template()));
            pendingRequest = null;
            return;
        }

        if (destinationSlot.getIndex() != action.targetHotbarSlot()
                && !moveExtractedMaterialToHotbar(client, handler, destinationSlot, action.targetHotbarSlot())) {
            LOGGER.warn("{} 取料后移动到快捷栏失败：临时玩家槽={}，目标快捷栏={}",
                    LOG_PREFIX, destinationSlot.id, action.targetHotbarSlot());
            pendingRequest = null;
            return;
        }

        action.material().source().updateContents(getContainerContents(handler));
        retrievedMaterials.addLast(action.material());
        InventoryUtils.setPickedItemToHand(pendingRequest.template(), client);
        LOGGER.info("{} 取料成功：材料={}，已切到主手={}，来源盒最新内容={}，待回存队列={}",
                LOG_PREFIX,
                describeStack(pendingRequest.template()),
                describeStack(client.player.getMainHandStack()),
                getStoredStacksFromHandler(handler),
                describeRetrievedMaterials());
        pendingRequest = null;
    }

    private static boolean moveExtractedMaterialToHotbar(MinecraftClient client,
                                                          ShulkerBoxScreenHandler handler,
                                                          Slot sourceSlot,
                                                          int targetHotbarSlot) {
        Slot targetSlot = findPlayerStorageSlot(handler, targetHotbarSlot);
        if (targetSlot == null) {
            LOGGER.warn("{} 取料移动失败：找不到快捷栏槽={}", LOG_PREFIX, targetHotbarSlot);
            return false;
        }

        clickSlot(client, handler, sourceSlot.id);
        if (!handler.getCursorStack().isOf(pendingRequest.template().getItem())) {
            LOGGER.warn("{} 取料移动失败：重新拿起临时槽={} 后光标={}，期望={}",
                    LOG_PREFIX, sourceSlot.id, describeStack(handler.getCursorStack()), describeStack(pendingRequest.template()));
            return false;
        }

        clickSlot(client, handler, targetSlot.id);
        if (!targetSlot.hasStack() || !targetSlot.getStack().isOf(pendingRequest.template().getItem())) {
            LOGGER.warn("{} 取料移动失败：目标快捷栏={} 当前={}，光标={}",
                    LOG_PREFIX, targetHotbarSlot, describeStack(targetSlot.getStack()), describeStack(handler.getCursorStack()));
            return false;
        }

        if (!handler.getCursorStack().isEmpty()) {
            clickSlot(client, handler, sourceSlot.id);
        }
        LOGGER.info("{} 取料移动完成：临时槽={} -> 快捷栏={}", LOG_PREFIX, sourceSlot.id, targetHotbarSlot);
        return handler.getCursorStack().isEmpty();
    }

    /**
     * 背包没有空格时，把目标材料直接换入 Litematica 允许的快捷栏格，
     * 同一后台容器操作把被换下的物品放进刚腾空的潜影盒格，避免原版 pick block 选到空手。
     */
    private static void replaceHotbarMaterial(MinecraftClient client,
                                              ShulkerBoxScreenHandler handler,
                                              ActiveAction action) {
        if (pendingRequest == null
                || client.player == null
                || client.interactionManager == null
                || !handler.getCursorStack().isEmpty()
                || action.targetHotbarSlot() < 0
                || !isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot())) {
            LOGGER.warn("{} 满背包交换前置条件失败：需求={}，目标快捷栏={}，光标={}，允许替换={}，取消交换",
                    LOG_PREFIX,
                    pendingRequest == null ? "无" : describeStack(pendingRequest.template()),
                    action.targetHotbarSlot(),
                    describeStack(handler.getCursorStack()),
                    client.player != null
                            && action.targetHotbarSlot() >= 0
                            && isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot()));
            pendingRequest = null;
            return;
        }

        Slot sourceSlot = findContainerMaterialSlot(handler, pendingRequest.template());
        Slot destinationSlot = findPlayerStorageSlot(handler, action.targetHotbarSlot());
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.canTakeItems(client.player)) {
            LOGGER.warn("{} 满背包交换失败：盒内材料槽={}，目标快捷栏槽={}，需求={}，盒内容={}",
                    LOG_PREFIX,
                    sourceSlot == null ? "无" : sourceSlot.id,
                    destinationSlot == null ? "无" : destinationSlot.id,
                    describeStack(pendingRequest.template()),
                    getStoredStacksFromHandler(handler));
            pendingRequest = null;
            return;
        }

        boolean replacedExistingStack = destinationSlot.hasStack();
        LOGGER.info("{} 满背包交换点击：盒槽={} -> 快捷栏={}，被替换物品={}，需求={}",
                LOG_PREFIX,
                sourceSlot.id,
                action.targetHotbarSlot(),
                describeStack(destinationSlot.getStack()),
                describeStack(pendingRequest.template()));
        clickSlot(client, handler, sourceSlot.id);
        if (!handler.getCursorStack().isOf(pendingRequest.template().getItem())) {
            LOGGER.warn("{} 满背包交换取料后光标不匹配：光标={}，需求={}",
                    LOG_PREFIX, describeStack(handler.getCursorStack()), describeStack(pendingRequest.template()));
            pendingRequest = null;
            return;
        }

        clickSlot(client, handler, destinationSlot.id);
        if (!destinationSlot.hasStack() || !destinationSlot.getStack().isOf(pendingRequest.template().getItem())) {
            LOGGER.warn("{} 满背包交换放入快捷栏失败：快捷栏={} 当前={}，光标={}",
                    LOG_PREFIX,
                    action.targetHotbarSlot(),
                    describeStack(destinationSlot.getStack()),
                    describeStack(handler.getCursorStack()));
            pendingRequest = null;
            return;
        }

        if (replacedExistingStack) {
            clickSlot(client, handler, sourceSlot.id);
            if (!handler.getCursorStack().isEmpty()) {
                LOGGER.warn("{} 满背包交换回收旧物失败：来源盒槽={}，光标={}，旧物={}，清理请求",
                        LOG_PREFIX,
                        sourceSlot.id,
                        describeStack(handler.getCursorStack()),
                        describeStack(pendingRequest.template()));
                pendingRequest = null;
                return;
            }
        } else {
            action.material().source().updateContents(getContainerContents(handler));
            retrievedMaterials.addLast(action.material());
        }

        InventoryUtils.setPickedItemToHand(pendingRequest.template(), client);
        LOGGER.info("{} 满背包交换成功：快捷栏={} 已换入={}，旧物已{}，来源盒最新内容={}",
                LOG_PREFIX,
                action.targetHotbarSlot(),
                describeStack(pendingRequest.template()),
                replacedExistingStack ? "回收" : "无需回收",
                getStoredStacksFromHandler(handler));
        pendingRequest = null;
    }

    /**
     * 目标来源盒满时，先腾出目标快捷栏格；下一次后台开箱会从原来源盒取料。
     */
    private static void stashHotbarMaterial(MinecraftClient client,
                                            ShulkerBoxScreenHandler handler,
                                            ActiveAction action) {
        if (client.player == null
                || client.interactionManager == null
                || !handler.getCursorStack().isEmpty()
                || action.targetHotbarSlot() < 0
                || !isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot())) {
            LOGGER.warn("{} 暂存前置条件失败：目标快捷栏={}，光标={}，允许替换={}，取消暂存",
                    LOG_PREFIX,
                    action.targetHotbarSlot(),
                    describeStack(handler.getCursorStack()),
                    client.player != null
                            && action.targetHotbarSlot() >= 0
                            && isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot()));
            return;
        }

        Slot sourceSlot = findPlayerStorageSlot(handler, action.targetHotbarSlot());
        Slot destinationSlot = findEmptyContainerSlot(handler);
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.hasStack() || !sourceSlot.canTakeItems(client.player)) {
            LOGGER.warn("{} 暂存失败：玩家槽={}，盒内空槽={}，玩家物品={}，盒内容={}",
                    LOG_PREFIX,
                    sourceSlot == null ? "无" : sourceSlot.id,
                    destinationSlot == null ? "无" : destinationSlot.id,
                    sourceSlot == null ? "无" : describeStack(sourceSlot.getStack()),
                    getStoredStacksFromHandler(handler));
            return;
        }

        LOGGER.info("{} 执行暂存：快捷栏={} 的 {} -> 盒槽={}",
                LOG_PREFIX, action.targetHotbarSlot(), describeStack(sourceSlot.getStack()), destinationSlot.id);
        clickSlot(client, handler, sourceSlot.id);
        clickSlot(client, handler, destinationSlot.id);
        LOGGER.info("{} 暂存完成：快捷栏={} 当前={}，光标={}，盒内容={}",
                LOG_PREFIX,
                action.targetHotbarSlot(),
                describeStack(sourceSlot.getStack()),
                describeStack(handler.getCursorStack()),
                getStoredStacksFromHandler(handler));
    }

    /**
     * 来源盒原本有空位时，先存旧物、再取材料，保留材料原槽位给后续有序回塞。
     */
    private static void stashHotbarAndExtractMaterial(MinecraftClient client,
                                                       ShulkerBoxScreenHandler handler,
                                                       ActiveAction action) {
        if (pendingRequest == null
                || action.material() == null
                || client.player == null
                || client.interactionManager == null
                || !handler.getCursorStack().isEmpty()
                || action.targetHotbarSlot() < 0
                || !isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot())) {
            LOGGER.warn("{} 有序换入前置条件失败：需求={}，目标快捷栏={}，光标={}，允许替换={}，取消操作",
                    LOG_PREFIX,
                    pendingRequest == null ? "无" : describeStack(pendingRequest.template()),
                    action.targetHotbarSlot(),
                    describeStack(handler.getCursorStack()),
                    client.player != null
                            && action.targetHotbarSlot() >= 0
                            && isLitematicaPickBlockTarget(client.player.getInventory(), action.targetHotbarSlot()));
            pendingRequest = null;
            return;
        }

        Slot playerSlot = findPlayerStorageSlot(handler, action.targetHotbarSlot());
        Slot stashSlot = findEmptyContainerSlot(handler);
        Slot materialSlot = findContainerMaterialSlot(handler, pendingRequest.template());
        if (playerSlot == null
                || stashSlot == null
                || materialSlot == null
                || !playerSlot.hasStack()
                || !playerSlot.canTakeItems(client.player)
                || !materialSlot.canTakeItems(client.player)) {
            LOGGER.warn("{} 有序换入失败：玩家槽={}，盒空槽={}，材料槽={}，玩家物品={}，盒内容={}",
                    LOG_PREFIX,
                    playerSlot == null ? "无" : playerSlot.id,
                    stashSlot == null ? "无" : stashSlot.id,
                    materialSlot == null ? "无" : materialSlot.id,
                    playerSlot == null ? "无" : describeStack(playerSlot.getStack()),
                    getStoredStacksFromHandler(handler));
            pendingRequest = null;
            return;
        }

        LOGGER.info("{} 有序换入点击：先把快捷栏={} 的 {} 放入盒槽={}，再取材料槽={} 到快捷栏={}",
                LOG_PREFIX,
                action.targetHotbarSlot(),
                describeStack(playerSlot.getStack()),
                stashSlot.id,
                materialSlot.id,
                action.targetHotbarSlot());
        clickSlot(client, handler, playerSlot.id);
        clickSlot(client, handler, stashSlot.id);
        if (!handler.getCursorStack().isEmpty()) {
            LOGGER.warn("{} 有序换入暂存后光标未清空：光标={}，取消后续取料", LOG_PREFIX, describeStack(handler.getCursorStack()));
            pendingRequest = null;
            return;
        }

        clickSlot(client, handler, materialSlot.id);
        clickSlot(client, handler, playerSlot.id);
        if (!playerSlot.hasStack() || !playerSlot.getStack().isOf(pendingRequest.template().getItem())) {
            LOGGER.warn("{} 有序换入取料后快捷栏校验失败：槽={} 当前={}，光标={}，需求={}",
                    LOG_PREFIX,
                    action.targetHotbarSlot(),
                    describeStack(playerSlot.getStack()),
                    describeStack(handler.getCursorStack()),
                    describeStack(pendingRequest.template()));
            pendingRequest = null;
            return;
        }

        action.material().source().updateContents(getContainerContents(handler));
        retrievedMaterials.addLast(action.material());
        InventoryUtils.setPickedItemToHand(pendingRequest.template(), client);
        LOGGER.info("{} 有序换入成功：快捷栏={} 已换入={}，来源盒内容={}，待回存队列={}",
                LOG_PREFIX,
                action.targetHotbarSlot(),
                describeStack(pendingRequest.template()),
                getStoredStacksFromHandler(handler),
                describeRetrievedMaterials());
        pendingRequest = null;
    }

    private static void returnMaterial(MinecraftClient client,
                                       ShulkerBoxScreenHandler handler,
                                       RetrievedMaterial material) {
        if (client.player == null || client.interactionManager == null || !handler.getCursorStack().isEmpty()) {
            LOGGER.warn("{} 回存前置条件失败：材料={}，玩家={}，交互器={}，光标={}，丢弃跟踪记录",
                    LOG_PREFIX,
                    material == null ? "无" : Registries.ITEM.getId(material.item()),
                    client.player != null,
                    client.interactionManager != null,
                    describeStack(handler.getCursorStack()));
            retrievedMaterials.removeFirstOccurrence(material);
            return;
        }

        Slot sourceSlot = findPlayerMaterialSlot(handler, material.item());
        Slot destinationSlot = findEmptyContainerSlot(handler);
        if (sourceSlot == null || destinationSlot == null || !sourceSlot.canTakeItems(client.player)) {
            LOGGER.warn("{} 回存失败：材料={} 的玩家槽={}，来源盒空槽={}，盒内容={}，有序={}，丢弃跟踪记录",
                    LOG_PREFIX,
                    Registries.ITEM.getId(material.item()),
                    sourceSlot == null ? "无" : sourceSlot.id,
                    destinationSlot == null ? "无" : destinationSlot.id,
                    getStoredStacksFromHandler(handler),
                    QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled());
            retrievedMaterials.removeFirstOccurrence(material);
            return;
        }

        LOGGER.info("{} 执行回存：玩家槽={} 的 {} -> 来源盒槽={}，材料={}，有序={}",
                LOG_PREFIX,
                sourceSlot.id,
                describeStack(sourceSlot.getStack()),
                destinationSlot.id,
                Registries.ITEM.getId(material.item()),
                QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled());
        clickSlot(client, handler, sourceSlot.id);
        clickSlot(client, handler, destinationSlot.id);
        material.source().updateContents(getContainerContents(handler));
        retrievedMaterials.removeFirstOccurrence(material);
        LOGGER.info("{} 回存完成：材料={}，来源盒最新内容={}，剩余待回存={}",
                LOG_PREFIX,
                Registries.ITEM.getId(material.item()),
                getStoredStacksFromHandler(handler),
                describeRetrievedMaterials());
    }

    private static void processClosingShulker(MinecraftClient client) {
        if (activeAction == null || client.player == null) {
            LOGGER.warn("{} 关闭潜影盒时上下文丢失，清理补料状态", LOG_PREFIX);
            resetState(client);
            return;
        }

        if (client.player.currentScreenHandler.syncId != activeAction.syncId()) {
            LOGGER.info("{} 潜影盒界面已关闭：操作={}，同步 ID={} -> {}，完成清理",
                    LOG_PREFIX,
                    activeAction.type(),
                    activeAction.syncId(),
                    client.player.currentScreenHandler.syncId);
            finishActiveAction();
            return;
        }

        if (!closeSent) {
            if (++operationTicks <= 2) {
                return;
            }

            if (!client.player.currentScreenHandler.getCursorStack().isEmpty()
                    && ++closeCursorRetries < CLOSE_CURSOR_RETRIES) {
                LOGGER.warn("{} 关闭前光标仍有物品，等待第 {}/{} 次：光标={}",
                        LOG_PREFIX,
                        closeCursorRetries,
                        CLOSE_CURSOR_RETRIES,
                        describeStack(client.player.currentScreenHandler.getCursorStack()));
                return;
            }

            LOGGER.info("{} 发起关闭潜影盒：操作={}，同步 ID={}，光标={}",
                    LOG_PREFIX,
                    activeAction.type(),
                    activeAction.syncId(),
                    describeStack(client.player.currentScreenHandler.getCursorStack()));
            client.player.closeHandledScreen();
            closeSent = true;
            operationTicks = 0;
            return;
        }

        if (++operationTicks > CLOSE_TIMEOUT_TICKS) {
            LOGGER.warn("{} 关闭潜影盒超时：操作={}，同步 ID={}，等待={} tick，强制结束状态",
                    LOG_PREFIX, activeAction.type(), activeAction.syncId(), operationTicks);
            finishActiveAction();
        }
    }

    private static boolean openShulker(MinecraftClient client,
                                       int playerInventorySlot,
                                       ActionType actionType,
                                       RetrievedMaterial material,
                                       int targetHotbarSlot) {
        LOGGER.info("{} 发起后台开箱：背包槽={}，操作={}，目标快捷栏={}，材料={}，来源={}",
                LOG_PREFIX,
                playerInventorySlot,
                actionType,
                targetHotbarSlot,
                material == null ? "无" : Registries.ITEM.getId(material.item()),
                material == null ? "无" : describeTrackedShulker(material.source()));
        activeAction = new ActiveAction(actionType, material, targetHotbarSlot, -1);
        operation = Operation.WAITING_FOR_OPEN;
        operationTicks = 0;
        if (!sendOpenQuickShulkerPacket(playerInventorySlot)) {
            LOGGER.warn("{} 后台开箱包发送失败：背包槽={}，操作={}，清理状态", LOG_PREFIX, playerInventorySlot, actionType);
            finishActiveAction();
            return false;
        }

        restockCooldownTicks = QuickCraftConfigs.getQuickShulkerActionIntervalTicks();
        LOGGER.info("{} 后台开箱包已发送：操作={}，冷却={} tick，等待内容回调",
                LOG_PREFIX, actionType, restockCooldownTicks);
        return true;
    }

    private static void beginClosing(ScreenHandler handler) {
        activeAction = new ActiveAction(
                activeAction.type(),
                activeAction.material(),
                activeAction.targetHotbarSlot(),
                handler.syncId
        );
        operation = Operation.WAITING_FOR_CLOSE;
        operationTicks = 0;
        closeCursorRetries = 0;
        closeSent = false;
        LOGGER.info("{} 进入关闭阶段：操作={}，同步 ID={}，盒内容={}",
                LOG_PREFIX, activeAction.type(), handler.syncId, getStoredStacksFromHandler(handler));
    }

    private static void finishActiveAction() {
        if (activeAction != null) {
            LOGGER.info("{} 完成补料操作：操作={}，同步 ID={}，剩余待回存={}，冷却={} tick",
                    LOG_PREFIX,
                    activeAction.type(),
                    activeAction.syncId(),
                    describeRetrievedMaterials(),
                    restockCooldownTicks);
        }
        boolean completedReturn = activeAction != null && activeAction.type() == ActionType.RETURN;
        activeAction = null;
        operation = Operation.IDLE;
        operationTicks = 0;
        closeCursorRetries = 0;
        closeSent = false;
        if (completedReturn) {
            restockCooldownTicks = 0;
        }
    }

    private static void resetState(MinecraftClient client) {
        boolean hadState = activeAction != null || pendingRequest != null || !retrievedMaterials.isEmpty()
                || operation != Operation.IDLE || restockCooldownTicks > 0;
        if (hadState) {
            LOGGER.info("{} 清理补料状态：操作={}，待处理={}，待回存={}，冷却={} tick，客户端界面={}",
                    LOG_PREFIX,
                    operation,
                    pendingRequest == null ? "无" : describeStack(pendingRequest.template()),
                    describeRetrievedMaterials(),
                    restockCooldownTicks,
                    client == null || client.currentScreen == null ? "无" : client.currentScreen.getClass().getSimpleName());
        }
        if (activeAction != null
                && activeAction.syncId() >= 0
                && client != null
                && client.player != null
                && client.player.currentScreenHandler.syncId == activeAction.syncId()) {
            client.player.closeHandledScreen();
        }

        pendingRequest = null;
        retrievedMaterials.clear();
        activeAction = null;
        operation = Operation.IDLE;
        operationTicks = 0;
        closeCursorRetries = 0;
        restockCooldownTicks = 0;
        closeSent = false;
    }

    private static boolean canUseQuickShulker() {
        if (!FabricLoader.getInstance().isModLoaded("quickshulker")) {
            return false;
        }

        try {
            return ClientPlayNetworking.canSend(QUICK_SHULKER_OPEN_PACKET);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("{} Quick Shulker 协议检查失败：{}", LOG_PREFIX, exception.toString());
            return false;
        }
    }

    private static boolean sendOpenQuickShulkerPacket(int slotId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            LOGGER.warn("{} 无法发开箱包：当前没有玩家", LOG_PREFIX);
            return false;
        }

        int handlerSlotId = findCurrentHandlerSlotId(client.player.currentScreenHandler, slotId);
        if (handlerSlotId == -1) {
            LOGGER.warn("{} 无法发开箱包：背包槽={} 不在当前处理器={} 中",
                    LOG_PREFIX, slotId, client.player.currentScreenHandler.getClass().getSimpleName());
            return false;
        }

        try {
            Class<?> packetClass = Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
            Object packet = packetClass.getConstructor(int.class).newInstance(handlerSlotId);
            ClientPlayNetworking.send((CustomPayload) packet);
            LOGGER.info("{} 发送 Quick Shulker 开箱包：背包槽={}，处理器槽={}，处理器同步 ID={}",
                    LOG_PREFIX, slotId, handlerSlotId, client.player.currentScreenHandler.syncId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("{} Quick Shulker 开箱包发送异常：背包槽={}，处理器槽={}，异常={}",
                    LOG_PREFIX, slotId, handlerSlotId, exception.toString());
            return false;
        }
    }

    /**
     * Quick Shulker 服务端按 PlayerScreenHandler 的槽位 ID 解析开箱包：主背包是 9-35，
     * 快捷栏则是 36-44，不能直接把 PlayerInventory 的 0-8 发过去。
     */
    private static int findCurrentHandlerSlotId(ScreenHandler handler, int playerInventorySlot) {
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == playerInventorySlot) {
                return slot.id;
            }
        }
        return -1;
    }

    private static int findShulkerWithMaterial(PlayerInventory inventory, ItemStack template) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack shulker = inventory.getStack(slot);
            if (isShulkerBox(shulker) && shulker.getCount() == 1 && containsMaterial(shulker, template)) {
                return slot;
            }
        }
        return -1;
    }

    private static int findShulkerWithSpace(PlayerInventory inventory, int excludedSlot) {
        for (int slot = 0; slot < 36; slot++) {
            if (slot == excludedSlot) {
                continue;
            }

            ItemStack shulker = inventory.getStack(slot);
            if (isShulkerBox(shulker) && shulker.getCount() == 1 && hasEmptyContainerSlot(shulker)) {
                return slot;
            }
        }
        return -1;
    }

    private static int findReturnShulker(PlayerInventory inventory, RetrievedMaterial material) {
        if (QuickCraftConfigs.isLitematicaShulkerMaterialOrderlyStorageEnabled()) {
            int trackedSlot = material.source().findIn(inventory);
            if (trackedSlot != -1 && hasEmptyContainerSlot(inventory.getStack(trackedSlot))) {
                return trackedSlot;
            }

            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (isShulkerBox(stack)
                        && stack.getCount() == 1
                        && getStoredStacks(stack).size() < 27
                        && containsItem(stack, material.item())) {
                    return slot;
                }
            }

            return -1;
        }

        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isShulkerBox(stack) && stack.getCount() == 1 && getStoredStacks(stack).size() < 27) {
                return slot;
            }
        }
        return -1;
    }

    private static RetrievedMaterial getReturnCandidate(PlayerInventory inventory) {
        while (!retrievedMaterials.isEmpty()) {
            RetrievedMaterial candidate = retrievedMaterials.peekFirst();
            if (hasPlayerItem(inventory, candidate.item())) {
                return candidate;
            }
            retrievedMaterials.removeFirst();
        }
        return null;
    }

    private static boolean hasPlayerMaterial(PlayerInventory inventory, ItemStack template) {
        return hasPlayerItem(inventory, template.getItem());
    }

    private static boolean hasPlayerItem(PlayerInventory inventory, Item item) {
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getStack(slot).isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInventoryFull(PlayerInventory inventory) {
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getStack(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static int countEmptyPlayerSlots(PlayerInventory inventory) {
        int emptySlots = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getStack(slot).isEmpty()) {
                emptySlots++;
            }
        }
        return emptySlots;
    }

    private static String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "空";
        }

        return Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount();
    }

    private static String describeHotbar(PlayerInventory inventory) {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (int slot = 0; slot < 9; slot++) {
            joiner.add(slot + "=" + describeStack(inventory.getStack(slot)));
        }
        return joiner.toString();
    }

    private static String describeShulkers(PlayerInventory inventory, ItemStack template) {
        StringJoiner joiner = new StringJoiner("; ", "[", "]");
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isShulkerBox(stack) && stack.getCount() == 1) {
                joiner.add("槽" + slot
                        + "=" + Registries.ITEM.getId(stack.getItem())
                        + "，占用=" + getStoredStacks(stack).size() + "/27"
                        + "，命中=" + containsMaterial(stack, template));
            }
        }
        String result = joiner.toString();
        return "[]".equals(result) ? "无" : result;
    }

    private static String describeRetrievedMaterials() {
        if (retrievedMaterials.isEmpty()) {
            return "[]";
        }

        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (RetrievedMaterial material : retrievedMaterials) {
            joiner.add(Registries.ITEM.getId(material.item()) + " <- " + describeTrackedShulker(material.source()));
        }
        return joiner.toString();
    }

    private static String describeTrackedShulker(TrackedShulker shulker) {
        if (shulker == null) {
            return "无";
        }

        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (ItemStack stack : shulker.contents) {
            joiner.add(describeStack(stack));
        }
        return "槽" + shulker.lastKnownSlot
                + ":" + Registries.ITEM.getId(shulker.boxItem)
                + "，占用=" + shulker.contents.size() + "/27，内容=" + joiner;
    }

    private static String getStoredStacksFromHandler(ScreenHandler handler) {
        return describeContents(getContainerContents(handler));
    }

    private static String describeContents(List<ItemStack> contents) {
        if (contents.isEmpty()) {
            return "[] (0/27)";
        }

        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (ItemStack stack : contents) {
            joiner.add(describeStack(stack));
        }
        return joiner + " (" + contents.size() + "/27)";
    }

    private static boolean containsMaterial(ItemStack shulker, ItemStack template) {
        return containsItem(shulker, template.getItem());
    }

    private static boolean containsItem(ItemStack shulker, Item item) {
        for (ItemStack stack : getStoredStacks(shulker)) {
            if (stack.isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private static List<ItemStack> getStoredStacks(ItemStack shulker) {
        ContainerComponent container = shulker.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack stack : container.iterateNonEmpty()) {
            contents.add(stack.copy());
        }
        return contents;
    }

    private static boolean hasEmptyContainerSlot(ItemStack shulker) {
        return getStoredStacks(shulker).size() < 27;
    }

    private static List<ItemStack> getContainerContents(ScreenHandler handler) {
        List<ItemStack> contents = new ArrayList<>();
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.hasStack()) {
                contents.add(slot.getStack().copy());
            }
        }
        return contents;
    }

    private static Slot findContainerMaterialSlot(ShulkerBoxScreenHandler handler, ItemStack template) {
        for (Slot slot : getContainerSlots(handler)) {
            if (slot.hasStack() && slot.getStack().isOf(template.getItem())) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findEmptyContainerSlot(ShulkerBoxScreenHandler handler) {
        for (Slot slot : getContainerSlots(handler)) {
            if (!slot.hasStack() && slot.isEnabled()) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findEmptyPlayerStorageSlot(ShulkerBoxScreenHandler handler, int preferredHotbarSlot) {
        Slot preferredSlot = findPlayerStorageSlot(handler, preferredHotbarSlot);
        if (preferredSlot != null && !preferredSlot.hasStack() && preferredSlot.isEnabled()) {
            return preferredSlot;
        }

        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (!slot.hasStack() && slot.isEnabled()) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findPlayerStorageSlot(ShulkerBoxScreenHandler handler, int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= 36) {
            return null;
        }

        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.getIndex() == inventorySlot) {
                return slot;
            }
        }
        return null;
    }

    private static int findLitematicaPickBlockTarget(PlayerInventory inventory) {
        List<Integer> configuredSlots = getLitematicaPickBlockSlots();
        if (configuredSlots.isEmpty()) {
            return -1;
        }

        int selectedSlot = inventory.selectedSlot;
        if (configuredSlots.contains(selectedSlot) && isLitematicaPickBlockTarget(inventory, selectedSlot)) {
            return selectedSlot;
        }

        for (int slot : configuredSlots) {
            if (isLitematicaPickBlockTarget(inventory, slot)) {
                return slot;
            }
        }
        return -1;
    }

    private static List<Integer> getLitematicaPickBlockSlots() {
        List<Integer> slots = new ArrayList<>();
        for (String configuredSlot : Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue().split(",")) {
            try {
                int slot = Integer.parseInt(configuredSlot.trim()) - 1;
                if (slot >= 0 && slot < 9 && !slots.contains(slot)) {
                    slots.add(slot);
                }
            } catch (NumberFormatException ignored) {
                // Litematica 也会忽略格式错误的快捷栏配置。
            }
        }
        return slots;
    }

    private static boolean isLitematicaPickBlockTarget(PlayerInventory inventory, int slot) {
        ItemStack stack = inventory.getStack(slot);
        return !isShulkerBox(stack)
                && (!Configs.Generic.PICK_BLOCK_AVOID_DAMAGEABLE.getBooleanValue() || !stack.isDamageable())
                && (!Configs.Generic.PICK_BLOCK_AVOID_TOOLS.getBooleanValue() || !(stack.getItem() instanceof MiningToolItem));
    }

    private static Slot findPlayerMaterialSlot(ShulkerBoxScreenHandler handler, Item item) {
        for (Slot slot : getPlayerStorageSlots(handler)) {
            if (slot.hasStack() && slot.getStack().isOf(item)) {
                return slot;
            }
        }
        return null;
    }

    private static List<Slot> getContainerSlots(ScreenHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory) && slot.isEnabled()) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator.comparingInt((Slot slot) -> slot.getIndex()).thenComparingInt(slot -> slot.id));
        return slots;
    }

    private static List<Slot> getPlayerStorageSlots(ScreenHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory
                    && slot.getIndex() >= 0
                    && slot.getIndex() < 36
                    && slot.isEnabled()) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator.comparingInt(Slot::getIndex).thenComparingInt(slot -> slot.id));
        return slots;
    }

    private static void clickSlot(MinecraftClient client, ScreenHandler handler, int slotId) {
        client.interactionManager.clickSlot(
                handler.syncId,
                slotId,
                0,
                SlotActionType.PICKUP,
                client.player
        );
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static ItemStack copyTemplate(ItemStack stack) {
        ItemStack template = stack.copy();
        template.setCount(1);
        return template;
    }

    private enum Operation {
        IDLE,
        WAITING_FOR_OPEN,
        WAITING_FOR_CLOSE
    }

    private enum ActionType {
        EXTRACT,
        REPLACE,
        STASH,
        STASH_AND_EXTRACT,
        RETURN
    }

    private record PendingRequest(ItemStack template) {
    }

    private record ActiveAction(ActionType type, RetrievedMaterial material, int targetHotbarSlot, int syncId) {
    }

    private record RetrievedMaterial(Item item, TrackedShulker source) {
    }

    private static final class TrackedShulker {
        private final Item boxItem;
        private List<ItemStack> contents;
        private int lastKnownSlot;

        private TrackedShulker(Item boxItem, List<ItemStack> contents, int lastKnownSlot) {
            this.boxItem = boxItem;
            this.contents = contents;
            this.lastKnownSlot = lastKnownSlot;
        }

        private static TrackedShulker from(ItemStack shulker, int slot) {
            return new TrackedShulker(shulker.getItem(), getStoredStacks(shulker), slot);
        }

        private void updateContents(List<ItemStack> contents) {
            this.contents = copyContents(contents);
        }

        private int findIn(PlayerInventory inventory) {
            for (int slot = 0; slot < 36; slot++) {
                if (isMatch(inventory.getStack(slot))) {
                    lastKnownSlot = slot;
                    return slot;
                }
            }

            if (lastKnownSlot >= 0
                    && lastKnownSlot < 36
                    && isSameBox(inventory.getStack(lastKnownSlot))) {
                return lastKnownSlot;
            }
            return -1;
        }

        private boolean isMatch(ItemStack stack) {
            return isSameBox(stack)
                    && areContentsEqual(contents, getStoredStacks(stack));
        }

        private boolean isSameBox(ItemStack stack) {
            return stack.getItem() == boxItem && stack.getCount() == 1;
        }
    }

    private static List<ItemStack> copyContents(List<ItemStack> contents) {
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : contents) {
            copies.add(stack.copy());
        }
        return copies;
    }

    private static boolean areContentsEqual(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) {
            return false;
        }

        boolean[] matched = new boolean[second.size()];
        for (ItemStack stack : first) {
            boolean found = false;
            for (int index = 0; index < second.size(); index++) {
                if (!matched[index] && ItemStack.areEqual(stack, second.get(index))) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
