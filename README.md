# GPU Cluster Platform

独立的 GPU 集群管理接口平台。主层 L5 容器与调度，上游 L8 管理接口，下游 Kubernetes/containerd 与 L4 驱动运行时。

本仓库实现 M0–M2：镜像上传授权与仓库核验、按 digest 部署、service 全生命周期、持久化操作与幂等。真实验收结果见 [测试记录](docs/TESTING.md)；CPU 验收不代表 B300 或 64 台/512 卡生产验收。

## 本地启动（Windows / PowerShell 7）

前置：Docker Desktop Linux 引擎、Java 21（设置 `JAVA_HOME`）、Maven 3.9.11、Git（含 OpenSSL）、kubectl；下载工具和镜像需要网络。Harbor 与 kind 至少预留 8 GB 可用内存和 20 GB 磁盘。首次部署可能需要较长时间。

```powershell
./scripts/init-local.ps1
./scripts/setup-cluster.ps1
./scripts/configure-local-worker-identity.ps1
./scripts/run-local.ps1
./scripts/test.ps1 -Database
./scripts/acceptance.ps1
./scripts/acceptance-faults.ps1
```

如果 Docker Hub 暂时无法查询基础镜像，而本机已有先前构建的 `gpu-cluster-platform:local`，可运行 `./scripts/run-local-offline.ps1`。该脚本复用本机现有运行时镜像，仅替换本次编译的应用 JAR 并重建本项目 API/worker；它不会更新基础镜像，首次启动仍需正常完成在线初始化。

`setup-cluster.ps1` 只操作 `gpu-platform` kind 集群、`harbor`/`platform-workloads` 命名空间；域名解析配置限于容器，不修改 Windows hosts。不改变原默认 kubeconfig，项目 kubeconfig 位于 `.local/`。宿主机 curl 调用 Harbor 时使用 `--resolve harbor.platform.test:30443:127.0.0.1`。集群内 Harbor 使用私有 CA（证书颁发机构：用于校验本地服务身份）；Java 信任库和节点信任配置均在项目范围内。证书有效期 30 天，仅用于本地测试。

Compose 将管理接口绑定到 `127.0.0.1:18080`、数据库绑定到 `127.0.0.1:55432`。本地 worker 使用单独生成的 24 小时服务账号 kubeconfig，过期后重新运行 `configure-local-worker-identity.ps1` 并重启 worker；不会把管理员 kubeconfig 挂入 worker。本地回环管理连接使用 HTTP；生产必须在受控 HTTPS 入口后部署。Harbor 始终校验证书。真实集群、Harbor 管理凭据及 JWT（JSON Web 令牌：用于校验调用方身份）配置不能公开。

已有 `.local/platform.env` 的开发环境需要加入 `NODE_MAINTENANCE_NODES=gpu-platform-control-plane` 才会显示可用的节点调度按钮；新初始化的环境会自动包含它。其他节点默认不可执行维护。

`.local/platform.env` 存放随机生成的本地配置，不提交。上传授权凭据只能由有写权限的调用方通过专用 `/credentials` 端点读取，普通查询不返回。数据库内环境变量和仓库秘密使用 AES-GCM 加密；`ENCRYPTION_KEY` 丢失将无法恢复这些数据。

## 开发与接口

浏览器打开 `http://localhost:18080/` 使用简易管理控制台。本地 `AUTH_MODE=demo` 时自动以配置的 `CALLER_SUBJECT` 进入，无需粘贴 Bearer 凭据；API 只绑定宿主机 `127.0.0.1`，演示认证仅接受本机地址并拒绝跨站请求。页面支持查看节点和镜像、创建服务、启停、扩缩容、删除和按 ID 跟踪操作。`AUTH_MODE=local` 或 `jwt` 时，页面保留手动凭据连接；默认配置为 JWT，生产环境不应启用演示模式。镜像推送和版本更新继续使用 API/脚本。

