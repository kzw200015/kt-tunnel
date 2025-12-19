# Kotlin + Netty + picocli：WS/WSS 三端内网穿透（无多路复用）实现说明

> 本文档以当前仓库代码为准（Kotlin/JVM；Netty + picocli + fastjson2）。  
> - 同时支持 `ws` 与 `wss`：server 同时提供 `--cert` 与 `--key` 时启用 TLS（wss）；否则为 ws。  
> - **不做多路复用**：client 每接入一条本地 TCP 连接，就新建一条 **Client Tunnel WebSocket（WS/WSS）** 到 server。  
> - agent 维持一条 **Agent Control WebSocket（WS/WSS）长连接**；每条隧道再新建一条 **Agent Data WebSocket（WS/WSS）**。  
> - `tunnelId` 由 **client 生成（UUID）**，首包携带。  
> - **统一规范**的 `type` 命名（全大写、统一前缀）。  
> - 去掉 `TUNNEL_CREATE_OK`：成功以 **`AGENT_DATA_BIND` 到位** 为准。  
> - **server 等 `AGENT_DATA_BIND` 成功绑定后才回 `CLIENT_TUNNEL_OK`**，client 收到 OK 才开始透传数据。

---

## 1. 总体架构与连接模型

### 1.1 三端职责
- **server（公网）**  
  - 维护在线 agent 的控制连接（agentId → control channel）  
  - 维护隧道状态表（pending/active）  
  - 在 clientTunnelWS 与 agentDataWS 之间 **转发二进制帧**（透明传输）
- **agent（内网）**  
  - 建立并保持 **Agent Control WS/WSS** 长连接  
  - 收到建隧道命令后，先连接内网 `targetHost:targetPort`  
  - 成功后建立 **Agent Data WS/WSS**，首包 `AGENT_DATA_BIND` 绑定 tunnelId  
  - 之后在 `target TCP ↔ agentDataWS` 间透传
- **client（本地）**  
  - 监听本地 `listenHost:listenPort`  
  - 每 accept 一条本地连接：生成 `tunnelId(UUID)`，新建 **Client Tunnel WS/WSS**  
  - 首包发 `CLIENT_TUNNEL_OPEN`，等待 `CLIENT_TUNNEL_OK`  
  - 收到 OK 后在 `local TCP ↔ clientTunnelWS` 间透传

### 1.2 WebSocket 通道（WS/WSS）
| 通道 | 方向 | Endpoint | 用途 |
|---|---|---|---|
| Agent Control | agent → server | `/ws/agent/control` | 注册/心跳/接收创建指令/回报失败 |
| Agent Data | agent → server | `/ws/agent/data` | **每条隧道 1 条**，二进制透传 |
| Client Tunnel | client → server | `/ws/client/tunnel` | **每条本地连接 1 条**，二进制透传 |

> 数据路径：`local TCP ↔ clientTunnelWS ↔ server ↔ agentDataWS ↔ agent ↔ target TCP`

---

## 2. CLI 设计（picocli 三子命令）

打包成单一可执行 JAR：`build/libs/kt-tunnel-*-standalone.jar`（Gradle ShadowJar 输出）

### 2.1 server
```bash
java -jar build/libs/kt-tunnel-*-standalone.jar server   --bind 0.0.0.0 --port 7000   --token TOKEN   --cert server.crt --key server.key
```

或使用自签证书快速启用 TLS（wss）：
```bash
java -jar build/libs/kt-tunnel-*-standalone.jar server   --bind 0.0.0.0 --port 7000   --token TOKEN   --self-signed-tls SERVER_HOST
```
注意：自签证书默认不被信任；client/agent 需要使用 `--insecure`，或将 server 日志里打印的 `cert` 文件路径作为 `--ca` 传入。

参数（与代码一致）：
- `--bind` 监听地址（默认 0.0.0.0）
- `--port` 监听端口
- `--token` 共享密钥（MVP 简化鉴权）
- `--cert/--key` PEM 格式证书与私钥（两者必须同时提供才启用 TLS/wss）
- `--self-signed-tls [HOST]` 生成临时自签证书并启用 TLS/wss（默认 HOST=localhost；与 `--cert/--key` 互斥）
- `--pending-timeout-seconds` pending 超时（默认 10 秒）：client OPEN 后等待 agent DATA_BIND 的最大时长

### 2.2 agent
```bash
java -jar build/libs/kt-tunnel-*-standalone.jar agent   --server-host SERVER_IP --server-port 7000   --token TOKEN   --agent-id AGENT_ID
```

