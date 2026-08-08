# Create Mod 安全漏洞 #10624 完整分析

## 漏洞概述

| 项目 | 内容 |
|------|------|
| Issue 编号 | [#10624](https://github.com/Creators-of-Create/Create/issues/10624) |
| 漏洞类型 | 任意物品生成 / NBT注入 |
| 严重等级 | 严重 (Critical) |
| 影响版本 | Create 6.0.4 ~ 6.0.10（mc1.20.1 及 mc1.21.1 分支） |
| 发现者 | Adnermo |
| 发现日期 | 2026-07-16 |
| PoC 工具 | [Create-FilterBreaker](https://github.com/Adnermo/Create-FilterBreaker) |
| 关联 Issue | [#8086](https://github.com/Creators-of-Create/Create/issues/8086)（物品复制/dupe bug） |
| 对应方块 | 仓储发报机 (Stock Ticker) |

---

## 漏洞形状

```
攻击者（修改过的客户端）
  │
  │  发送 REFUND_STOCK_KEEPER_CATEGORY 包
  │  ┌───────────────────────────────────────┐
  │  │ BlockPos: 任意已加载的 Stock Ticker 坐标 │
  │  │ ItemStack: 任意 FilterItem             │
  │  │   例如: create:filter, count=64, 任意 NBT │
  │  └───────────────────────────────────────┘
  │
  ▼
服务端处理
  │
  │  BlockEntityConfigurationPacket.handle()
  │    ✅ 检查: !player.isSpectator()
  │    ✅ 检查: !AdventureUtil.isAdventure(player)
  │    ✅ 检查: 距离 < maxRange() (20格)
  │    ❌ 未检查: 玩家是否打开了 StockKeeperCategoryMenu
  │    ❌ 未检查: menu.contentHolder == 目标BE
  │
  │  StockKeeperCategoryRefundPacket.applySettings()
  │    ✅ 检查: filter.isEmpty() == false
  │    ✅ 检查: filter.getItem() instanceof FilterItem
  │    ❌ 未检查: filter 是否存在于 be.categories 中
  │    ❌ 未检查: filter.getCount() == 1
  │    ❌ 未检查: filter 的 NBT 是否与服务端一致
  │
  ▼
player.getInventory().placeItemBackInInventory(filter)
  → 任意 FilterItem 写入玩家背包
```

**核心问题：服务端仅做了一次 `instanceof FilterItem` 类型检查，就完全信任客户端传来的 `ItemStack`，将其放入玩家背包。**

---

## 漏洞利用链

### 方式一：Create-FilterBreaker 工具（最简单）

```
安装 FilterBreaker 客户端模组
  → 进入游戏，定位任意已加载的 Stock Ticker
  → 输入命令: /gainfilter create:filter 64
  → 客户端通过反射构造 StockKeeperCategoryRefundPacket
  → PacketDistributor.sendToServer() 发送
  → 服务端验证仅检查 instanceof FilterItem ✓
  → placeItemBackInInventory(filter) → 64个列表过滤器进入背包
```

### 方式二：手动修改客户端

```
修改 Minecraft 客户端网络层
  → 伪造 REFUND_STOCK_KEEPER_CATEGORY 数据包
  → 填入任意 FilterItem 的 ItemStack（自定义 count、NBT）
  → 发送到服务端
  → 服务端无条件接受
```

### 方式三：EditPacket 投毒 + GUI 正常退还

```
1. 发送 StockKeeperCategoryEditPacket，注入恶意 FilterItem 到 be.categories
   → 服务端无验证接受: be.categories = schedule
2. 正常打开 StockKeeperCategoryScreen
   → GUI 显示注入的恶意 FilterItem
3. 正常点击删除按钮
   → 客户端发送 StockKeeperCategoryRefundPacket(blockPos, entry)
   → 服务端 applySettings() → placeItemBackInInventory(filter)
```

---

## 漏洞延续（横向关联）

### StockKeeperCategoryEditPacket（中危）

```
客户端发送完整 List<ItemStack>
  → 服务端直接: be.categories = schedule  (无任何验证)
  → 攻击者可注入任意 FilterItem 到 BE 中
  → 配合 RefundPacket 或方块破坏实现变现
```

### 与 #8086 的关系

#8086 报告的物品复制现象：关闭 Stock Ticker 的 GUI 时会复制过滤器。这是因为：
- 客户端 `schedule` 列表中的 FilterItem 被发送到服务端
- 服务端 `categories` 记录该 FilterItem
- 同时 FilterItem 仍在玩家背包中
- 本质上是同一个问题：服务端盲目信任客户端数据

---

## 修复方法

### 核心思路：服务端权威化

**不信任客户端传来的任何数据，所有操作以服务端持有的状态为准。**

### 修改文件清单

| 文件 | 修改内容 |
|------|----------|
| `StockKeeperCategorySecurity.java` | **新增** — 集中式安全校验类 |
| `StockKeeperCategoryRefundPacket.java` | 委托给 Security 做会话验证 + 服务端匹配退还 |
| `StockKeeperCategoryEditPacket.java` | 委托给 Security 做事务性写入 + 背包消耗验证 |
| `StockKeeperCategoryMenu.java` | 新增 `categoryRevision` 防陈旧会话 |
| `StockKeeperCategoryScreen.java` | 新增 `submitSchedule()` + `refreshCategoryRevision()` |
| `StockTickerBlockEntity.java` | 新增 `categoryRevision` 字段 + 强制 `copyWithCount(1)` |
| `GhostItemSubmitPacket.java` | 对 StockKeeperCategoryMenu 增加 FilterItem 类型校验 |

### 防护机制

**1. StockKeeperCategorySecurity.validateSession()**

```
✅ player.containerMenu instanceof StockKeeperCategoryMenu
✅ menu.contentHolder == be                (防跨方块攻击)
✅ menu.stillValid(player)                 (距离有效)
✅ be.behaviour.mayInteract(player)        (权限有效)
✅ menu.matchesCategoryRevision(be.getCategoryRevision())  (防陈旧会话)
```

**2. StockKeeperCategorySecurity.refund()**

```
✅ count == 1
✅ instanceof FilterItem
✅ 精确匹配服务端 categories 中的条目
✅ 忽略自定义名称的模糊匹配（兜底）
→ 退还: be.categories.remove(index).copyWithCount(1)
→ 不匹配则拒绝，不返还任何物品
```

**3. StockKeeperCategorySecurity.applySchedule()**

```
✅ 事务性规划（先规划，后提交）
✅ 来源解析: 已有类别 → 背包 → 手持物品
✅ 背包必须持有对应 FilterItem 才允许添加
✅ 仅允许修改自定义名称
✅ 提交前二次校验库存一致性
→ 返回被移除的类别到玩家背包
```

### 修复前后对比

| 检查项 | 修复前 | 修复后 |
|--------|--------|--------|
| 菜单状态验证 | 无 | `instanceof StockKeeperCategoryMenu` |
| BE 一致性验证 | 无 | `menu.contentHolder == be` |
| 陈旧会话防护 | 无 | `categoryRevision` 机制 |
| 权限验证 | 仅 spectator / adventure | 增加 `mayInteract()` |
| 类别存在性验证 | 无 | 精确匹配 + 模糊匹配 |
| count 验证 | 无 | 强制 count == 1 |
| NBT 验证 | 无 | 退还服务端持有的副本 |
| 背包消耗验证 | 无 | 必须持有对应 FilterItem |

---

## 总结

#10624 是一个典型的服务端信任客户端数据漏洞。攻击者仅需获知一个已加载的 Stock Ticker 坐标（无需任何权限），即可生成任意数量的 FilterItem 并注入任意 NBT 数据。该漏洞与 #8086 同源，均源于 `StockKeeperCategoryRefundPacket` 和 `StockKeeperCategoryEditPacket` 两个网络包对客户端数据的盲目信任。

修复方案通过引入 `StockKeeperCategorySecurity` 集中式安全校验层，将"服务端权威化"原则贯穿于所有类别操作中，彻底消除了攻击面。