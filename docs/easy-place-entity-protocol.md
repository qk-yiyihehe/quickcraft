# QuickCraft 轻松放置实体：Carpet 扩展服务端实现说明

本文面向负责 Carpet 扩展的服务端开发者。客户端实现只负责读取 Litematica 投影、显示选择器和发送请求；服务端是唯一权威：

- 服务端决定是否启用功能。
- 服务端重新计算实体类型、材料、距离、权限和世界边界。
- 服务端在同一个主线程任务中完成“校验材料 -> 扣材料 -> 生成实体”。
- 客户端没有服务端能力握手时不会发送放置请求，也不会创建假实体或本地扣物品。

当前协议版本：`2`  
协议命名空间：`quickcraft`  
目标版本线：QuickCraft 与 Carpet-FGA Addition 的 `1.21` 至 `26.2` 对应版本

## 版本兼容约定

- 四个 payload ID、字段顺序和字段上限在所有 Minecraft 版本分支中保持一致。
- Minecraft、Yarn/Mojmap、Fabric API 的类名或方法名变化只由各分支源码适配，不因此升级协议版本。
- `1.21.11+` 服务端源码中的 `Identifier` 与此前的 `ResourceLocation` 都按同一种资源 ID 字符串和网络格式传输。
- 只有增加、删除、重排字段或改变字段编码时才升级 `PROTOCOL_VERSION`。
- 客户端与服务端应使用相同 Minecraft 版本线；本协议不提供跨 Minecraft 版本登录能力。
- 新功能优先通过 hello/capability 的功能位协商。不能用功能位兼容的二进制布局变化才发布新协议版本。

Carpet-FGA Addition 使用一个源码分支和 ReplayMod Preprocessor 生成各版本包。QuickCraft 客户端按版本分支维护，但每个分支中的 payload 定义必须保持相同的 ID、协议版本和字段顺序。

## 1. 功能流程

```text
客户端进入游戏
    |
    | quickcraft:entity_place_hello
    v
服务端检查协议版本，返回 capability
    |
    | quickcraft:entity_place_capability(enabled=true)
    v
客户端按 Alt + E 打开实体选择器并点击实体格子
    |
    | quickcraft:entity_place_request
    v
服务端主线程重新校验所有数据
    |
    +-- 失败 -> entity_place_result(status != SUCCESS)，不扣材料、不生成实体
    |
    +-- 成功 -> 原子扣材料、生成实体、entity_place_result(SUCCESS)
```

服务端未注册 `entity_place_hello` 或未声明可接收 `entity_place_request` 时，客户端会认为服务器不支持该功能。

## 2. 注册网络 Payload

服务端扩展必须在 play 阶段注册四个 payload。下面是 Fabric API 1.21 风格的骨架，具体包名按 Carpet 扩展工程调整：

```java
public final class QuickCraftEntityPlaceNetworking {
    public static final int PROTOCOL_VERSION = 2;
    public static final int MAX_NBT_BYTES = 262_144;
    public static final Identifier HELLO_ID = Identifier.of("quickcraft", "entity_place_hello");
    public static final Identifier CAPABILITY_ID = Identifier.of("quickcraft", "entity_place_capability");
    public static final Identifier REQUEST_ID = Identifier.of("quickcraft", "entity_place_request");
    public static final Identifier RESULT_ID = Identifier.of("quickcraft", "entity_place_result");

    public static void register() {
        PayloadTypeRegistry.playC2S().register(HelloPayload.ID, HelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestPayload.ID, RequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CapabilityPayload.ID, CapabilityPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ResultPayload.ID, ResultPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(HelloPayload.ID,
                (payload, context) -> context.server().execute(() ->
                        QuickCraftEntityPlaceServer.onHello(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(RequestPayload.ID,
                (payload, context) -> context.server().execute(() ->
                        QuickCraftEntityPlaceServer.onRequest(context.player(), payload)));
    }
}
```

`PayloadTypeRegistry` 和接收器必须在服务器初始化时注册一次。不要在每个玩家进入时重复注册，否则会出现重复 receiver 或注册异常。

