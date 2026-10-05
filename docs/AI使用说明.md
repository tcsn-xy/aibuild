# AIBuild：AI 统一操作手册

后来的 AI 从这里开始。建筑设计由当前对话完成；本模组只负责读取世界和可靠施工。无需模型 API、视觉决策或网络端口。

## 游戏关闭或随时进入游玩

1.6.0新增后台世界引擎。用户只需给出世界名，AI运行下面的命令；无需用户开游戏或输入connect。

```sh
python3 scripts/offline.py --instance "/path/to/instance" worlds
python3 scripts/offline.py --instance "/path/to/instance" start "示例世界"
python3 scripts/offline.py --instance "/path/to/instance" status
```

- 只加载已有26.3世界；名字不唯一时使用目录ID，不自动转换或新建世界。原世界正被游戏使用时启动会拒绝，不能创建第二个写入者。
- 后台持有Minecraft原生存档锁，备份后加载，正常保存原存档。不能直接编辑`.mca`。配置/模组在后台专用目录复制，正式图形与按键设置不变。
- 用户随时启动MC，在单人列表选择同一世界，模组会通过仅绑定`127.0.0.1`的本机连接加入正在运行的后台；不会再启动集成服务端。退出客户端不停止后台，不需要AI操纵窗口。
- 原单人玩家UUID继续用于登录与保存，避免生成一套新背包。后台只允许一个本机玩家。后台运行时生物与世界时间仍继续；图形客户端仍用原后端和资源包。
- 后台模式沿用`workflow.py`，世界、维度、会话及回滚保护不变；默认维度为主世界，启动可用`--dimension minecraft:the_nether`明确指定。无准星时必须明确建筑原点，不拿默认原点猜选址。
- 玩家进入后施工采用较小预算；玩家在搬迁范围或开着相关箱子时仍会暂停保护。其余区域可正常游玩。
- 同一实例同时只启用一个后台世界。可以正常玩另一个世界，但不能让它抢用同一桥接；先停止后台再连接另一个世界。不同世界不共用任务目录。
- 背景模式属于本机服务器连接，部分客户端模组可能按服务器连接组织地图缓存；已有单人地图文件保留。不要为了它调整用户配置。

任务完成且无人游玩时保存停止；有人游玩就保留后台。停止操作在服务端再次检查玩家及正在连接的客户端，不强踢或强杀：

```sh
python3 scripts/offline.py --instance "/path/to/instance" stop
```

等待`stopped:true,saved:true`后才可回滚JAR或恢复备份。启动、停止不等同于建筑撤销；已有施工日志在原世界中继续保存。后台备份与日志在实例`aibuild-background/`，不上传Git。此工具当前依赖macOS的Java25定位方式。

## 先确认环境

在仓库根目录运行 `python3 scripts/bridge.py --instance "/path/to/instance" status`。核对 `connected`、`updated`、`world`、`dimension`、`session`、`capabilities` 和现有任务状态。状态文件存在不代表游戏在线；10秒以上的旧状态不能作为在线依据。

Minecraft 26.3、Java25、Fabric Loader≥0.19.5、Fabric API≥0.161.0+26.3；支持本机单人世界、房主开放的局域网与本机后台世界。游戏中 `/aibuild connect` 建立连接，退出或切维度后重新连接。不能请求玩家反复靠近施工区：桥接自动加载目标及周边一圈。游戏关闭时世界不运行。

统一工具使用 Python3 标准库，支持 macOS/Linux；其他平台先使用 `bridge.py`。下面所有命令在仓库根目录执行，`--instance` 必须在子命令前；示例坐标为占位示例，不是用户的真实场地。

## 选哪个操作

| 意图 | 统一工具准备命令 | 引擎流程 | 注意 |
|---|---|---|---|
| 自己设计新建筑 | `prepare-build` | `check → build` | v1/v2施工图，保护已有方块实体 |
| 放置现成投影 | `prepare-projection` | `projection-check → projection-build` | 原图NBT、库存、受支持实体及刻时保留 |
| 搬当前建筑和箱内物品 | `prepare-move` | `move-check → move` | 使用实时快照和库存封存；不能复制再删除 |
| 明确销毁 | `prepare-demolish` | `demolish-check → demolish` | 范围内容器及物品会被销毁；必须有明确授权 |
| 撤回已做工作 | `rollback` | 按任务ID调用`undo` | 保护后续改动，可能拒绝或部分恢复 |

