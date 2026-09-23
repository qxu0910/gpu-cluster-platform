# 管理接口契约

所有 `/v1` 请求需要 `Authorization: Bearer <credential>`。调用方主体通过服务端 `CALLER_SUBJECT` 映射到 `PROJECT_ID`、`CLUSTER_ID` 和 `WORKLOAD_NAMESPACE`。本版每个部署配置一个主体映射；未映射主体返回 403，不从请求体或任意令牌 claim 接受项目归属。

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