在 26.x Fabric API 中，四个 registry 入口更名为：

```java
PayloadTypeRegistry.serverboundPlay();
PayloadTypeRegistry.clientboundPlay();
```

这只是注册 API 名称变化，不改变线上 payload 字节。

### 2.1 Codec 约束

服务端必须使用与客户端相同的 codec 字段顺序。不要使用 JSON、NBT 外层字符串或自定义字段顺序替代当前协议。

如果服务端扩展不能直接复用客户端源码，请复制同等的 record 和 codec 定义到服务端工程。当前 QuickCraft 主包声明为 client 环境，Carpet 扩展不应把客户端 jar 当作服务端依赖。

## 3. Payload 字段表

### 3.1 `quickcraft:entity_place_hello`（C2S）

| 顺序 | 类型 | 限制 | 含义 |
| --- | --- | --- | --- |
| 1 | `varint` | 建议 `1..16` | 客户端协议版本 |
| 2 | `varint` | 当前客户端为 `0` | 客户端功能位 |
| 3 | `varint` | 客户端当前为 `262144` | 客户端可接受的最大 NBT 字节数 |

服务端收到后：

1. 拒绝版本不支持的客户端。
2. 为该玩家创建新的会话 token。
3. 将服务端实际允许的 NBT 上限取为 `min(serverLimit, clientMaxNbtBytes)`。
4. 回传 `entity_place_capability`。

### 3.2 `quickcraft:entity_place_capability`（S2C）

| 顺序 | 类型 | 限制 | 含义 |
| --- | --- | --- | --- |
| 1 | `varint` | `2` | 服务端采用的协议版本 |
| 2 | `boolean` | - | 是否启用实体放置 |
| 3 | `double` | 建议 `0..64` | 服务端实际 reach |
| 4 | `varint` | 建议不超过 `262144` | 服务端允许的最大 NBT 字节数 |
| 5 | `varint` | - | 服务端功能位 |
| 6 | `string(128)` | 不可为空 | 本次连接的随机 session token |

当功能关闭或版本不匹配时也应发送 capability，但设置 `enabled=false`。此时客户端不会发送放置请求。

### 3.3 `quickcraft:entity_place_request`（C2S）

| 顺序 | 类型 | 限制 | 含义 |
| --- | --- | --- | --- |
| 1 | `string(128)` | 必须匹配当前会话 | session token |
| 2 | `long` | 每次请求随机 | 客户端 nonce |
| 3 | `Identifier` | 必须等于玩家当前维度 | 客户端当前维度 |
| 4-6 | `double` | 有限数值 | 目标位置 `x/y/z` |
| 7 | `Identifier` | 必须为允许的实体类型 | 实体类型 |
| 8 | `string(256)` | 仅用于日志/审计 | 投影子区域名称 |
| 9 | `varint` | 建议 `0..100000` | 投影实体索引 |
| 10 | `float` | 有限数值 | 变换后的 yaw |
| 11 | `float` | 有限数值 | 变换后的 pitch |
| 12-14 | `double` | 速度上限内 | 变换后的动量 `x/y/z` |
| 15 | `boolean` | 仅作为请求；服务端必须确认创造模式 | 是否请求创造模式免材料 |
| 16 | `NbtCompound` | 不超过 capability 上限 | 客户端投影实体 NBT |

注意：`region` 和 `entityIndex` 不是安全凭证。客户端可以伪造任意子区域名、索引和 NBT，服务端不能用它们证明玩家拥有某个投影文件。

### 3.4 `quickcraft:entity_place_result`（S2C）

| 顺序 | 类型 | 限制 | 含义 |
| --- | --- | --- | --- |
| 1 | `long` | 对应请求 nonce | 请求关联 ID |
| 2 | `string(64)` | 使用下方状态码 | 处理结果 |
| 3 | `string(64)` | 成功时可填 UUID | 服务端生成实体 UUID |
| 4 | `string(256)` | 可为空 | 客户端语言 key |

服务端发送回执时必须使用原请求 nonce。失败回执不能填写成功 UUID。

## 4. 玩家会话状态

