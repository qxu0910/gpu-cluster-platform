# 管理接口契约

`AUTH_MODE=local` 和 `AUTH_MODE=jwt` 下，所有 `/v1` 请求需要 `Authorization: Bearer <credential>`。本地 `AUTH_MODE=demo` 且 `CPU_TEST=true` 时，浏览器从本机地址访问会自动使用 `CALLER_SUBJECT`，无需 Bearer 请求头；演示模式拒绝非本机 Host 和跨站 Origin，Compose API 端口仅绑定 `127.0.0.1`。调用方主体通过服务端 `CALLER_SUBJECT` 映射到 `PROJECT_ID`、`CLUSTER_ID` 和 `WORKLOAD_NAMESPACE`。本版每个部署配置一个主体映射；未映射主体返回 403，不从请求体或任意令牌 claim 接受项目归属。

生产模式使用 OAuth2 资源服务器，校验 HTTPS issuer、签名、有效期、audience 和 `platform.read`/`platform.write` scope。本地模式只接受至少 32 字符的显式配置令牌。默认认证模式为 JWT，缺少 issuer 或加密密钥则启动失败。

## 变更与查询

变更需要 `Idempotency-Key`（1–128 个字母、数字、点、冒号、下划线或短横线）。作用域是主体、方法和路径；保留 7 天。相同键且规范化 JSON 相同返回原操作，内容不同返回 409。过期后不承诺去重。响应为 `202`，包含 `operation_id`、`target_id` 和 `Location: /v1/operations/{id}`。

查询返回 200；无认证 401，权限不足 403，不可见资源 404，状态或版本冲突 409，依赖数据库不可用 503。成功查询失败操作仍是 200。错误体为 `{"error":{"code":"...","message":"..."}}`，请求标识位于 `X-Request-ID`。

分页使用 `offset`（默认 0）和 `limit`（默认 20，最大 100），响应包含 `items`。未知请求字段拒绝，GET 不回显环境变量值或上传/拉取凭据。

| 路径 | 方法与含义 |
|---|---|
| `/v1/image-uploads` | POST 请求上传授权，异步创建隔离项目及机器人账号 |
| `/v1/image-uploads/{id}` | GET 上传状态、目标和完成后的 image_id |
| `/v1/image-uploads/{id}/credentials` | POST 读取受限上传凭据；只读操作，不需要幂等键，响应 no-store |
| `/v1/image-uploads/{id}/complete` | POST 发起真实仓库核验，可提交期望 digest |
| `/v1/images`、`/v1/images/{id}` | GET 镜像分页及详情 |
| `/v1/images/{id}` | DELETE 撤销平台可部署权限；不删除仓库内容 |
| `/v1/workloads`、`/v1/workloads/{id}` | POST 创建；GET 列表或详情 |
| `/v1/workloads/{id}/start`、`stop`、`scale` | POST 改变服务运行状态 |
| `/v1/workloads/{id}/revisions` | POST 提交新完整配置，创建不可变版本 |
| `/v1/workloads/{id}` | DELETE 所属资源，需要带双引号的 `If-Match` 版本头，例如 `"5"` |
| `/v1/operations/{id}` | GET 持久化操作状态与错误 |
| `/v1/capabilities` | GET 已实现与未开放能力 |
| `/v1/nodes` | GET 节点快照列表，支持 offset/limit，返回 total、stale 和 observed_at |
| `/v1/nodes/{name}` | GET 单个节点快照及采集新鲜度 |
| `/v1/nodes/{name}/cordon`、`/uncordon` | POST 停止或恢复新工作负载调度；需要写权限、幂等键及节点 UID/版本前提 |

## 节点观测与调度开关（M3 增量）

执行器每 15 秒读取配置集群的节点并保存到 PostgreSQL，接口进程只读取快照，不持有 kubeconfig。查询按服务端主体对应的项目和集群隔离，需要 `platform.read` 权限。节点是集群级资源，当前操作主体可观察其映射集群的全部节点；这不是命名空间级节点隔离。

节点字段包含 name、uid、resource_version、maintenance_allowed、ready（True/False/Unknown）、unschedulable、capacity、allocatable、conditions、kubelet_version、runtime_version、architecture、operating_system。资源只返回 cpu、memory、pods、nvidia.com/gpu；GPU 未上报时不填充为 0。数量保留 Kubernetes 单位，例如内存 Ki、CPU m。容量不代表实时利用率，也不等同剩余未申请资源。

