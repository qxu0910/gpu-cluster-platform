# 状态、恢复与运维

## 状态来源

数据库保存期望配置、不可变 revision、操作及最近观察；Kubernetes 是实际运行状态来源。operation 状态：pending、running、blocked、succeeded、failed、unknown。阶段另行反映 scheduling/pulling/starting/ready/stopped/deleting。

Blocked 可继续协调；Unknown 表示外部结果不确定，不能当作成功。Pod Running 不等于 Ready，目标 Deployment generation 必须已被控制器观察，且更新/可用副本满足期望。停止等待 Pod 删除；删除使用 UID 前提，且只删除本平台拥有的 Deployment、Service、拉取 Secret，不删除命名空间或镜像。

数据库事务接收任务后才返回 202。领取使用 `FOR UPDATE SKIP LOCKED`、45 秒租约与 10 秒心跳，完成更新需要租约归属仍有效。执行器崩溃后其他进程可在租约过期后续跑。Kubernetes 名称确定，写入前查询，重复调用收敛到同一对象。

List/Watch 从 resourceVersion 继续，异常/版本过期重新 List，3 秒任务轮询和 30 秒周期对账提供兜底。外部调用设连接与读取超时；无法核验时返回 Unknown 并保留上次观察时间，避免伪装为新鲜健康。并发 API 变更与周期对账通过资源行锁隔离。

## 重启与故障

```powershell
docker compose --env-file .local/platform.env -f deploy/compose.yml restart worker
```

不删除数据库卷即可恢复未完成任务。检查 `/v1/operations/{id}`、资源 `observed_at` 和 Docker/Kubernetes 事件。管理接口不暴露原始异常、仓库凭据、环境变量或完整 kubeconfig。

镜像不存在、digest 不符、平台不支持、权限失败返回明确错误；网络故障进入 Unknown 并重试。拉取失败、调度不足、CrashLoop 或发布超过 600 秒进入 Blocked。超过观察期限不自动取消实际工作负载；资源仍可能稍后启动。

同一目标的活动操作采用 409 冲突保护。显式 stop/delete 可在无有效执行租约的边界接替未完成部署，原任务保留 superseded 审计，新任务观察真实停止或删除；有租约时重试 409。旧执行器的数据库更新受租约保护，Kubernetes 更新不得覆盖更高 revision。强制终止协议属于后续增强，不通过数据库手工篡改状态绕过保护。

## 备份和隔离恢复

运行 `scripts/backup.ps1` 使用 PostgreSQL 自定义格式导出到 `.local/`。同时离线保存 `ENCRYPTION_KEY`，数据库备份本身不能替代加密密钥。恢复时先停 API/worker，恢复到新数据库，检查 Flyway 版本、记录数和加密字段可解密，再切换 `DATABASE_URL`。不在仍运行的业务库上覆盖恢复。

隔离恢复示例：

```powershell
docker compose --env-file .local/platform.env -f deploy/compose.yml exec -T postgres createdb -U platform platform_restore
docker compose --env-file .local/platform.env -f deploy/compose.yml cp .local/<backup>.dump postgres:/tmp/restore.dump
docker compose --env-file .local/platform.env -f deploy/compose.yml exec -T postgres pg_restore -U platform -d platform_restore --no-owner /tmp/restore.dump
```

数据库恢复不恢复 Harbor 镜像或 Kubernetes/etcd；生产要分别备份仓库对象、Harbor 元数据与控制平面，并执行跨组件一致性检查。恢复点和恢复时间目标尚未由业务确认。

## 生产前要求

配置可信 HTTPS 管理入口、JWT issuer/audience、最小命名空间 Kubernetes 权限、限定 Harbor 管理账号、受保护的数据库与秘密存储。示例 kind kubeconfig 为本地集群管理凭据，不用于生产。API 进程不需要 Kubernetes kubeconfig；仅 worker 配置集群访问。

监控数据库连通性、未完成/Unknown 操作积压、租约过期、观察时间滞后、Harbor/集群依赖健康。生产告警阈值与可用性指标待业务确认，本地示例不声称高可用。