服务端为每个 `ServerPlayerEntity` 保存独立状态，至少包含：

```java
final class EntityPlaceSession {
    UUID playerUuid;
    String token;
    int negotiatedVersion;
    int maxNbtBytes;
    double reach;
    boolean enabled;
    long lastRequestTick;
    int requestsThisWindow;
    LongOpenHashSet usedNonces;
}
```

建议规则：

- token 使用 `UUID.randomUUID().toString()` 或同等强度的随机值。
- 玩家退出、换维度或服务器重载时丢弃旧 token。
- 每个 token 只绑定一个玩家，不能跨玩家复用。
- 当前实现为每个会话保留最近 64 个 nonce，避免重复请求重新扣材料。
- 当前实现为每玩家每 2 tick 最多处理 1 次实体放置请求；超出返回 `RATE_LIMITED`。
- 每个 tick 的累计处理数量也应设置服务器级上限，防止大量玩家同时发包拖垮主线程。

## 5. Hello 处理

伪代码：

```java
static void onHello(ServerPlayerEntity player, HelloPayload hello) {
    EntityPlaceSession session = sessions.computeIfAbsent(player.getUuid(), id -> new EntityPlaceSession());
    session.token = UUID.randomUUID().toString();
    session.negotiatedVersion = hello.version();

    boolean versionOk = hello.version() == PROTOCOL_VERSION;
    boolean enabled = config.enabled() && versionOk;
    session.enabled = enabled;
    session.reach = MathHelper.clamp(serverReach(player), 0.0D, 64.0D);
    session.maxNbtBytes = Math.min(MAX_NBT_BYTES, Math.max(0, hello.maxNbtBytes()));

    send(player, new CapabilityPayload(
            PROTOCOL_VERSION,
            enabled,
            session.reach,
            session.maxNbtBytes,
            0,
            session.token
    ));
}
```

如果 Carpet 配置、权限或世界规则不允许实体放置，将 `enabled` 设为 `false`。不要返回 `enabled=true` 后再依赖请求阶段临时拒绝所有请求，这会让客户端误以为功能可用。

## 6. Request 处理总流程

所有检查和生成都必须在服务端主线程执行。网络 receiver 只负责把任务切回 `server.execute(...)`，不要在网络线程直接改世界或玩家库存。

推荐顺序如下：

```text
1. 找到玩家会话
2. 检查 enabled、版本、token
3. 检查 nonce 是否重放、请求频率
4. 检查坐标和速度是否为有限数值
5. 检查维度和玩家实际距离
6. 解析并清洗实体 NBT
7. 检查实体白名单和服务端世界规则
8. 从清洗后的 NBT 重新计算材料
9. 检查玩家库存和背包锁定规则
10. 检查实体生成位置和世界边界
11. 原子扣材料
12. 生成实体及乘客
13. 记录 nonce，发送 SUCCESS
```

进入扣料步骤前发生的失败必须保持库存和世界不变；扣料后的实体加入失败必须删除整棵实体树并恢复库存快照，再返回失败回执。

## 7. 安全校验

### 7.1 会话、版本和 nonce

```java
if (!session.enabled) {
    reject(player, request.nonce(), "DISABLED", "quickcraft.entity_placement.disabled");
    return;
}
if (!constantTimeEquals(session.token, request.sessionToken())) {
    reject(player, request.nonce(), "INVALID_SESSION", "quickcraft.entity_placement.invalid_session");
    return;
}
if (session.usedNonces.contains(request.nonce())) {
    reject(player, request.nonce(), "REPLAYED_REQUEST", "quickcraft.entity_placement.replayed");
    return;
}
```

`INVALID_SESSION` 是建议的扩展状态，客户端即使没有专门文案也必须安全忽略。不要先把 nonce 标记为已使用，再执行可能失败的校验；建议只在成功或明确处理完的请求上记录，具体策略要保证重放不能再次扣物品。

### 7.2 数值和大小

必须拒绝：

