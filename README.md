# CraftEnginePackGate

CraftEnginePackGate is a Velocity plugin that sends the latest uploaded CraftEngine resource pack from the proxy layer. It reads CraftEngine upload cache files, selects the newest valid URL/SHA-1 pair, and sends a single network resource pack to players after they connect to configured backend servers.

CraftEnginePackGate 是一个 Velocity 插件，用于在代理层下发最新的 CraftEngine 上传资源包。它读取 CraftEngine 上传缓存文件，选择最新有效的 URL/SHA-1，并在玩家进入指定后端后发送一个统一的网络资源包。

## Why It Exists / 设计目标

Backend resource-pack sending can easily create duplicate prompts in a proxy network. This plugin centralizes delivery in Velocity while still letting CraftEngine own the pack upload process.

在代理网络中由后端发送资源包很容易导致重复弹窗。本插件将资源包下发集中到 Velocity，同时仍保留 CraftEngine 负责上传资源包。

## Platform / 平台

- Proxy: Velocity
- Reads backend files: `plugins/CraftEngine/cache/gitlab.json`
- Optional refresh channel: `craftengine_pack_gate:refresh`

## Core Behavior / 核心行为

- reads one or more CraftEngine cache files;
- chooses the newest valid cache file by last-modified time;
- validates SHA-1 before sending;
- sends the pack once per player/session/SHA;
- tracks pending pack offers and resource-pack status;
- ignores the auth/limbo server;
- can reload cache and resend when `CraftEngineUnifiedPack` sends a refresh message.

核心行为：

- 读取一个或多个 CraftEngine cache 文件；
- 按修改时间选择最新有效 cache；
- 下发前校验 SHA-1；
- 同一玩家同一会话同一 SHA 只发送一次；
- 跟踪 pending 状态与客户端资源包响应；
- 跳过认证/limbo 服务器；
- 可接收 `CraftEngineUnifiedPack` 刷新消息并重新读取 cache、重发资源包。

## Configuration / 配置

Default `config.properties`:

```properties
force=false
prompt=服务器材质包已更新，请加载以显示自定义物品和模型。
auth-server=ellan-limbo
minecraft-root=..
auto-cache=true
packs=network
pack.network.url=https://example.invalid/resource_pack.zip
pack.network.sha1=0000000000000000000000000000000000000000
pack.network.id=4a475da6-9760-4fc7-91af-10dd2d5725b4
pack.network.servers=ellan-spawn,ellan-survival,ellan-redstone,ellan-adventure
pack.network.cache-files=01-spawn/plugins/CraftEngine/cache/gitlab.json,02-survival/plugins/CraftEngine/cache/gitlab.json,03-redstone/plugins/CraftEngine/cache/gitlab.json,04-adventure/plugins/CraftEngine/cache/gitlab.json
```

Important fields:

- `force`: whether Minecraft should require the pack.
- `prompt`: text shown in the client resource-pack prompt.
- `auth-server`: backend name that should not receive the pack.
- `minecraft-root`: path used to resolve relative cache files.
- `auto-cache`: when true, cache files override static URL/SHA fallback values.
- `pack.<name>.id`: stable UUID for the resource pack offer.
- `pack.<name>.servers`: backend server names that should receive the pack.

重要字段：

- `force`：是否强制客户端加载资源包。
- `prompt`：客户端资源包弹窗提示文本。
- `auth-server`：不发送资源包的认证服务器。
- `minecraft-root`：解析相对 cache 路径的服务器根目录。
- `auto-cache`：开启后 cache 文件会覆盖静态 URL/SHA 兜底值。
- `pack.<name>.id`：资源包 offer 使用的稳定 UUID。
- `pack.<name>.servers`：需要接收资源包的后端服务器名。

## Refresh Integration / 刷新联动

Version 1.2.0 registers this plugin message channel:

```text
craftengine_pack_gate:refresh
```

When it receives `resend` or `resend:<sha>`, it reloads config/cache state, clears already-applied session markers, and resends the current pack to online players on configured backend servers.

1.2.0 注册了上述插件消息通道。当收到 `resend` 或 `resend:<sha>` 时，它会重新读取配置和 cache，清空本会话已应用标记，并向当前在线且位于目标后端的玩家重发当前资源包。

## Deployment / 部署

1. Copy the jar to Velocity's `plugins` directory.
2. Ensure no older jar with the same plugin id remains active.
3. Restart Velocity.
4. Confirm startup log contains `CraftEnginePackGate enabled`.
5. Join a configured backend and watch for `Sent CraftEngine resource pack ...` in Velocity logs.

中文步骤：

1. 将 jar 复制到 Velocity 的 `plugins` 目录。
2. 确认没有旧版本同 id jar 同时存在。
3. 重启 Velocity。
4. 确认日志出现 `CraftEnginePackGate enabled`。
5. 进入配置的后端服务器，并查看 Velocity 日志中是否出现 `Sent CraftEngine resource pack ...`。

## Automated Builds / 自动构建

GitHub Actions builds the plugin on every push to `main`/`master`, every pull request, and manual workflow runs. The compiled jar is uploaded as a workflow artifact.

GitHub Actions 会在每次推送到 `main`/`master`、每个 pull request，以及手动运行 workflow 时自动构建插件，并把编译好的 jar 上传为 workflow artifact。

To publish a GitHub Release, push a version tag:

```powershell
git tag v1.3.3
git push origin v1.3.3
```

推送 `v*` 版本标签后，workflow 会自动创建同名 GitHub Release，并把 jar 上传到 release assets。

## Verification / 验证

- Cache files contain valid JSON with `url` and `sha1`.
- SHA-1 is exactly 40 hex characters.
- The newest cache file corresponds to the pack you expect.
- Velocity sends only after the player reaches a configured backend, not while in limbo.

- cache 文件应包含带 `url` 和 `sha1` 的有效 JSON。
- SHA-1 必须是 40 位十六进制字符串。
- 最新修改的 cache 文件应对应你期望下发的资源包。
- Velocity 应在玩家进入目标后端后发送，而不是在 limbo 阶段发送。

## Troubleshooting / 排障

- If no pack is sent, check backend server names in `pack.network.servers`.
- If the wrong pack is sent, inspect modification times of all configured cache files.
- If players do not receive refreshes, confirm PackGate 1.2.0 is running and a backend plugin message reached Velocity.
- If duplicate prompts occur, ensure backend CraftEngine resource-pack sending is disabled.

- 如果没有发送资源包，检查 `pack.network.servers` 中的后端名称。
- 如果发送了旧包，检查所有 cache 文件的修改时间。
- 如果刷新没有重发，确认 Velocity 正在运行 PackGate 1.2.0，并且后端插件消息到达了 Velocity。
- 如果出现重复弹窗，确认后端 CraftEngine 自己的资源包发送已关闭。
