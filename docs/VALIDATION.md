# 本机验收记录

验收日期：2026-10-23。以下记录来自本机真实运行，覆盖功能、安全约束及容器启动，不是性能压测或生产可用性报告。测试账号、房间和消息均为专门生成的测试数据。

## 自动化测试结果

| 验证项 | 实际结果 |
| --- | --- |
| `./mvnw -B -q verify` | 13 项 JUnit 测试全部通过：6 项 API 集成测试、7 项实时安全测试 |
| Docker 镜像构建 | 多阶段 Dockerfile 实际构建成功；构建阶段使用 `dependency:resolve` 解析依赖 |
| 真实依赖启动 | PostgreSQL 16、Redis 7、应用 A（8080）及应用 B（8081）均为 healthy |
| `tests/e2e_smoke.py` | 使用真实 HTTP/WebSocket、PostgreSQL 和 Redis 的双实例端到端测试通过 |
| `tests/browser_smoke.py` | Chromium 桌面宽度 1440 与手机宽度 390 的浏览器验收通过，无 JavaScript `pageerror` |

双实例端到端测试验证了注册、登录、未鉴权拒绝、错误密码拒绝、重复用户名冲突、公开房间创建、数据库消息写入与历史分页、消息 UUID 幂等，以及发送方和另一实例上的接收方均能收到同一条消息。WebSocket 票据由实例 A 签发后可在实例 B 消费，重复消费被拒绝，跨源 `Origin` 握手被拒绝。

浏览器验收验证了注册与创建房间、两个独立用户双向实时聊天、刷新后的会话与历史恢复、输入的 `<script>` 内容按纯文本显示，以及离线期间新增 65 条消息后的重连恢复。重连后先显示最近 50 条，再通过“加载更早的消息”恢复其余历史，未留下不可访问的分页缺口。手机尺寸下验证了无横向溢出、菜单打开与 Escape 关闭、退出登录。

## 复现命令

JUnit 与容器构建：

```bash
./mvnw -B -q verify
docker build -t realtime-chat:local .
```

真实依赖与双实例端到端测试：

```bash
./scripts/init-env.sh
docker compose -f compose.yaml -f tests/compose.integration.yaml up -d --build --wait
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r tests/requirements.txt
CHAT_BASE_A=http://localhost:8080 CHAT_BASE_B=http://localhost:8081 python tests/e2e_smoke.py
```

在应用仍运行时执行可选浏览器测试，使用同一虚拟环境：

```bash
python -m pip install -r tests/requirements-browser.txt
python -m playwright install chromium
CHAT_BROWSER_BASE=http://localhost:8080 python tests/browser_smoke.py
```

浏览器依赖固定为 Playwright 1.64.1。测试会创建测试账号与消息，建议使用可丢弃的测试数据库。结束后可执行以下命令停止容器，保留持久卷：

```bash
docker compose -f compose.yaml -f tests/compose.integration.yaml down
```

## 真实界面截图

- [桌面端截图](screenshots/desktop.png)：1440 × 960。
- [手机端截图](screenshots/mobile.png)：390 × 844。

截图来自实际网页和本地测试数据，不包含真实用户对话或生产业务数据。

## 尚未验证的范围

Kubernetes 清单仅完成 YAML 解析及静态配置审查，未部署到实际集群；HPA 扩缩容、Ingress/TLS、滚动更新和节点故障恢复没有实测。单副本 PostgreSQL 与 Redis 示例不提供高可用性。

未执行 500+ 并发连接、低于 100ms 端到端消息延迟、50K+ 消息数据集、查询提速 60% 或 99.7% 长期可用性验证，也未将 HTTP 负载脚本结果当作 WebSocket 性能证据。这些数字仍是待验证目标；后续应按 [性能验证方法](PERFORMANCE.md) 记录环境、数据规模、基线、负载与原始结果。
