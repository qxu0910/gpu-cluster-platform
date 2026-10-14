# 验证记录

本文件由实际检查结果更新，不以接口骨架或模拟通过代替真实基础设施验收。

## 多架构镜像索引准备（2026-10-13）

Harbor 核验现在解析 OCI/Docker 镜像索引的平台引用与子 manifest digest，持久化已支持的 `linux/amd64`、`linux/arm64` 架构集合；工作负载使用父索引 digest，并把集合投影为 `kubernetes.io/arch` 必需亲和条件。单架构旧记录继续可部署。2026-10-14 复核 `scripts/test.ps1 -Database`：36 项测试全部通过，包括单架构回归、索引引用异常、架构约束和工作负载持久化。尚未构建/推送真实多架构索引，也未在 ARM64 节点拉取；这部分不计入实机验收。

最终本地版本已在 kind 上通过 `scripts/acceptance-diagnostics.ps1` 的真实 CPU 探针故障验收。完整 `scripts/acceptance.ps1` 回归尝试在构建镜像阶段受阻：Docker Hub 对 `busybox:1.37.0` manifest 的请求超时，本机未缓存该基础镜像；脚本未执行后续镜像推送与全生命周期步骤，因此本次不记为完整回归通过。随后标准 Compose 重建也因 Docker Hub 的 Eclipse Temurin 基础镜像元数据超时而失败；使用本机已有 `gpu-cluster-platform:local` 作为运行时基底，`scripts/run-local-offline.ps1` 成功部署最终代码，并再次通过真实探针诊断验收。原有完整 CPU 生命周期的历史通过记录仍见下文。

## 工作负载诊断与节点资产增量（2026-10-13）

执行器读取本项目命名空间中当前工作负载 Pod 的 Kubernetes 异常事件，并只保存白名单原因、类别、Pod、次数和时间，不保存原始消息；事件读取失败不会改变工作负载本身的就绪判断。控制台新增“诊断”入口。节点快照增加架构、系统版本与 GPU Feature Discovery 型号/驱动标签的白名单投影；CPU kind 无 GPU 标签，因此不宣称 GPU 资产或健康实测。

`scripts/test.ps1 -Database` 在独立 `platform_test` 数据库完成 31 项测试，全部通过；`node --check src/main/resources/static/app.js` 通过。已在本项目 kind 环境重建 API/worker，并运行 `scripts/acceptance-diagnostics.ps1`：创建探针指向不存在路径的 CPU 服务，平台查询观察到真实 `Unhealthy` 事件、服务未误判 Ready、普通响应未包含原始事件消息；随后通过平台 DELETE 删除测试服务。该验收只覆盖本地 CPU 探针故障，不覆盖 Spark、B300 或 DCGM。

## DGX Spark ARM64 接入准备（2026-10-13）

镜像核验新增单架构 `linux/arm64`，工作负载保存镜像架构并与显式允许节点共同形成必需的节点亲和条件。新增单元测试验证 ARM64 与 `allowed_nodes` 必须同时满足。`scripts/test.ps1 -Database` 使用原本地 PostgreSQL 的独立 `platform_test` 库执行 30 项 Java 测试，30 项通过、0 失败、0 跳过。未修改两个现有 kind 集群，也未连接 DGX Spark；ARM64 镜像推送、Spark 节点拉取和 GPU 实算仍未验收，步骤见 [Spark 试点](SPARK_PILOT.md)。

## M3 独立集群真实本地存储保护（2026-10-13）

`scripts/acceptance-drain-local-storage.ps1` 在独立三节点 kind 环境分别创建平台管理的 CPU 服务，使其在目标 worker 就绪，再给 Deployment 模板加入 `emptyDir` 或 `hostPath` 卷，挂载到容器 `/scratch` 并写入探针文件。两种情况下 drain 均返回 `blocked/node_contains_local_storage`，Kubernetes 目标节点仍可调度，带卷 Pod 保持存在。移除卷后，原操作自动收敛到 `succeeded`；旧 Pod 离开目标节点，新 UID 的 Pod 在第二个 worker Ready。两组服务随后通过平台接口删除，目标节点恢复调度。操作 ID 和新旧 Pod UID 保存在忽略提交的 `.local/drain-local-storage-acceptance-result.json`。

同日复核实验 API `node_drain=false`，目标与备用 worker 可调度，验收命名空间无临时 Pod。以上与正常迁移、非托管 Pod/PDB 故障、执行器重启恢复组成独立 CPU 集群的 drain 验收；生产多节点 GPU 行为仍待真实硬件阶段验证。

## M3 独立集群真实 drain 故障与恢复（2026-10-13）

`scripts/acceptance-drain-faults.ps1` 在独立集群通过两组真实故障。第一组在 `default` 命名空间把非托管 Pod 放到目标 worker：平台操作先为 `blocked/node_contains_unmanaged_pods`，Kubernetes 目标节点仍可调度；删除该 Pod 后，原操作继续完成。第二组在平台管理的一副本 Ready 服务上创建 `minAvailable=1` 的 PDB：Eviction 被拒，操作为 `blocked/eviction_pdb_or_rate_limited` 且目标节点已 cordon；同幂等键重试返回原操作 ID，不同键冲突返回 HTTP 409。阻止期间重启独立执行器，操作仍保持并恢复处理；删除 PDB 后，原操作收敛到 `succeeded/drained`，新 UID 的 Pod 在第二个 worker Ready。

随后通过平台接口 uncordon、删除验收服务；`default` 和 `platform-workloads` 命名空间均无临时 Pod/PDB，三个节点均 Ready、可调度。两项最终操作及结果记录保存在忽略提交的 `.local/drain-fault-acceptance-result.json`。实验环境再次恢复 `node_drain=false`。