- `NaN`、正负无穷坐标、yaw、pitch 或速度。
- 坐标绝对值超过服务器世界允许范围。
- `entityNbt` 序列化大小超过 `session.maxNbtBytes`。
- 乘客树深度超过建议值 `8`。
- 乘客总数超过建议值 `16`。
- 速度任一轴绝对值超过建议值 `100`，或速度向量长度超过服务器上限。
- 字符串字段超过协议声明长度。

NBT 大小必须按服务端实际解码后的 compound 重新计算，不能只相信客户端 hello 中的大小。

### 7.3 维度和 reach

服务端不能相信客户端发送的坐标和维度声明。使用当前 `ServerPlayerEntity`：

```java
if (!player.getWorld().getRegistryKey().getValue().equals(request.dimension())) {
    reject(player, request.nonce(), "WRONG_DIMENSION", "quickcraft.entity_placement.wrong_dimension");
    return;
}

double allowed = session.reach + 0.25D; // 仅用于浮点/网络误差，不作为额外玩法距离
if (player.getEyePos().squaredDistanceTo(request.target()) > allowed * allowed) {
    reject(player, request.nonce(), "OUT_OF_REACH", "quickcraft.entity_placement.out_of_reach");
    return;
}
```

`session.reach` 应来自服务端实际规则，例如服务端交互距离、权限或 Carpet 配置。不能使用客户端本地 reach 作为权威值。

创造免材料同样不能只相信请求字段。只有请求值为 `true` 且服务端玩家当前确实处于创造模式时才能跳过材料检查和扣除；生存或冒险模式伪造该字段仍必须正常检查材料。

### 7.4 实体类型白名单

首版建议只允许有明确生存材料映射的类型：

| 实体 | 服务端材料 |
| --- | --- |
| 生物 | 对应 `SpawnEggItem`，并重新校验刷怪蛋的实体类型 |
| `minecraft:item` | NBT `Item` 中的真实 `ItemStack` |
| 盔甲架 | `minecraft:armor_stand` |
| 普通/熔炉/TNT 矿车 | 对应矿车物品 |
| 箱子/漏斗矿车 | 对应矿车物品，加 NBT `Items` 内所有物品 |
| 船 | 根据 NBT `Type` 对应木材船或竹筏 |
| 箱船 | 根据 NBT `Type` 对应箱船或竹筏箱船，加 NBT `Items` 内所有物品 |
| 画 | `minecraft:painting`，保留合法变体和挂靠方向 |
| 普通/荧光物品展示框 | 对应展示框物品；有展示物时额外扣除 NBT `Item` 中的完整 `ItemStack` |
| 末影水晶 | `minecraft:end_crystal` |

以下类型默认拒绝，除非 Carpet 扩展明确实现材料和安全策略：

- 命令方块矿车、刷怪笼矿车。
- `block_display`、`item_display`、`text_display`。
- `interaction`、`marker` 等没有明确生存材料入口的工具实体。
- 投射物、闪电、爆炸物、经验球等不可由普通生存材料直接放置的实体。
- 任何服务端未知或模组未加载的实体类型。

服务端必须根据 `entityNbt.id` 或已解析的实体类型重新判断类型，不能只使用 payload 的 `entityType` 字段。

## 8. NBT 清洗和实体构造

### 8.1 绝对不能直接信任的字段

服务端至少应删除或重写：

- `UUID`、`UUIDMost`、`UUIDLeast`。
- `Pos`、`Rotation`、`Motion`，使用服务端校验后的目标位置和限制后的朝向/速度。
- `Passengers` 中每个实体的 UUID 和位置。
- 任何能改变权限、命令执行、区块加载或刷物品行为的自定义字段。

对于生物，建议只保留服务端允许的外观、年龄、装备和自定义名称字段；不要允许客户端直接指定任意 AI、经验、战利品或管理字段。

### 8.2 乘客树

如果允许载具和乘客：

1. 递归解析 `Passengers`。
2. 每个节点重新校验实体类型白名单。
3. 每个节点重新计算材料需求。
4. 限制深度和总节点数。
5. 先创建载具，再按顺序创建乘客并调用服务端的挂载 API。
6. 任一节点失败，回滚整棵实体树并恢复全部材料。

