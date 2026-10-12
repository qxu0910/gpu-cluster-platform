# M3 独立排空验收环境

`scripts/setup-drain-lab.ps1` 准备独立的 `gpu-platform-drain` kind 集群：一个控制平面、两个 worker，API 仅监听 `127.0.0.1:57443`。脚本只使用 `.local/kubeconfig-drain-lab`，不切换默认 kubeconfig、不重建现有 `gpu-platform`/Harbor 集群。它在独立集群安装平台 worker 的基础权限、Pod Eviction 权限以及仅能更新 `gpu-platform-drain-worker` 的节点维护权限，并检查第二个 worker 与其他命名空间不可被修改或驱逐。

Docker Desktop Linux 引擎就绪后，在仓库目录执行：

```powershell
./scripts/setup-drain-lab.ps1
kubectl --kubeconfig .local/kubeconfig-drain-lab --context kind-gpu-platform-drain get nodes -o wide
```

本脚本只完成集群与 RBAC（基于角色的访问控制）准备，尚未将管理 API、执行器、PostgreSQL 或 Harbor 接到此集群，也没有执行真实 Pod 迁移。后续验收需使用独立数据库、仅本地保存的 worker 服务账号凭据和固定 digest 镜像；先确认服务位于目标 worker，再通过平台 drain 接口执行，观察该 Pod 从目标节点消失、在另一 worker 重新 Ready。随后分别验证 PDB（Pod 中断预算）拒绝、非托管 Pod 阻止、emptyDir/hostPath 阻止、执行器重启恢复与 uncordon。所有状态必须同时由平台操作记录和 Kubernetes 实际对象确认。完成前保持 `NODE_DRAIN_ENABLED=false`。

镜像预热不能靠启动任意用户镜像实现。Kubernetes 1.34 的 [image volume](https://v1-34.docs.kubernetes.io/docs/concepts/storage/volumes/#image) 可避免运行目标镜像入口，但其 ImageVolume 特性默认关闭且还依赖容器运行时支持。预热实现与验收需先选定并验证这一机制或等价的受控节点拉取接口；当前 `image_prewarm=false`。