投影格式与 Litematica 兼容，不依赖其安装或菜单操作。Litematica 26.3 源码在 <https://github.com/sakura-ryoko/litematica/tree/26.3>，许可证LGPL-3.0；本工具未复制其源码，AIBuild仍为MIT。

## 最短操作路径

每项操作使用一个独立任务目录，目录里保存 `task.json`、`plan.json` 与 `responses/`。不要为一次未确认施工创建新目录绕过去重。

```sh
# 现成投影：原点是转换后整体最小角；rotation为绕Y轴的顺时针角度。
python3 scripts/workflow.py --instance "/path/to/instance" prepare-projection "/path/to/model.litematic" --origin 0 80 0 --rotation 90 --task tasks/example

# 对新址进行实际检查；输出范围、改动数、容器与实体信息。
python3 scripts/workflow.py --instance "/path/to/instance" check --task tasks/example

# 用户已授权这个具体位置、尺寸、替换范围后才执行。
python3 scripts/workflow.py --instance "/path/to/instance" execute --task tasks/example --confirmed
python3 scripts/workflow.py --instance "/path/to/instance" status --task tasks/example
```

检查成功后令牌约60秒有效。用户已有具体任务授权不必重复询问；检查过期就 `check --refresh` 再执行。不要在检查与执行间插入扫描、其他检查或改原点，会使令牌失效。施工图改动必须新建任务，不能沿用旧令牌。

`execute` 返回任务编号代表已提交，不代表完成。继续用 `status` 查看真实 `phase`、进度、错误和冲突。`COMPLETE` 表示施工阶段完成；机器产量、通行和使用功能需另行验收。

## 搬迁输入

```json
{
  "regions": [{"origin": [0, 80, 0], "size": [79, 40, 50]}],
  "offset": [0, -10, -30],
  "exclude": [[5, 80, 5]],
  "cleanup": [{"pos": [0, 79, 0], "expected": "minecraft:stone", "restore": "minecraft:air"}],
  "additions": [{"pos": [0, 69, -30], "expected": "minecraft:air", "state": "minecraft:stone_bricks"}]
}
```

```sh
python3 scripts/workflow.py --instance "/path/to/instance" prepare-move move-spec.json --task tasks/move-example
python3 scripts/workflow.py --instance "/path/to/instance" check --task tasks/move-example
python3 scripts/workflow.py --instance "/path/to/instance" execute --task tasks/move-example --confirmed
```

- `regions` 是绝对源范围，自动分成各轴≤64的扫描块，最多16个，总扫描体积≤524288。尽量选择紧凑范围，减少扫描空气；允许多区域但不要无必要重叠。
- `offset` 为 `[dx,dy,dz]`，非零，各轴绝对值≤512。移动不旋转建筑。源非空气方块≤262144，统一改动位置≤524288；还有256个区块票据的实际限制，过大范围可能被拒绝。
- `exclude` 是绝对位置，必须位于源区；不会搬走这些方块，也不允许目标/清理/基础覆盖它们。源区实体仍由引擎核对；实体碰到排除位置会拒绝搬迁，需重新划范围，不能默默丢弃实体。
- `cleanup` 和 `additions` 可省略为空数组，必须明确给出原状态与最终状态。源区、清理与基础不能彼此误覆盖。新建筑目标优先于清理/基础，这是最终布局规则，不能依赖它掩盖选址错误。
- 准备时只扫描方块，不读取或复制库存；`move-check` 才封存当前完整方块实体、物品组件和受支持附属实体。检查前后有变化则停止刷新。
- 搬迁支持箱桶等能无损回读的方块实体，实体限物品展示框、发光展示框、画和盔甲架；乘客关系、其他实体及运行中的活塞拒绝处理。
- 玩家站在编辑位置或正开相关容器时暂停；不传送玩家、不强关菜单。暂停后的封存库存继续锁定，必须继续或完整回滚。

别用投影复制当前满箱建筑再拆旧址：这会复制库存，且重叠目标可能被删除。用统一搬迁事务。

## 新建与销毁输入

`prepare-build` 接收现有v1/v2 JSON：

```json
{"version":1,"name":"示例","origin":[0,80,0],"rotation":0,"blocks":[{"pos":[0,0,0],"state":"minecraft:oak_planks"}]}
```