当前实现限制乘客树深度不超过 `8`、实体总数不超过 `16`；客户端和服务端都递归计算每个节点的本体、装备、容器内容及展示物材料。

不要把客户端 NBT 直接交给 `EntityType.loadEntityWithPassengers` 或等价方法而不做字段清洗。

### 8.3 位置、方向和速度

客户端已经按照投影的 origin、子区域位置、旋转和镜像计算了 `target`、`yaw/pitch`、`velocity`，但服务端仍应：

- 普通实体用服务端校验后的 `target` 重写根实体位置。
- 将 yaw 规范化到 `[-180, 180)`，pitch 限制在实体允许范围。
- 对 velocity 做每轴和长度限制。
- 对船、矿车等按实体类型额外检查放置方向。
- 画和展示框不能在载入 NBT 后再统一调用 `moveTo(target)`；这会覆盖其 `TileX/TileY/TileZ` 挂靠点。服务端应先把实体临时放到 `target` 附近以通过原版 NBT 距离检查，载入已变换的挂靠点和方向，然后保留原版重新计算出的实际位置。
- 创建完成后必须以实体的服务端实际位置再次校验玩家 reach；挂靠实体还要以实际挂靠方块再次校验区块和权限，不能只校验客户端 `target`。

## 9. 材料计算与原子扣除

### 9.1 材料计算原则

材料必须由服务端从最终清洗后的实体树计算，不能使用：

- 客户端 UI 显示的材料。
- 客户端发送的材料数量。
- 客户端实体索引。
- 客户端声称的“生存可获取”标记。

建议将材料需求规范化为：

```java
record RequiredMaterial(ItemStack template, int count) {}
```

然后按 `ItemStack.areItemsAndComponentsEqual(...)` 合并相同材料。掉落物的 `ItemStack` 必须读取并验证组件、数量和最大堆叠数。

### 9.2 刷怪蛋

对于生物：

1. 从服务端注册表解析实体类型。
2. 查找对应 `SpawnEggItem`。
3. 如果不存在对应刷怪蛋，返回 `UNSUPPORTED_ENTITY`。
4. 不允许客户端通过实体 NBT 把普通刷怪蛋改成其他实体。

### 9.3 库存检查

库存检查必须在服务端主线程完成，覆盖玩家背包、快捷栏以及服务端明确允许的容器来源。建议首版只检查玩家自身库存，减少容器并发和回滚风险。

### 9.3.1 容器实体内容物

容器实体不能只扣载具本体，否则玩家可通过带有 NBT 的蓝图复制物品。服务端应按最终清洗后的实体 NBT 读取 `Items` 列表，将其中每个有效 `ItemStack` 加入材料需求：

```java
List<RequiredMaterial> calculateStorageEntityMaterials(NbtCompound nbt, ItemStack vehicleItem) {
    List<RequiredMaterial> required = new ArrayList<>();
    addAndMerge(required, vehicleItem);

    for (NbtCompound itemNbt : nbt.getList("Items", NbtElement.COMPOUND_TYPE)) {
        ItemStack stack = ItemStack.fromNbt(registryManager, itemNbt).orElse(ItemStack.EMPTY);
        if (stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > stack.getMaxCount()) {
            throw new InvalidEntityNbtException("invalid storage item");
        }
        addAndMerge(required, stack);
    }
    return required;
}
```

适用首版实体：`minecraft:chest_minecart`、`minecraft:hopper_minecart` 和 `minecraft:chest_boat`。例如，一个漏斗矿车内存有 32 个铁锭和 3 个漏斗时，服务端需同时扣除：`1 x 漏斗矿车`、`32 x 铁锭`、`3 x 漏斗`。任何材料不足都必须整体拒绝，不能只生成空载具或只扣部分材料。

服务端还必须限制 `Items` 条目数不超过该实体的真实库存容量：漏斗矿车为 5，箱子矿车和箱船均为 27。不要信任客户端 `Slot` 编号；重复槽位、越界槽位、非法物品 NBT 都应返回 `INVALID_NBT`。

