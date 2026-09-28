# 验证记录

本文件由实际检查结果更新，不以接口骨架或模拟通过代替真实基础设施验收。

## 节点调度开关增量验证（2026-09-28）

新增受允许名单保护的 cordon/uncordon 操作、持久化任务、幂等重放、节点 UID 校验和前端操作按钮。worker 改用独立 24 小时服务账号 kubeconfig；本地 ClusterRole 的 update 权限仅覆盖 `gpu-platform-control-plane`。使用该服务账号直接检查权限：指定节点 update=yes，未授权节点 update=no，节点列表 list=yes。两份 RBAC 清单均通过 Kubernetes API 服务器 dry-run。

`scripts/test.ps1 -Database`：当前 22 项通过，0 失败、0 错误、0 跳过。新增测试验证缺少幂等键、错误 UID、未列入允许名单的节点、相同请求重放、冲突请求以及 worker 操作完成。15 个 PowerShell 脚本和前端 JavaScript 语法检查通过。

`scripts/acceptance-node-maintenance.ps1` 在真实 `kind-gpu-platform` 集群通过：对唯一允许的节点停止新调度，重复请求返回同一操作；Kubernetes 与 API 均观察到变化；随后恢复初始可调度状态。最终直接查询 Kubernetes 和 API，均为 `unschedulable=false`，快照新鲜。当前 kind 只有一个控制平面节点，未测试 Pod 迁移或 drain；本增量仅完成调度开关。

切换为受限 worker 身份后，`scripts/acceptance.ps1` 于 2026-09-28 16:32（北京时间）再次通过完整 CPU 生命周期：真实 Harbor 推送与拉取、创建就绪、扩缩容、停止恢复、版本更新和删除。旧的故障注入结果保留原验收时间，本次未重跑故障套件。

## 本地演示用户自动进入（2026-09-28）

`AUTH_MODE=demo` 且 `CPU_TEST=true` 时，打开本地控制台自动使用已配置的 `CALLER_SUBJECT`，无需输入 Bearer 凭据。非本机 Host 和跨站 Origin 返回 403；原 `AUTH_MODE=local` 集成测试仍确认缺少凭据返回 401。当前 `scripts/test.ps1 -Database` 共 21 项通过，0 失败、0 错误、0 跳过；`node --check src/main/resources/static/app.js` 通过。

## 节点只读观测增量验证（2026-09-28）

新增 `/v1/nodes`、`/v1/nodes/{name}` 与控制台节点状态页。worker 使用独立只读 ClusterRole 周期采集 Kubernetes Node，API 只返回 Ready、压力条件、可调度状态、CPU/内存/Pod/GPU 容量及 kubelet、容器运行时、体系结构和操作系统；不返回标签、注解或凭据。

`scripts/test.ps1 -Database`：当前共 20 项通过，0 失败、0 错误、0 跳过；新增项目隔离、分页、详情、陈旧状态和安全字段投影测试。`node --check src/main/resources/static/app.js` 通过。

`scripts/acceptance-nodes.ps1` 在真实 kind `gpu-platform` 集群通过：API 列表和详情与 `kubectl get nodes` 一致；仅断开 worker 与 kind 网络后，节点变为 `Unknown` 且携带观测错误；网络恢复后重新收敛为新鲜的 `Ready=True`。本机实测节点为 `gpu-platform-control-plane`，CPU 容量/可分配量为 12/12 核，kubelet 为 v1.34.0，运行时为 containerd 2.1.3。该 CPU 环境未暴露 `nvidia.com/gpu`，因此不把缺失资源解释为 0 张真实 GPU。

机器可读结果写入 [acceptance-results.json](acceptance-results.json)。本增量完成 M3 的只读节点观测；节点排空、维护、镜像预热、GPU 健康和利用率仍在后续阶段。

## 简易控制台增量验证（2026-09-23）

加入首页、凭据连接、镜像/工作负载列表、创建、启停、扩缩容、删除确认与操作查询。首页及显式静态资源无需认证，业务 API 保持受保护。凭据只在页面内存中保存，不写入浏览器存储或 URL。

`scripts/test.ps1 -Database`：18 项通过，0 失败、0 错误、0 跳过；新增公开资源与业务认证隔离测试。`node --check` 和 Git 差异检查通过。原 M0–M2 验收记录如下。