参数（与代码一致）：
- `--agent-id` 可选：不传则生成 UUID 并打印
- TLS（wss）：
  - `--tls` 显式启用 TLS（使用 JDK 默认信任链）
  - `--ca ca.crt` 指定自定义 CA（同时也会启用 TLS）
  - `--insecure` 跳过校验（同时也会启用 TLS；仅开发环境使用）
  - 只要传了 `--tls` / `--ca` / `--insecure` 任一项，就会使用 `wss`

### 2.3 client
```bash
java -jar build/libs/kt-tunnel-*-standalone.jar client   --server-host SERVER_IP --server-port 7000   --token TOKEN   --agent-id AGENT_ID   --forward 9000:127.0.0.1:8080
```

参数（与代码一致）：
- `--forward` / `--socks5` 至少提供一个（可多次传入，创建多个本地 listener）
- TLS（wss）：同 `agent`（`--tls` / `--ca` / `--insecure` 任一项即启用）

`--forward` 支持两种格式（listenHost 可选，默认 0.0.0.0；支持多次传入参数）：
- `<listenPort>:<targetHost>:<targetPort>` 例如 `9000:127.0.0.1:8080`
- `<listenHost>:<listenPort>:<targetHost>:<targetPort>` 例如 `127.0.0.1:9000:127.0.0.1:8080`

`--socks5` 支持两种格式（listenHost 可选，默认 0.0.0.0；支持多次传入参数）：
- `<port>` 例如 `1080`
- `<listenHost>:<port>` 例如 `127.0.0.1:1080`

---

## 3. 统一消息协议（Text JSON + Binary 数据）

### 3.1 统一命名规范
- `type` 全大写
- 前缀固定：
  - `AGENT_*` agent 行为
  - `CLIENT_*` client 行为
  - `TUNNEL_*` 隧道控制指令/错误

### 3.2 控制消息使用 TextWebSocketFrame（JSON）
通用字段：
- `type`（必填）
- `tunnelId`（按消息需要）
- `agentId`（按消息需要）
- `token`（client/agent 发起请求时携带）
- `code/message`（错误返回）

### 3.3 透传数据使用 BinaryWebSocketFrame
- 所有业务数据均为 **binary frame**
- 建议 chunk：`16KB~64KB`（吞吐与延迟的折中）
- server 不解析 binary 内容，原样转发

---

## 4. 消息定义（精简版：无 TUNNEL_CREATE_OK）

### 4.1 Agent Control（/ws/agent/control）
#### 4.1.1 注册
Agent → Server：
```json
{"type":"AGENT_REGISTER","agentId":"A1","token":"TOKEN"}
```

Server → Agent：
```json
{"type":"AGENT_REGISTER_OK"}
```
或
```json
{"type":"AGENT_REGISTER_ERR","code":401,"message":"BAD_TOKEN"}
```

#### 4.1.2 创建隧道指令
Server → Agent：
```json
{"type":"TUNNEL_CREATE","tunnelId":"<uuid>","targetHost":"127.0.0.1","targetPort":8080}
```

#### 4.1.3 创建隧道失败（仅失败时）
Agent → Server：
```json
{"type":"TUNNEL_CREATE_ERR","tunnelId":"<uuid>","code":502,"message":"DIAL_FAILED"}
```

> 成功不需要发 OK。成功由后续 `AGENT_DATA_BIND` 表达。

---

### 4.2 Client Tunnel（/ws/client/tunnel）
Client → Server 首包（必须第一帧，Text）：
```json
{
  "type":"CLIENT_TUNNEL_OPEN",
  "tunnelId":"<uuid>",
  "agentId":"A1",
  "targetHost":"127.0.0.1",
  "targetPort":8080,
  "token":"TOKEN"
}
```

Server → Client：
- 成功（**必须等 DATA_BIND 已绑定完成**）：
```json
{"type":"CLIENT_TUNNEL_OK","tunnelId":"<uuid>"}
```
- 失败：
```json
{"type":"CLIENT_TUNNEL_ERR","tunnelId":"<uuid>","code":404,"message":"AGENT_OFFLINE"}
```
```json
{"type":"CLIENT_TUNNEL_ERR","tunnelId":"<uuid>","code":401,"message":"BAD_TOKEN"}
```
```json
{"type":"CLIENT_TUNNEL_ERR","tunnelId":"<uuid>","code":504,"message":"HANDSHAKE_TIMEOUT"}
```

