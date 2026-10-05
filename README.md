# AIBuild：Codex → Minecraft 建筑桥接

在 Codex 等本地编程助手中描述建筑，助手读取 Minecraft 的实际方块数据、生成施工图，经你确认后分批施工。通过本地文件交换请求，不需要模型 API Key，也不开放网络端口。

适用于 **Minecraft 26.3 / Fabric / Java 25**，面向本机单人世界及房主开放的局域网。MIT 开源。

## 当前状态

源码版本为 `1.6.0+mc26.3`。新增无窗口后台世界：不用启动游戏也能施工，中途从单人列表选择同一世界即可加入，退出后后台继续。后台19项、普通单人菜单与施工5项检查通过；Python25项通过，1项可选原图用例跳过。验证边界见 [1.6.0记录](docs/验证记录-1.6.0.md)。

后续 AI 的统一入口：[AI 使用说明](docs/AI使用说明.md)。包含操作选择、命令示例、输入格式、恢复和限制。

## 能做什么

- `/aibuild connect` 建立世界、维度和会话绑定；支持读取选址、检查施工图、施工、暂停、恢复、取消和撤销。
- 使用服务端线程改变方块，自动加载任务区块，任务结束后释放加载票据。
- 支持方块状态、方向、分阶段红石／液体更新，以及完整 `.litematic` 导入。
- 投影保留已支持的方块实体数据、容器库存和实体；不支持的数据明确拒绝。
- 提供带快照的明确范围拆除，以及现场建筑搬迁。搬迁先封存库存，统一处理源址与目标重叠，再恢复容器和附属实体。
- 前台施工降低每tick预算；连接时避免失焦自动弹菜单，手动Esc正常。

## 构建

需要JDK25。首次构建需要下载Gradle、Minecraft和Fabric依赖。

```sh
./gradlew build qaJar
```

Windows可使用已安装的Gradle运行 `gradle build qaJar`。macOS也可运行 `./build.sh`。

正式模组产物位于 `build/libs/ai-builder-1.6.0+mc26.3.jar`。`aibuild-qa.jar` 是隔离验收辅助模组，不安装到正常游玩的实例。

依赖：Fabric Loader至少0.19.5，Fabric API至少0.161.0+26.3。关闭游戏后，把正式JAR放入实例的 `mods/`，只保留一个桥接版本。

## 游戏关闭时施工

告诉助手世界名即可。助手运行 `python3 scripts/offline.py start "世界名"`，随后沿用统一施工工具。你随时启动MC，在单人列表选择该世界即可加入同一后台；不会打开第二份存档。连接仅绑定本机127.0.0.1，沿用原玩家UUID与库存。后台运行期间世界时间继续。无人游玩时可以 `python3 scripts/offline.py stop` 保存退出。

此模式需要安装本版本并重启客户端一次；完整操作、备份和限制见 [AI使用说明](docs/AI使用说明.md)。

## 游戏使用

1. 进入允许命令的本机世界，房主输入 `/aibuild connect`。
2. 在助手对话中描述建筑。助手读取场地并展示位置、尺寸和替换范围。
3. 你确认后，助手发送施工任务。
4. `/aibuild pause` 暂停，`/aibuild resume <任务ID>` 继续，`/aibuild undo <任务ID>` 撤销。
5. `/aibuild disconnect` 断开。

连接期间，即使切出窗口或打开菜单，世界仍运行，生物和时间继续。断开后恢复原暂停行为，不持久修改游戏选项。退出世界或换维度后需要重新连接。

## 本地命令行工具

优先使用 `python3 scripts/workflow.py --help`：投影、搬迁、新建与拆除共用准备→检查→执行→状态流程。具体示例见 [AI 使用说明](docs/AI使用说明.md)。

需要Python3，核心工具只使用标准库。默认实例路径为macOS的 `~/Library/Application Support/minecraft/versions/26.3-Fabric`；其他位置使用 `--instance` 指定。

```sh
python3 scripts/bridge.py --instance "/path/to/instance" status
python3 scripts/bridge.py --instance "/path/to/instance" scan --origin 0 64 0 --size 16 16 16 --out build/site.json
python3 scripts/design.py house --origin 0 64 0 --out build/house.json
python3 scripts/bridge.py --instance "/path/to/instance" check build/house.json --out build/check.json
# 确认实际施工范围后，使用检查返回的令牌：
python3 scripts/bridge.py --instance "/path/to/instance" build TOKEN --confirmed
```

基础施工图示例：

```json
{"version":1,"name":"示例","origin":[0,64,0],"rotation":0,"blocks":[{"pos":[0,0,0],"state":"minecraft:oak_planks"}]}
```

`version:2` 支持有序阶段、`deferred/live` 更新和阶段等待。`scripts/projection.py --help` 提供 `.litematic` 到 `version:3` 施工图的转换；`bridge.py --help` 列出完整投影、拆除和搬迁命令。

普通施工图及单个读取区域最多64×64×64。搬迁可由多个不超过64格的源区域组成，统一保存源址／目标布局。未知方块、非法状态、不完整的门床、无效支撑和未适配实体会被拒绝。

## 数据与恢复

通信目录为实例的 `aibuild-bridge/`。日志保存在世界目录的 `aibuild-data/<维度>/`，包含任务快照和进度。请求绑定当前世界、维度和随机会话，旧会话请求不能施工。

撤销会保护后续玩家改动。搬迁回滚发现后续库存或展示实体变化时，会拒绝整批恢复，避免复制物品。暂停搬迁后，处于中间阶段的库存继续封存，必须用同一任务继续或回滚。正常保存退出恢复已有测试；强杀和断电与Minecraft区块保存之间的事务一致性不作保证。

不直接编辑运行中的存档。卸载模组不会删除已建建筑；有未完成搬迁时，应先完成或回滚，再卸载。

施工完成不等于红石机器长期产量或跨版本兼容已验证。任务结束释放加载票据后，机器按Minecraft正常的区块模拟规则运行。

## 离线检查

```sh
./gradlew fingerprintQA inputBudgetQA moveIndexQA
python3 -m unittest discover -s tests
```

Java检查使用真实Minecraft类型，但不启动游戏或测试服务器。可选投影集成用例通过 `AIBUILD_LITEMATIC_FIXTURE` 指定本地原图；未提供时跳过。图形客户端控制代码位于 `src/qa/`，个人实例、真实原图、存档、库存快照和本地验收报告不在仓库中。

## 许可证

项目源码使用 [MIT](LICENSE)。Gradle Wrapper 的许可见 [第三方说明](THIRD_PARTY_NOTICES.md)。Minecraft及构建依赖由构建工具下载，不随仓库分发。