实际浏览器验证：错误凭据显示 401；本地凭据连接后加载真实镜像列表；创建 `console-smoke` CPU 服务到 Ready，扩容至 2 副本，停止至 0 副本，恢复 2 副本，再删除。5 个操作均显示成功；删除取消不改变服务。临时工作负载已清理，浏览器控制台无脚本错误，断开连接后清空页面数据并禁用管理按钮。

验收日期：2026-09-23，Windows / Docker Desktop Linux 引擎，独立 kind `gpu-platform` 集群。M0–M2 本地 CPU 验收通过；这不代表生产高可用、B300 驱动兼容或 512 卡验收。

## 自动测试

`scripts/test.ps1 -Database`：17 项通过，0 失败、0 错误、0 跳过。其中 6 项独立测试，11 项使用真实 PostgreSQL 17.6 的数据库集成测试。

覆盖认证及跨项目隐藏、严格字段校验、并发幂等重试与冲突、版本前提、镜像引用保护、秘密加密和响应脱敏、数据库迁移、租约竞争和过期接管、依赖中断恢复、过期观察、停止接管与有效租约排斥、标准 If-Match、OpenAPI。外部依赖替身只用于这些契约/故障注入测试。

12 个 PowerShell 脚本解析通过。真实 API/worker 日志扫描未发现本地令牌、数据库密码、仓库管理密码或加密密钥的明文值。

## 真实基础设施验收

`scripts/acceptance.ps1` 于 09:27:27（北京时间）完成：两个 BusyBox CPU 服务版本标准推送至 Harbor；实际 digest/架构核验；节点使用独立只读机器人经 TLS 拉取；创建、Ready、集群内 HTTP 返回 v1、扩容到 2、停止、恢复、更新 v2、HTTP 返回 v2、删除并观察资源消失。幂等重放返回同一操作，引用镜像删除返回 409。

`scripts/acceptance-faults.ps1` 首次完整通过于 09:32:05；加入 HTTP 404 探针事件的强制断言后，于 09:36:35 再次完整通过。机器可读摘要见 [acceptance-results.json](acceptance-results.json)。

| 故障 | 实际结果 |
|---|---|
| 仓库中镜像不存在 | failed / image_not_found，无可部署版本登记 |
| 禁用独立拉取机器人，使用从未缓存的新 digest | blocked / image_pull_unauthorized |
| readiness 请求不存在路径 | 保持未就绪；Kubernetes Unhealthy 事件确认 HTTP 404 |
| 容器退出码 7 | blocked / container_crash |
| 请求 256 CPU 核 | blocked / unschedulable |
| 执行器停止时接收任务，再启动 | 原持久化操作收敛到 Ready |
| 仅断开 worker 的 kind 网络，再接回 | Unknown 后恢复到 Ready |
| Harbor core 缩至 0 后恢复 | 上传授权 Unknown 后自动完成 |

故障工作负载通过正式 DELETE 接口清理；未直接篡改业务库绕过状态机。断连测试恢复网络和 Harbor 副本数。原始运行日志与结果 JSON 保留在忽略的 `.local/`，不提交凭据或 kubeconfig。

## 备份恢复与修复记录

`scripts/backup.ps1` 和 `scripts/restore-test.ps1` 已完成真实 pg_dump/pg_restore：隔离库 `platform_restore_20260923092645` 中 Flyway V1 成功，恢复镜像 8、上传会话 9、工作负载 3 条（备份时刻记录数）。原业务库未覆盖；密钥需另行保管。

真实验收发现并修复：Harbor 项目机器人筛选参数大小写、机器人密码格式、Windows Java HTTP 客户端异常、containerd 未加载本地 CA 目录、PowerShell 原生命令输出污染函数返回值，以及 If-Match 必须使用带双引号的版本值。Docker Desktop 遗留 socket 通过备份运行时目录恢复，没有重置镜像或数据卷。

验收层为 L5 容器与调度，信号来自真实镜像拉取、Kubernetes generation/探针、集群内请求和删除观察；L4 驱动及真实 GPU 尚未验证。M3 节点管控、预热与 M4–M5 硬件/规模工作见 ROADMAP。
