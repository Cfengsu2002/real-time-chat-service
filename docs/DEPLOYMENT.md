# 部署说明

## 本机 Docker Compose

```bash
./scripts/init-env.sh
docker compose up --build -d
docker compose ps
curl --fail http://localhost:8080/actuator/health/readiness
```

应用以非 root 用户运行。PostgreSQL 和 Redis 不暴露宿主机端口，应用通过内部网络访问依赖。数据库迁移由 Flyway 在启动时执行。现有数据库卷不会因更改 `.env` 自动更新密码，修改前请规划数据库侧的密码轮换。

数据库与 Redis 的持久卷只解决容器重建后的数据保留，不能替代备份。Redis 的 AOF 不会持久化 Pub/Sub 消息；历史聊天保存在 PostgreSQL。

## Kubernetes 示例

示例依赖集群默认 StorageClass、容器镜像仓库访问权限，以及支持 CPU 指标的 Metrics Server。先构建并发布自己的镜像，例如：

```bash
docker build -t ghcr.io/YOUR_GITHUB_USERNAME/realtime-chat:v1 .
docker push ghcr.io/YOUR_GITHUB_USERNAME/realtime-chat:v1
```

将 `deploy/k8s/04-app.yaml` 中的镜像占位值改为发布的版本标签，推荐使用不可变 tag 或 digest。私有仓库还需配置 `imagePullSecrets`。应用镜像名只是示例，仓库不会自动发布镜像。

创建命名空间和密钥：

```bash
kubectl apply -f deploy/k8s/00-namespace.yaml
kubectl -n realtime-chat create secret generic chat-secrets \
  --from-literal=POSTGRES_PASSWORD="$(openssl rand -hex 24)" \
  --from-literal=JWT_SECRET="$(openssl rand -hex 48)"
```

上面会在本机生成密钥并写入集群，避免把真实密钥提交到版本库。`secret.example.yaml` 是字段说明，不要直接应用占位值。Kubernetes Secret 默认仅编码，不等于加密；生产使用外部密钥系统或配置集群静态加密与适当的 RBAC。

```bash
kubectl apply -f deploy/k8s/01-configmap.yaml
kubectl apply -f deploy/k8s/02-postgres.yaml
kubectl apply -f deploy/k8s/03-redis.yaml
kubectl apply -f deploy/k8s/04-app.yaml
kubectl apply -f deploy/k8s/05-hpa.yaml
kubectl -n realtime-chat rollout status deployment/chat-app
kubectl -n realtime-chat port-forward service/chat-app 8080:80
```

随后访问 [http://localhost:8080](http://localhost:8080)。若应用暂时因数据库未就绪而重启，请检查依赖 Pod 与卷状态；就绪后应用会重新启动。不要对整个目录直接执行 `kubectl apply -f deploy/k8s/`，以免应用示例密钥。

应用声明 2 个副本，HPA 可扩至 5 个。单副本 PostgreSQL 与 Redis 的 StatefulSet/PVC **仅为可运行示例，不提供高可用性**。生产环境应使用受维护的数据库服务，设置备份恢复演练、Redis 故障切换、资源监控与网络策略。

## HTTPS / WebSocket 入口

公网使用 HTTPS 和 WSS。下面是使用 ingress-nginx 且已有 TLS Secret 的入口示例；需先安装所选 Ingress Controller，并将域名解析到入口地址。其他控制器的超时设置需按其文档调整。

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: chat-app
  namespace: realtime-chat
  annotations:
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  ingressClassName: nginx
  tls:
    - hosts: [chat.example.com]
      secretName: chat-tls
  rules:
    - host: chat.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: chat-app
                port:
                  number: 80
```

入口需支持 HTTP Upgrade，并限制请求体和连接数。同一 WebSocket 的数据流由其建立连接的应用实例承载，不需要粘滞会话；Redis 将房间消息跨实例同步。滚动更新、节点故障和入口空闲超时仍会导致连接断开，需要浏览器重连和历史补读。

只向入口开放所需的网页、API 和 `/ws`，限制公网访问 Actuator。不要在代理访问日志中记录 `/ws` 的查询参数，票据虽然短期且一次性，仍应按凭据处理。禁止将 JWT、密码、会话票据或完整对话内容写入公开日志或公开对象存储。

## 运维限制

- JWT 密钥须在所有副本间一致；轮换会使现有登录令牌失效。
- 默认注册对访问者开放，应按部署场景增加限流、注册策略和滥用治理。
- PostgreSQL 提交与 Redis 发布不构成原子事务；当前没有 outbox 或离线推送。
- HPA 仅根据 CPU 调节副本数。需要结合活跃连接、消息速率、Redis/数据库负载和端到端延迟监控评估容量。
- 健康探针通过不代表容量或长期可用性达标。99.7% 等可用性数字需要明确监测窗口与真实监控记录。
