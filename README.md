# GPU Cluster Platform

独立的 GPU 集群管理接口平台。主层 L5 容器与调度，上游 L8 管理接口，下游 Kubernetes/containerd 与 L4 驱动运行时。

本仓库实现 M0–M2：镜像上传授权与仓库核验、按 digest 部署、service 全生命周期、持久化操作与幂等。真实验收结果见 [测试记录](docs/TESTING.md)；CPU 验收不代表 B300 或 64 台/512 卡生产验收。

## 本地启动（Windows / PowerShell 7）

前置：Docker Desktop Linux 引擎、Java 21（设置 `JAVA_HOME`）、Maven 3.9.11、Git（含 OpenSSL）、kubectl；下载工具和镜像需要网络。Harbor 与 kind 至少预留 8 GB 可用内存和 20 GB 磁盘。首次部署可能需要较长时间。

```powershell
./scripts/init-local.ps1
./scripts/setup-cluster.ps1
./scripts/run-local.ps1
./scripts/test.ps1 -Database
./scripts/acceptance.ps1
./scripts/acceptance-faults.ps1
```

`setup-cluster.ps1` 只操作 `gpu-platform` kind 集群、`harbor`/`platform-workloads` 命名空间；域名解析配置限于容器，不修改 Windows hosts。不改变原默认 kubeconfig，项目 kubeconfig 位于 `.local/`。宿主机 curl 调用 Harbor 时使用 `--resolve harbor.platform.test:30443:127.0.0.1`。集群内 Harbor 使用私有 CA（证书颁发机构：用于校验本地服务身份）；Java 信任库和节点信任配置均在项目范围内。证书有效期 30 天，仅用于本地测试。

Compose 将管理接口绑定到 `127.0.0.1:18080`、数据库绑定到 `127.0.0.1:55432`。本地回环管理连接使用 HTTP；生产必须在受控 HTTPS 入口后部署。Harbor 始终校验证书。真实集群、Harbor 管理凭据及 JWT（JSON Web 令牌：用于校验调用方身份）配置不能公开。

`.local/platform.env` 存放随机生成的本地配置，不提交。上传授权凭据只能由有写权限的调用方通过专用 `/credentials` 端点读取，普通查询不返回。数据库内环境变量和仓库秘密使用 AES-GCM 加密；`ENCRYPTION_KEY` 丢失将无法恢复这些数据。

## 开发与接口

浏览器打开 `http://localhost:18080/` 使用简易管理控制台。从本地 `.local/platform.env` 复制 `LOCAL_TOKEN` 的值到凭据输入框，再连接。页面支持查看镜像、创建服务、启停、扩缩容、删除和按 ID 跟踪操作；凭据仅保存在页面内存，刷新后重新输入。首页及其两个静态资源公开，所有业务接口仍要求认证。镜像推送和版本更新继续使用 API/脚本。

环境变量清单见 [.env.example](.env.example)；该文件只含空白秘密占位，实际使用初始化脚本生成的 `.local/platform.env`。

```powershell
# 若没有全局 Maven，固定版本可解压到 .tools/apache-maven-3.9.11
mvn -B verify
```

普通测试不需要 Kubernetes。数据库集成测试使用独立 `platform_test` 数据库，**会清空该测试库中的平台表**；不要把测试数据库 URL 指向业务数据库。设置 `RUN_DATABASE_TESTS=true` 可在持续集成中运行数据库测试。

接口与样例见 [API 契约](docs/API.md)。运行时完整 OpenAPI JSON 位于 `/v3/api-docs`，要求认证。健康检查 `/actuator/health` 不要求认证，其他管理与监控路径要求认证。

API 与后台执行器由 `PLATFORM_ROLE=api|worker|all` 控制。默认 `all`；Compose 分别运行 `api` 与 `worker`。真实适配器始终为默认实现；测试替身只存在于测试代码，不提供会伪报成功的生产模拟开关。

## 维护与边界

- [状态与恢复](docs/OPERATIONS.md)：幂等、租约、失联、恢复及备份。
- [版本与环境基线](docs/BASELINE.md)：固定版本和未验收环境项。
- [开发基线](docs/PROJECT_SPEC.md)：原始交接文档，保留为范围依据。
- [后续工作](docs/ROADMAP.md)：M3–M5、生产多主体映射、正式运维与硬件验收。

`scripts/cleanup-local.ps1` 删除本项目测试集群并停止 Compose 服务，默认保留数据库卷。只有显式 `-DeleteDatabase` 才删除本项目数据库卷。测试 Harbor 数据随 kind 集群删除；需保留镜像时先导出或配置持久化外部存储。