### 9.3.2 展示框、装备和乘客材料

- 普通或荧光展示框始终扣除展示框本体；`Item` 非空时，再按完整物品组件和数量扣除展示物。
- `SaddleItem`、`ArmorItem`、`DecorItem`、`body_armor_item`、`ArmorItems` 和 `HandItems` 中的有效物品都加入材料需求。
- 驴、骡、羊驼或行商羊驼带箱子时额外扣除 1 个箱子；驴/骡按 15 格校验，羊驼按合法的 `Strength × 3` 格校验。
- `Passengers` 中每个节点递归执行相同计算。任何一个本体、装备、容器内容或展示物缺少材料，都返回 `NO_MATERIAL`，不得生成部分实体树。

### 9.4 原子扣除

推荐结构：

```java
List<RequiredMaterial> required = calculateMaterials(cleanEntityTree);
if (!inventoryContains(player, required)) {
    reject(player, nonce, "NO_MATERIAL", "quickcraft.entity_placement.no_material");
    return;
}

// 进入同一个服务端 tick 的原子段；此处之后不能再做可失败的解析。
InventorySnapshot before = snapshotInventory(player);
if (!removeMaterials(player, required)) {
    restoreInventory(player, before);
    reject(player, nonce, "NO_MATERIAL", "quickcraft.entity_placement.no_material");
    return;
}

List<Entity> created = spawnEntityTree(player, cleanEntityTree, target);
if (created.isEmpty() || created.size() != expectedEntityCount) {
    removeEntities(created);
    restoreInventory(player, before);
    reject(player, nonce, "INTERNAL_ERROR", "quickcraft.entity_placement.internal_error");
    return;
}
```

如果工程已有可靠的库存事务/回滚工具，优先复用。不要先扣材料、再把生成任务异步丢到下一 tick；这会产生扣除成功但生成失败的窗口。

## 10. 生成位置、权限和世界规则

生成前必须检查：

- 玩家拥有放置权限，且未被 spawn protection、区域保护或 Carpet 规则禁止。
- 目标区块已加载，或服务端明确允许加载该区块。
- 根实体和所有乘客的碰撞箱位于世界边界内。
- 画和展示框的挂靠方块有效，且原版 `survives()` 校验通过。
- 实体类型允许在当前维度生成。
- 实体数量、追踪范围和世界实体上限不会被突破。
- 生物、载具、掉落物的原版生成规则没有被绕过。

投影记录的实体可能本来就与方块重叠，因此本协议明确允许方块碰撞，不能用 `noCollision()` 或 `noBlockCollision()` 拒绝这类请求。`COLLISION` 仅保留给世界边界等无法生成的位置校验。

失败状态建议：`COLLISION`、`PERMISSION_DENIED`、`WORLD_RULE_BLOCKED`、`UNSUPPORTED_ENTITY`。

## 11. 回执发送

```java
static void sendResult(ServerPlayerEntity player, long nonce, String status, String uuid, String messageKey) {
    ServerPlayNetworking.send(player,
            new ResultPayload(nonce, status, uuid == null ? "" : uuid, messageKey == null ? "" : messageKey));
}

static void reject(ServerPlayerEntity player, long nonce, String status, String messageKey) {
    sendResult(player, nonce, status, "", messageKey);
}
```

推荐状态码：

| 状态 | 含义 |
| --- | --- |
| `SUCCESS` | 材料已扣除，实体已生成 |
| `DISABLED` | 服务端配置或权限关闭 |
| `INVALID_SESSION` | token 不匹配或会话已失效 |
| `UNSUPPORTED_ENTITY` | 实体类型或材料映射不支持 |
| `OUT_OF_REACH` | 超出服务端 reach |
| `WRONG_DIMENSION` | 请求维度与玩家当前维度不一致 |
| `NO_MATERIAL` | 服务端库存材料不足 |
| `INVALID_NBT` | NBT、数值、乘客树或大小不合法 |
| `COLLISION` | 实体碰撞箱超出世界边界等不可生成区域 |
| `PERMISSION_DENIED` | 区域、权限或世界规则拒绝 |
| `RATE_LIMITED` | 请求过于频繁 |
| `REPLAYED_REQUEST` | nonce 已处理 |
| `INTERNAL_ERROR` | 服务端生成或回滚失败 |

