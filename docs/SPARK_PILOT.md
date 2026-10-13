# DGX Spark 接入试点

本试点将 DGX Spark 视为独立 ARM64/GPU 集群。现有 `gpu-platform` 与 `gpu-platform-drain` kind 集群继续用于本地 CPU 验收；不将物理 Spark 加入 kind，也不切换默认 kubeconfig。

## 当前状态

平台已允许核验单架构 `linux/arm64` 镜像，并把镜像架构固化到工作负载配置，通过 `kubernetes.io/arch` 节点亲和条件限制部署。单平台 `linux/amd64` 路径保持可用。多架构镜像索引仍不支持。当前仅完成代码与本地测试；尚未在 Spark 验证镜像推送、节点拉取或 GPU 计算。

## 只读盘点

在 Spark 的 SSH 会话中执行下列命令，记录输出中的架构、系统版本、驱动和 Kubernetes 状态；无需发送密码、令牌、私钥或 kubeconfig：

```sh
uname -m
cat /etc/os-release
nvidia-smi --query-gpu=name,driver_version --format=csv,noheader
nvidia-ctk --version
command -v kubectl || true
command -v k3s || true
command -v microk8s || true
```

若已有 Kubernetes，再以该机器已授权的只读身份检查 `kubectl get nodes -o wide`、节点 `kubernetes.io/arch`、`nvidia.com/gpu` 的 capacity/allocatable，以及 GPU 插件或 Operator 的状态。若尚未安装，先确认版本组合、维护窗口和回滚方式，另行建立单节点集群；不在盘点阶段安装驱动、重启机器或覆盖现有容器运行时配置。

## 接入与验收顺序

1. Spark 的 Kubernetes API 必须能被平台 worker 访问；给 worker 单独的受限身份和 kubeconfig，保存在本地忽略提交目录。平台 API 进程不持有 kubeconfig。独立配置项目、集群 ID、命名空间和数据库，不复用现有 kind 环境。
2. Spark 节点必须能通过可信 TLS 与只读凭据访问 Harbor。当前本地 Harbor 的域名解析和端口主要服务于 kind，需单独验证从 Spark 可达，不能仅凭 Windows 端访问成功判定。
3. 使用实机可运行的 `linux/arm64` 单架构测试镜像，走上传会话、Harbor 核验、固定 digest 部署，验证实际节点拉取、就绪与集群内访问。若镜像无 GPU 需求，先用 CPU 请求隔离验证架构和网络。
4. 在 Kubernetes 正确报告 `nvidia.com/gpu` 后，提交申请 1 张 GPU 的 ARM64 工作负载，观察调度、容器内真实计算、停止后释放和再次启动。节点观测与操作记录须显示实机结果；失败时保留 `Unknown/Blocked` 证据，不把 Docker 单机测试或 kind CPU 测试算作 GPU 验收。

DGX Spark 与 B300 是两种独立硬件基线。Spark 接入通过只能证明该机型及其版本组合，不能外推到 B300 或生产多节点集群。