Client 只有在收到 `CLIENT_TUNNEL_OK` 后才开始发送 binary 数据。

---

### 4.3 Agent Data（/ws/agent/data）
Agent → Server 首包（必须第一帧，Text）：
```json
{"type":"AGENT_DATA_BIND","tunnelId":"<uuid>","agentId":"A1","token":"TOKEN"}
```

Server → Agent：
- 成功：
```json
{"type":"AGENT_DATA_BIND_OK","tunnelId":"<uuid>"}
```
- 失败（例如无对应 pending）：
```json
{"type":"AGENT_DATA_BIND_ERR","tunnelId":"<uuid>","code":404,"message":"NO_SUCH_TUNNEL"}
```

绑定成功后进入 binary 透传。

---

## 5. 严格时序（server 等 DATA_BIND 才回 client OK）

1) agent 建立 `/ws/agent/control`，发送 `AGENT_REGISTER`，server 回 `AGENT_REGISTER_OK`  
2) client accept 本地 TCP 连接，生成 `tunnelId=UUID`  
3) client 建立 `/ws/client/tunnel`，首包发送 `CLIENT_TUNNEL_OPEN`（含 tunnelId）  
4) server 校验 token/ACL，检查 agent 在线后，向 agent control 下发 `TUNNEL_CREATE(tunnelId,target)`  
5) agent 收到 `TUNNEL_CREATE`：
   - dial target 失败 → control 回 `TUNNEL_CREATE_ERR(tunnelId,...)`，结束
   - dial target 成功 → 建立 `/ws/agent/data`，首包发 `AGENT_DATA_BIND(tunnelId,...)`
6) server 收到 `AGENT_DATA_BIND` 并校验通过：
   - 将 `clientWS ↔ agentDataWS` 绑定为 active
   - **此刻才**回 client：`CLIENT_TUNNEL_OK(tunnelId)`
7) client 收到 OK 后才开始二进制透传  
8) server 在两条 WS 之间转发 binary frames  
9) 任一侧关闭，全链路关闭并清理

---

## 6. server 侧状态表与超时

### 6.1 在线 agent 表
- `ConcurrentHashMap<String, AgentControl>`：
  - `agentId -> { Channel controlCh, Instant lastSeen }`

### 6.2 隧道表（以 tunnelId 为 key）
- `pending: ConcurrentHashMap<String, PendingTunnel>`
- `active: ConcurrentHashMap<String, ActiveTunnel>`

建议结构：
- `PendingTunnel`：
  - `String tunnelId`
  - `String agentId`
  - `String targetHost`
  - `int targetPort`
  - `Channel clientCh`（client tunnel ws）
  - `ScheduledFuture<?> timeoutTask`
  - `Instant createdAt`
- `ActiveTunnel`：
  - `Channel clientCh`
  - `Channel agentDataCh`

### 6.3 事件处理规则

#### 6.3.1 client OPEN
当 server 在 client tunnel WS 收到 `CLIENT_TUNNEL_OPEN`：
1. token 校验失败：回 `CLIENT_TUNNEL_ERR(401)` 并关闭 clientCh
2. agent 不在线：回 `CLIENT_TUNNEL_ERR(404)` 并关闭
3. tunnelId 重复：回 `CLIENT_TUNNEL_ERR(409,"DUPLICATE_TUNNEL_ID")` 并关闭
4. 创建 `PendingTunnel`，加入 pending
5. 通过 agent control 下发 `TUNNEL_CREATE`
6. 启动超时任务（建议 10 秒）：
   - 超时仍未绑定 data → 回 `CLIENT_TUNNEL_ERR(504)`，关闭 clientCh，删除 pending

#### 6.3.2 agent create err
收到 `TUNNEL_CREATE_ERR(tunnelId)`：
- 若 pending 存在：
  - 回 client `CLIENT_TUNNEL_ERR(code,message)`
  - 关闭 clientCh
  - 删除 pending（取消 timeoutTask）
- 若不存在：忽略（可能已超时清理）

#### 6.3.3 agent data bind
收到 `AGENT_DATA_BIND(tunnelId,agentId,token)`：
1. token 校验失败 → 回 `AGENT_DATA_BIND_ERR(401)`，关闭 dataCh
2. pending 不存在 → 回 `AGENT_DATA_BIND_ERR(404)`，关闭 dataCh
3. pending.agentId 与 bind.agentId 不一致 → 回 `AGENT_DATA_BIND_ERR(403)`，关闭 dataCh；并给 client 回 ERR/关闭
4. 绑定成功：
   - 从 pending 移除，取消 timeoutTask
   - 加入 active（保存 clientCh 与 dataCh）
   - 回 client `CLIENT_TUNNEL_OK`
   - 回 agent `AGENT_DATA_BIND_OK`
   - 启动 binary 转发（见第 8 节）