## M3 独立集群真实 CPU drain 正常路径（2026-10-13）

独立三节点 `gpu-platform-drain` 集群、受限 worker 令牌、三节点本地 Harbor CA、独立 PostgreSQL/API/worker Compose 环境已就绪。Harbor HTTPS 从新 worker 经 CA 校验后返回未认证的 401；实验 API 在 `127.0.0.1:18082` 采集到 3 个新鲜 Ready 节点，只允许维护 `gpu-platform-drain-worker`。独立环境默认 `node_drain=false`，验收时临时开启。

`scripts/acceptance-drain-lab.ps1` 通过真实 Harbor 推送和 manifest 核验登记 CPU 镜像，以 digest 创建服务；先使 Pod 在目标 worker Ready，再通过平台 drain 接口驱逐。平台操作最终为 `succeeded` / `drained`；原 Pod 离开目标节点，新 UID 的 Pod 在 `gpu-platform-drain-worker2` Ready，容器内 HTTP 请求返回 `v1`。`scripts/cleanup-drain-lab-acceptance.ps1` 随后通过平台接口恢复目标节点调度并删除验收服务；复核三个节点均 Ready 且可调度，`platform-workloads` 无残留 Pod。验收记录和操作 ID 保存在不提交的 `.local/drain-acceptance-result.json`，最后将实验服务恢复到 `node_drain=false`。

另在独立 PostgreSQL 的 `platform_test` 测试库运行 `mvn verify`：29 项 Java 测试全部执行并通过，0 失败、0 跳过。测试仅清理 `platform_test` 的平台表，未指向实验服务数据库 `platform`。

本节只记录正常迁移；故障与恢复场景的后续验收见上文。

## M3 独立排空集群初始化（2026-10-12）

Docker Desktop Linux 引擎已恢复：`docker info` 返回 Server 29.7.2、OSType `linux`，context 为 `desktop-linux`。运行 `scripts/setup-drain-lab.ps1` 创建独立 `gpu-platform-drain` kind 集群，三个 v1.34.0 节点均为 Ready。首次执行在 RBAC 断言处失败：`kubectl auth can-i` 将 `pods/eviction` 解析为资源/名称，而不是待检查的子资源。改用 `create pods --subresource=eviction` 后，受限 worker 对目标节点 update=yes、其他 worker update=no，`platform-workloads` 内 Eviction=yes、`default` 内 Eviction=no；重跑脚本通过。未修改原 `gpu-platform` 集群或默认 kubeconfig。

本次只完成测试集群和 RBAC 初始化。API、worker、PostgreSQL 和 Harbor 尚未接入该独立集群，真实 Pod 排空、PDB 拒绝与重启恢复尚未验收；`NODE_DRAIN_ENABLED` 保持默认 false。

## 控制台模拟样例（2026-10-12）

加入仅在浏览器内存中的 M3 展示数据：三个节点、两版镜像、一项服务，以及正常迁移、PDB 阻止、非托管 Pod 阻止三种排空场景；节点调度开关和 v2 预热可模拟操作。页面明确标注模拟，退出或刷新后样例消失，真实 drain 按能力开关显示。另提供只服务静态文件的 `run-sample.ps1` 与 `?mode=sample` 直达入口，可在 Docker 不可用时查看。

`node --check src/main/resources/static/app.js` 通过；`node --test src/test/sample-data.test.js src/test/console-sample.test.js` 四项通过，其中控制台测试确认直达入口不发出网络请求、排空只改内存数据；PowerShell 启动脚本语法检查通过，脚本可启动静态服务。Maven `verify` 成功，29 项 Java 测试中 13 项通过、16 项数据库测试因环境未运行。浏览器自动化连接被本机文件访问权限阻止，本机 HTTP 探测被当前执行环境的套接字限制阻断，因此尚未完成页面的可视化或 HTTP 验收。Docker/kind 仍不可用，模拟样例不计入真实 drain 或镜像预热验收。

## M3 drain 合约测试与隔离环境准备（2026-10-12）

新增 Kubernetes 客户端的内存 HTTP 合约测试，覆盖无备用节点、跨命名空间非托管 Pod、本地临时存储、DaemonSet 跳过、PDB 429 拒绝后重试，以及 Pod UID 驱逐前提和离开节点后的完成判断。`mvn verify`：29 项发现、13 项通过、16 项数据库依赖测试跳过、0 失败。独立三节点 kind 配置、初始化脚本和限定节点/命名空间的 RBAC 已写入仓库；PowerShell 语法检查通过。

Docker Linux 引擎仍不可达；`com.docker.service` 显示停止，尝试启动时 Windows 拒绝打开该服务。因此独立集群未创建，RBAC 未应用，数据库集成测试与真实 drain 均未执行。`NODE_DRAIN_ENABLED` 继续保持默认 false。准备和后续验收步骤见 [DRAIN_LAB.md](DRAIN_LAB.md)。

## M3 受控 drain 开发检查（2026-10-09）

新增默认关闭的 drain 接口与执行器逻辑，专用 RBAC 尚未应用到集群。`mvn verify` 通过：24 项发现、8 项执行通过、16 项因本地数据库依赖未运行；新增数据库集成测试尚待执行。静态测试确认仅跳过 DaemonSet/镜像 Pod，并识别 emptyDir 本地数据。Docker Desktop Linux 引擎无法连接，现有 kind API 亦不可达，因此没有真实多节点 Pod 迁移、PDB 拒绝、重启恢复验收。`node_drain` 保持 false；M3 drain 未完成。

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