`prepare-demolish` 接收明确绝对范围，或普通施工任务的 `job`；可附旧矿车部署 `deployments`：

```json
{"origin":[0,80,0],"size":[16,16,16],"deployments":[]}
```

```sh
python3 scripts/workflow.py --instance "/path/to/instance" prepare-build plan.json --task tasks/new-example
python3 scripts/workflow.py --instance "/path/to/instance" prepare-demolish demolition.json --task tasks/remove-example
```

随后仍是 `check → execute --confirmed → status`。销毁会删除目标内的方块、容器内容和实体而不生成掉落物；不能拿它替代保留用户修改的`undo`。拆除范围最多64³，大范围必须明确拆成独立任务。任务ID选择范围只支持普通施工日志，不把`tx-`/`mv-`当普通`job`盲传。

完整投影最多64³，超限明确拆分，不截断。支持litematic格式5/6/7、多区域、负尺寸和0/90/180/270旋转。模型NBT由游戏DataFixer转换；非法状态、多格结构不完整、未知实体、可执行命令载荷和运行中的活塞快照会拒绝。装配、NBT、实体、流体与启动分阶段执行；`activation`默认空，不凭空启动所有红石。

## 恢复、暂停与回滚

```sh
python3 scripts/workflow.py --instance "/path/to/instance" pause --task tasks/example
python3 scripts/workflow.py --instance "/path/to/instance" resume --task tasks/example
python3 scripts/workflow.py --instance "/path/to/instance" cancel --task tasks/example
python3 scripts/workflow.py --instance "/path/to/instance" rollback --task tasks/example
```

统一工具保存世界、维度、计划SHA256、请求ID、原会话、任务ID和阶段；不允许同一任务目录并发运行或换实例施工。退出重进后使用同一目录 `status`，确认后继续原任务。

超时不等于失败。原请求保留为`PENDING`；重跑同一命令只等待原请求，绝不自动再发。`status`先读取原结果；施工回执丢失时用游戏端 `request.json` 与进度日志找回唯一任务。没有结果和完整回执时明确保持未确认，人工核对 `aibuild-bridge/results/` 与任务日志，不能猜任务ID重建。崩溃发生在保存请求意图与实际发出之间时也不自动重发。

旧会话结束后，未完成的只读检查可以 `check --refresh`；同会话仍未确认的检查不能重查。已发送过施工的目录只允许查询、继续或回滚，不再次开工；已明确失败且无世界修改时核对后新建任务重新检查。

回滚仅恢复仍符合施工结果的位置；普通施工/拆除可能报告`PARTIAL`，需查看冲突。搬迁若库存或展示实体被改动，会整批拒绝回滚以防物品复制。不能把取消当作已恢复，也不能把部分撤销报成无残留。

底层逃生入口：`python3 scripts/bridge.py --help`。仅在统一工具无法表达时使用；先看原任务记录，保留新请求ID和结果，不绕开会话、保护或确认。

## 效率与操作边界

- 先查状态和历史，再查少量必要数据；优先复用已完成扫描和任务结果，最后检查必须使用实时状态。
- 准备、检查、施工存在顺序依赖，按顺序执行；引擎同一时间只接受一个施工任务，不能同时发多个子代理任务抢桥接。
- 默认工具等待上限900秒，区块加载上限60秒。长核对阶段向用户简短报告进度，不反复取消/重扫；任务失败先读具体错误。
- 前台施工沿用较小预算，后台最多512格/约2ms每tick。票据在完成、暂停、取消、断开后释放；机器无人加载时仍按原版暂停。
- 不编辑运行中的`.mca`、`level.dat`或库存；世界修改只通过集成服务端线程。快照和日志可只读核对。
- 不操作玩家视角、鼠标、窗口焦点或快捷键，不切图形后端、不改其他模组/配置/存档。
- 构建使用 `./build.sh`，QA JAR仅限一次性图形客户端；无头测试服不用于本项目验收。正式JAR必须游戏关闭后备份安装，且只留一个版本。
- 正常保存退出恢复受支持；强杀/断电与区块存盘之间的原子一致性不保证。未完成搬迁必须先完成或回滚再卸载/退版。
- `tasks/`、`sites/`、库存快照、世界和私人投影不上传Git；公共文档只放通用示例。卸载模组不自动撤销建筑。
