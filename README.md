# kt-tunnel

一个基于 **Kotlin/JVM + Netty** 实现的 WS/WSS 三端隧道（`server / agent / client`），用于在公网 `server` 与内网 `agent` 之间建立 TCP 访问通道。

支持：
- 本地端口转发（`--forward`）：`listenHost:listenPort -> targetHost:targetPort`（由 agent 侧去连接 target）
- 本地 SOCKS5 代理（`--socks5`）：仅支持 `NO_AUTH` + `CONNECT`
- `ws` / `wss`（server 可配置证书或一键自签）

## 技术选型

- **Kotlin/JVM**：单一可执行 JAR 交付；代码表达力强，适合快速实现网络协议与状态机。
- **Netty 4.2**：事件驱动 + pipeline 模型，适合构建高并发长连接服务端/客户端；内置 HTTP/WebSocket/SOCKS5 codec。
- **WebSocket（ws/wss）**：统一承载控制面与数据面，部署时可复用 80/443，穿透网络环境更友好。
- **picocli**：以子命令组织 `server/agent/client`，参数声明清晰、帮助信息自动生成。
- **fastjson2**：控制面消息使用 JSON（TextWebSocketFrame）序列化/反序列化。
- **logback**：统一日志输出，便于排障。
- **Gradle + Shadow 插件**：构建 `standalone` fat-jar（包含全部依赖），便于分发运行。

## 架构设计与思路（简要）

### 1) 三端职责
- **server（公网）**
  - 同端口提供 3 个 WebSocket endpoint：`/ws/agent/control`、`/ws/agent/data`、`/ws/client/tunnel`
  - 维护 `agentId -> control channel` 在线表
  - 维护隧道状态：`pending`（等待 agent 绑定）/ `active`（已配对转发）
  - 在 `clientTunnelWS` 与 `agentDataWS` 之间**透明转发二进制帧**
- **agent（内网）**
  - 与 server 保持一条 control WS 长连接（注册、接收建隧道指令）
  - 每条隧道：先拨号连接 `targetHost:targetPort`（TCP），再建立 data WS（首包 `AGENT_DATA_BIND` 绑定 tunnelId）
  - 在 `target TCP ↔ data WS` 之间转发
- **client（本地）**
  - 按 `--forward` / `--socks5` 在本地启动 listener
  - 每接入一条本地连接就创建一条 client tunnel WS（首包 `CLIENT_TUNNEL_OPEN`）
  - **收到 server 的 `CLIENT_TUNNEL_OK` 后才开始读取/转发本地数据**，避免半连接与乱序

### 2) 连接与时序（无多路复用）

每条隧道（tunnelId）独占连接：
- `client -> server`：`/ws/client/tunnel`（1 条）
- `agent  -> server`：`/ws/agent/data`（1 条）
此外 agent 还有一条长期 `control` 连接：`/ws/agent/control`。

典型时序：
1. agent 连接 control 并发送 `AGENT_REGISTER`
2. client 建立 tunnel WS 并发送 `CLIENT_TUNNEL_OPEN`（携带 agentId/target/token）
3. server 校验 token + agent 在线后将 tunnel 置为 `pending`，并向 agent 下发 `TUNNEL_CREATE`
4. agent 连接 target TCP 成功后建立 data WS，发送 `AGENT_DATA_BIND`
5. server 绑定两端后返回 `CLIENT_TUNNEL_OK`，随后开始双向透传 BinaryWebSocketFrame

数据路径：
`local TCP ↔ clientTunnelWS ↔ server ↔ agentDataWS ↔ agent ↔ target TCP`

## 使用方法

### 1) 构建

需要 JDK 8+。

```bash
./gradlew shadowJar
```

产物：`build/libs/kt-tunnel-*-standalone.jar`

### 2) 启动 server（公网）

不启用 TLS（ws）：
```bash
java -jar build/libs/kt-tunnel-*-standalone.jar server \
  --bind 0.0.0.0:8000 \
  --token TOKEN
```

启用 TLS（wss），方式二选一：
- 指定证书（PEM）：
```bash
java -jar build/libs/kt-tunnel-*-standalone.jar server \
  --bind 0.0.0.0:8000 \
  --token TOKEN \
  --cert server.crt --key server.key
```
- 一键自签（便于本地/临时验证）：
```bash
java -jar build/libs/kt-tunnel-*-standalone.jar server \
  --bind 0.0.0.0:8000 \
  --token TOKEN \
  --self-signed-tls kttunnel
```

### 3) 启动 agent（内网）

```bash
java -jar build/libs/kt-tunnel-*-standalone.jar agent \
  --server ws://127.0.0.1:8000 \
  --token TOKEN \
  --agent-id AGENT_ID
```

不传 `--agent-id` 会自动生成 UUID 并在控制台打印 `agentId=...`。

若 server 使用 TLS（wss），`--server` 需使用 `wss://`。证书校验可选：
- `--ca ca.crt`：指定自定义 CA
- `--insecure`：跳过证书校验（仅开发环境）

### 4) 启动 client（本地）

client 需要至少提供一个 `--forward` 或 `--socks5`（可多次传入，创建多个本地 listener）。

#### 4.1 端口转发（forward）

示例：把本地 `9000` 转发到 agent 可访问的 `127.0.0.1:8080`：
```bash
java -jar build/libs/kt-tunnel-*-standalone.jar client \
  --server ws://127.0.0.1:8000 \
  --token TOKEN --agent-id AGENT_ID \
  --forward 9000:127.0.0.1:8080
```

`--forward` 支持格式（可多次传入）：
- `<listenPort>:<targetHost>:<targetPort>`（listenHost 默认 `0.0.0.0`）
- `<listenHost>:<listenPort>:<targetHost>:<targetPort>`

#### 4.2 SOCKS5 代理（socks5）

启动本地 SOCKS5（示例：监听 `127.0.0.1:1080`）：
```bash
java -jar build/libs/kt-tunnel-*-standalone.jar client \
  --server ws://127.0.0.1:8000 \
  --token TOKEN --agent-id AGENT_ID \
  --socks5 127.0.0.1:1080
```

`--socks5` 支持格式（可多次传入）：
- `<port>`（listenHost 默认 `0.0.0.0`）
- `<listenHost>:<port>`

说明：当前 SOCKS5 仅支持 `NO_AUTH` 与 `CONNECT`，不支持 UDP/鉴权等扩展。

### 5) 查看帮助

```bash
java -jar build/libs/kt-tunnel-*-standalone.jar --help
java -jar build/libs/kt-tunnel-*-standalone.jar server --help
java -jar build/libs/kt-tunnel-*-standalone.jar agent --help
java -jar build/libs/kt-tunnel-*-standalone.jar client --help
```

## 限制与注意事项

- **无多路复用**：每条本地连接会新建一条 client tunnel WS；每条隧道在 agent 侧也会新建一条 data WS + 一条 target TCP。
- **鉴权为共享 token（MVP）**：建议配合 TLS（wss）使用，避免明文传输。
- **TLS 自签证书**：默认不受信任；可用 `--insecure` 临时跳过校验，或把 CA 通过 `--ca` 传给 client/agent。

## 更多文档

- 详细实现说明与协议细节：`doc/kotlin-netty-picocli-wss-tunnel.md`