---

## 7. 关闭语义（简单全关闭，无 half-close）

触发条件（任意一个发生都关闭整个隧道）：
- client local TCP 关闭
- client tunnel WS 关闭或异常
- agent target TCP 关闭
- agent data WS 关闭或异常
- server 任一侧 WS 写失败/异常

关闭动作：
- server：关闭 active 对应的 `clientCh` 与 `agentDataCh`，删除 active 记录
- agent：data WS 关闭时关闭 target TCP；target TCP 关闭时关闭 data WS
- client：localConn 关闭时关闭 clientWS；clientWS 关闭时关闭 localConn

---

## 8. Netty 实现要点（Channel Pipeline 设计）

### 8.1 server（WS/WSS）端：三个 endpoint 共用一个端口
当前实现：一个 `ServerBootstrap` + 升级前用 path 路由（HTTP 阶段）分发到不同 WS endpoint。

基础 pipeline（升级前，所有连接共用）：
1. `SslHandler`（可选；server 提供 `--cert/--key` 时启用）
2. `HttpServerCodec`
3. `HttpObjectAggregator(65536)`
4. `WsPathRouterHandler`（按 path 安装对应的 `WebSocketServerProtocolHandler` + endpoint handler）

> 建议通过 path 区分：  
> - `/ws/agent/control`  
> - `/ws/agent/data`  
> - `/ws/client/tunnel`

#### 8.1.1 /ws/agent/control
- `AgentControlHandler`：只处理 `TextWebSocketFrame`（JSON）
  - `AGENT_REGISTER` 建立 agentId → channel 绑定
  - `TUNNEL_CREATE_ERR` 转发到 pending client 并清理
- 心跳：
  - 可用 WS ping/pong 或自定义 `AGENT_HEARTBEAT`（可选）

#### 8.1.2 /ws/agent/data
当前实现为单 handler（`AgentDataHandler`）：
- 第一帧必须是 `TextWebSocketFrame` 的 `AGENT_DATA_BIND`
- bind 成功后只转发 `BinaryWebSocketFrame`（原样转发到对应 clientCh）
- close/exception：触发隧道清理与联动关闭

#### 8.1.3 /ws/client/tunnel
当前实现为单 handler（`ClientTunnelHandler`）：
- 第一帧必须是 `CLIENT_TUNNEL_OPEN`
- 建 pending、下发 `TUNNEL_CREATE`、启动超时
- 未 OK 前收到 `BinaryWebSocketFrame`：直接关闭（协议违规）
- bind active 后转发 `BinaryWebSocketFrame` 到 agentDataCh

> 实现技巧：使用 Netty `AttributeKey<String>` 在 channel 上保存 `tunnelId`，close 时快速清理。

---

### 8.2 agent（客户端）
agent 需要两个 WS 客户端能力：

#### 8.2.1 Control WS（长连接）
pipeline（WS/WSS client）：
1. `SslHandler`（JDK 默认信任链 / `--ca` / `--insecure`）
2. `HttpClientCodec`
3. `HttpObjectAggregator`
4. `WebSocketClientProtocolHandler(uri=/ws/agent/control)`
5. `AgentControlClientHandler`

`AgentControlClientHandler`：
- handshake 完成：发送 `AGENT_REGISTER`
- 收到 `TUNNEL_CREATE`：
  - Netty `Bootstrap` dial target
  - dial 成功：创建 data WS（下一节）
  - dial 失败：在 control 上回 `TUNNEL_CREATE_ERR`

#### 8.2.2 Data WS（每条隧道一条）
- 建连 `/ws/agent/data` 后第一帧发送 `AGENT_DATA_BIND`
- 绑定成功后开始透传：
  - target TCP 入站 ByteBuf → `BinaryWebSocketFrame` 写到 dataWS
  - dataWS 收到 binary → 写回 target TCP

建议：
- 每个 tunnel 一个上下文：`TunnelCtx { tunnelId, Channel targetCh, Channel dataWsCh }`
- 任一侧关闭：关闭另一侧并清理 ctx

---