集群调用失败或最后成功采集超过 120 秒，stale=true、ready=Unknown，保留 observed_at 和历史容量/条件。依赖错误通过 error_code 返回，页面明确标记历史观察。首次采集前返回空列表和 node_observation_pending；新鲜快照中不存在的节点详情为 404，无法确认时为 503。节点信息不返回地址、任意标签/注解或原始异常消息。

`POST /v1/nodes/{name}/cordon` 或 `/uncordon` 使用 `{"expected_uid":"...","expected_resource_version":"..."}`，两个值来自最新节点详情。必须提供 `Idempotency-Key`；重复请求返回同一操作。仅 `NODE_MAINTENANCE_NODES` 显式列出的节点可以修改；快照陈旧返回 503，节点身份或版本变化返回 409，同一节点已有未完成操作也返回 409。worker 再次检查允许名单与实际 UID，使用 Kubernetes resourceVersion 更新 `spec.unschedulable`，确认变更后才把操作标为成功。节点名称重新指向不同 UID 时拒绝沿用旧操作。cordon 只停止新 Pod 调度，不驱逐已有 Pod。调用方需要 `platform.write` 权限。

`deploy/worker-rbac.yml` 只授予节点 get/list；`deploy/node-maintenance-rbac-local.yml` 仅对本地测试节点 `gpu-platform-control-plane` 授予 get/update。`node_observation=true`、`node_maintenance=true`，但 `node_drain=false`、`image_prewarm=false`。drain 和镜像预热仍未开放。

## 镜像流程

1. POST `/v1/image-uploads`：`{"repository":"demo","tag":"v1"}`。
2. 轮询 operation，成功后 GET 上传会话取得 `target`。
3. POST `/credentials` 取得 `registry`、`username`、`password`，按标准 Registry 协议直接推送；镜像层不经过管理接口。
4. POST `/complete`：`{}` 或 `{"digest":"sha256:..."}`。平台通过 Harbor 读取真实 digest、OS 和架构；首版本地基线接受单平台 linux/amd64 镜像，其他架构/镜像索引明确拒绝。
5. 核验成功后 GET 上传会话取得 `image_id`，上传账号撤销。部署始终使用 `repository@sha256:...`。

每次上传使用独立 Harbor 私有项目；上传机器人只能在该项目 push/pull。会话期限 24 小时，超期后台撤销机器人。节点使用独立只读机器人，凭据加密保存并仅安装到工作负载专用拉取 Secret。只读账号不自动过期，生产须按运维流程轮换。删除镜像版本仅撤销平台可部署权限；工作负载当前配置及历史 revision 仍引用时拒绝删除。

## service 示例

```json
{
  "name": "demo", "image_id": "image-...", "cluster_id": "local-kind",
  "type": "service", "replicas": 1,
  "resources": {"gpu_per_replica": 0, "cpu_cores": 0.1, "memory_gib": 0.0625},
  "ports": [{"name":"http","port":8080,"protocol":"TCP"}],
  "readiness": {"type":"http","path":"/ready","port":8080},
  "termination_grace_seconds": 30
}
```

0 卡仅在 `CPU_TEST=true` 下允许。GPU 按整卡申请，单副本最多 8 卡，副本上限 64；CPU 为 0.1–256 核，内存为 0.0625–2048 GiB，退出宽限 1–600 秒。这些是开发契约上限，不代表生产容量。

可选 `command`、`args`、`env`、`startup`、`placement.node_pool`/`allowed_nodes`。放置通过 nodeSelector/affinity，禁止直接指定 nodeName。HTTP/TCP 探针端口必须声明为 TCP 服务端口；`readiness.type=process` 明确采用较弱的进程检查。正常模式不授予宿主权限或自动挂载平台 ServiceAccount。

状态改变请求如 `{"expected_version":1,"replicas":2}`。stop 保存恢复副本数；start 可省略 replicas。scale 到 0 等同停止但保留配置。revision 请求为 `{"expected_version":3,"configuration":{...完整配置...}}`。名称不可改变。冲突操作返回 409，版本前提必须与 GET 返回值匹配。显式 stop/delete 可在执行租约释放后接替未完成的部署操作，原操作记为 failed/superseded；新操作仍需观察实际停止/删除完成。执行器持有租约时返回 409，可用相同幂等键稍后重试；删除中的资源不可被 stop 接替。

返回的 `observed.access` 含 `scope=cluster` 与服务 DNS 名；仅保证集群内访问范围。上游外部可达性、生产出口和长连接无损迁移不在本轮承诺内。