管理接口启动但集群暂不可用时，可以在连接面板选择「查看模拟样例」；若已自动连接，先点「断开」。样例仅存在当前浏览器内存，展示三个节点、两版镜像、一项服务，以及正常迁移、PDB 阻止、非托管 Pod 阻止的排空场景。选择场景会重置样例，节点按钮可演示调度开关、排空与 v2 预热；刷新页面会清除样例。页面始终标注模拟，样例操作不会调用管理接口、Kubernetes 或 Harbor，也不计入 M3 真实验收。真实 drain 仍由 `NODE_DRAIN_ENABLED` 能力开关控制。

如果 Docker 和管理接口都未启动，只需本机 Python 与 PowerShell，即可在项目目录执行 `./scripts/run-sample.ps1`，再打开 `http://127.0.0.1:18081/?mode=sample`。此入口只提供静态文件，不运行平台 API；关闭命令窗口或按 Ctrl+C 即停止。普通 `http://localhost:18080/` 仍是连接真实管理接口的入口。

环境变量清单见 [.env.example](.env.example)；该文件只含空白秘密占位，实际使用初始化脚本生成的 `.local/platform.env`。

```powershell
# 若没有全局 Maven，固定版本可解压到 .tools/apache-maven-3.9.11
mvn -B verify
```

普通测试不需要 Kubernetes。数据库集成测试使用独立 `platform_test` 数据库，**会清空该测试库中的平台表**；不要把测试数据库 URL 指向业务数据库。设置 `RUN_DATABASE_TESTS=true` 可在持续集成中运行数据库测试。

接口与样例见 [API 契约](docs/API.md)。运行时完整 OpenAPI JSON 位于 `/v3/api-docs`，要求认证。健康检查 `/actuator/health` 不要求认证，其他管理与监控路径要求认证。

API 与后台执行器由 `PLATFORM_ROLE=api|worker|all` 控制。默认 `all`；Compose 分别运行 `api` 与 `worker`。真实适配器始终为默认实现；测试替身只存在于测试代码，不提供会伪报成功的生产模拟开关。

## 维护与边界

前端“节点状态”展示真实集群节点的就绪、调度开关、压力条件、资源容量与已上报的架构/GPU 型号/驱动信息，并可提交停止/恢复新工作负载调度操作。工作负载“诊断”展示受限的 Pod 异常事件原因与采集时间，原始事件消息不会进入普通接口。执行器每 15 秒采集节点，失联或超过 120 秒未更新显示 Unknown。容量不是实时使用率；停止调度不驱逐已有 Pod。节点查询接口为 `/v1/nodes` 和 `/v1/nodes/{name}`；维护操作和验收见 [API 契约](docs/API.md) 与 `scripts/acceptance-node-maintenance.ps1`。独立三节点环境已通过真实 CPU drain 正常迁移、非托管 Pod/PDB 阻止、执行器重启恢复及 emptyDir/hostPath 本地存储保护；项目默认 drain 开关保持关闭，详见 [排空实验环境](docs/DRAIN_LAB.md)。

- [状态与恢复](docs/OPERATIONS.md)：幂等、租约、失联、恢复及备份。
- [版本与环境基线](docs/BASELINE.md)：固定版本和未验收环境项。
- [开发基线](docs/PROJECT_SPEC.md)：原始交接文档，保留为范围依据。
- [后续工作](docs/ROADMAP.md)：M3–M5、生产多主体映射、正式运维与硬件验收。
- [DGX Spark 试点](docs/SPARK_PILOT.md)：ARM64 镜像准备、只读盘点与独立 GPU 接入验收顺序。

`scripts/cleanup-local.ps1` 删除本项目测试集群并停止 Compose 服务，默认保留数据库卷。只有显式 `-DeleteDatabase` 才删除本项目数据库卷。测试 Harbor 数据随 kind 集群删除；需保留镜像时先导出或配置持久化外部存储。