### 8.3 client（客户端）
client 需要：
- 本地 listener（Netty `ServerBootstrap`）：
  - `--forward`：TCP listener
  - `--socks5`：SOCKS5 listener（每个 CONNECT 请求映射为一条隧道）
- 每 accept 一条本地连接创建一条 client tunnel WS/WSS

流程：
1. local accept → 生成 tunnelId(UUID)
2. 建连 `/ws/client/tunnel`
3. handshake 完成发送 `CLIENT_TUNNEL_OPEN`
4. 收到 `CLIENT_TUNNEL_OK` 才启用 relay（local ↔ ws）

---

## 9. JSON 编解码与类型安全

当前实现使用 fastjson2：
- 入站：`JSON.parseObject(text)` → `JSONObject`，先取 `type` 再按 type 提取字段
- 出站：使用 Kotlin `data class` + `JSON.toJSONString(...)` 生成 JSON 文本

必须校验：
- `tunnelId`：UUID 格式
- `targetPort`：1..65535
- `token`：必填，长度上限
- `targetHost`：必填，长度上限

---

## 10. 超时、限流与保护（当前实现 + 可选增强）

当前实现：
- `pending` 超时：默认 10 秒，可通过 server 的 `--pending-timeout-seconds` 配置
- HTTP 聚合器：`HttpObjectAggregator(65536)`
- WS 帧大小：`maxFramePayloadLength = 1MB`（Text/Binary 共用上限）
- 未 OK 前收到 binary：直接关闭（server/client 均如此）

可选增强（未在当前代码中实现）：
- 最大 pending/active 数量
- `WriteBufferWaterMark` 控制写队列内存

---

## 11. 错误码建议
- 401 `BAD_TOKEN`
- 403 `AGENT_MISMATCH`
- 404 `AGENT_OFFLINE` / `NO_SUCH_TUNNEL`
- 409 `DUPLICATE_TUNNEL_ID`
- 502 `DIAL_FAILED`
- 504 `HANDSHAKE_TIMEOUT`

---

## 12. 项目结构（当前实现）

```
kt-tunnel/
  build.gradle.kts
  src/main/kotlin/
    Main.kt
    Logging.kt
    NettyIoExceptions.kt

    cli/
      RootCmd.kt
      ServerCmd.kt
      AgentCmd.kt
      ClientCmd.kt
      ForwardConverter.kt
      Socks5ListenConverter.kt

    common/
      MsgTypes.kt
      Messages.kt
      Protocol.kt

    server/
      ServerApp.kt
      AgentRegistry.kt
      TunnelRegistry.kt
      handler/
        WsServerInitializer.kt
        WsPathRouterHandler.kt
        AgentControlHandler.kt
        AgentDataHandler.kt
        ClientTunnelHandler.kt

    agent/
      AgentApp.kt
      AgentTunnelManager.kt
      TunnelContext.kt
      AgentControlClientInitializer.kt
      AgentControlClientHandler.kt
      AgentDataClientInitializer.kt
      AgentDataClientHandler.kt
      TargetInitializer.kt
      TargetToWsRelayHandler.kt

    client/
      ClientApp.kt
      ClientTunnelConnector.kt
      ClientTunnelContext.kt
      ClientTunnelWsInitializer.kt
      ClientTunnelWsHandler.kt
      LocalServerInitializer.kt
      LocalInboundRelayHandler.kt
      Forward.kt
      Socks5Listen.kt
      Socks5ServerInitializer.kt
      Socks5ProxyHandler.kt

    tls/
      ClientSslContexts.kt
```

---

## 13. MVP 实现顺序
1) server：WS/WSS 启动 + 三 endpoint WS 握手
2) agent：control 注册 + server 维护在线表
3) client：建立 client tunnel WS，发送 OPEN，server 能下发 TUNNEL_CREATE
4) agent：dial 失败能回 ERR，client 收到 ERR
5) agent：dial 成功 data bind，server bind 后才回 client OK
6) binary 透传：client ↔ server ↔ agent ↔ target
7) close 传播与清理：任一侧断开，全链路关闭

## 14. 验收标准
使用 iperf3 完成打流测试才算通过：在 agent 侧起 `iperf3 -s -p 5201`，在 client 侧用 `--forward 5202:127.0.0.1:5201` 建立转发后运行 `iperf3 -c 127.0.0.1 -p 5202`，需要吞吐量正常、无丢包。
---

**版本信息**
- v1：无多路复用；每隧道两条 WS（clientTunnel + agentData）+ agentControl 长连接；server 等 DATA_BIND 才回 OK