客户端当前会显示带有 `messageKey` 的失败回执；服务端也可以发送空 key，仅记录日志。

## 12. 建议的服务端模块划分

Carpet 扩展可以按以下职责拆分：

```text
QuickCraftEntityPlaceNetworking
  - payload ID、codec、receiver 注册

QuickCraftEntityPlaceSessions
  - token、版本协商、nonce、限频、玩家退出清理

QuickCraftEntityPlaceValidator
  - 数值、维度、reach、权限、世界边界、NBT 安全检查

QuickCraftEntityPlaceMaterials
  - 实体树 -> RequiredMaterial 列表

QuickCraftEntityPlaceSpawner
  - 清洗后的实体树创建、挂载、回滚

QuickCraftEntityPlaceServer
  - 串联上述模块并发送 result
```

不要让 networking receiver 直接承担材料计算和实体生成；这样更难测试，也容易在未来 Carpet 规则变化时引入绕过校验的路径。

## 13. Carpet 规则建议

建议至少提供以下服务端规则，默认全部关闭或按服务器管理员选择：

```text
quickCraftEasyPlaceEntities         总开关
quickCraftEasyPlaceEntitiesReach    最大 reach，默认使用原版交互距离
quickCraftEasyPlaceEntitiesRate     每玩家请求间隔
quickCraftEasyPlaceEntitiesNbtBytes 最大 NBT 字节数
quickCraftEasyPlaceEntitiesPassengers 是否允许乘客树
quickCraftEasyPlaceEntitiesDangerous 是否允许危险实体
```

规则关闭时仍可以注册 payload，但 capability 必须返回 `enabled=false`。

## 14. 测试清单

### 协议和会话

- 正确 hello 返回 `enabled=true` capability。
- 版本不匹配返回 `enabled=false`。
- token 错误返回 `INVALID_SESSION`。
- 相同 nonce 第二次请求返回 `REPLAYED_REQUEST`。
- 高频请求返回 `RATE_LIMITED`。
- 玩家退出、换维度或重载后旧 token 失效。

### 安全

- 伪造实体类型但 NBT 使用另一实体时拒绝。
- 伪造坐标、维度、yaw、pitch、velocity 时拒绝或按服务端值重写。
- 超大 NBT、深乘客树、过快速度被拒绝。
- 客户端提供不存在的材料字段时，服务端仍按 NBT 重算。
- 伪造 UUID、管理员字段、命令字段不会进入生成实体。

### 材料和回滚

- 材料不足时库存完全不变。
- 目标超出世界边界时库存完全不变。
- 生成中途失败时已扣材料全部恢复。
- 成功时每种材料只扣一次。
- 掉落物数量、组件和最大堆叠数正确处理。
- 生物必须扣对应刷怪蛋，而不是任意刷怪蛋。

### 世界行为

- reach 使用服务端值，不受客户端伪造影响。
- 区域保护、spawn protection 和 Carpet 规则正常生效。
- 多人同时请求不会重复扣除或生成超额实体。
- 客户端未安装 QuickCraft 时，服务器仍能安全忽略未知请求。
- 客户端没有 capability 时不会产生任何服务端实体。

## 15. 与客户端实现的边界

客户端会发送以下信息，但服务端必须全部视为不可信输入：

- `entityType`
- `target`
- `region`
- `entityIndex`
- `yaw/pitch`
- `velocity`
- `entityNbt`

客户端不会发送材料清单，也不会发送“我有材料”的声明。选择器里的材料图标只用于玩家提示，真正扣除完全由服务端实现。

客户端没有服务端协议时的行为是固定的：

- 不生成实体。
- 不在 `ClientWorld` 创建视觉假实体。
- 不修改玩家库存。
- 不走单机特殊回退。

因此，Carpet 扩展只要正确实现上述协议和服务端校验，单机集成服与联机服务器可以使用同一套服务端逻辑。
