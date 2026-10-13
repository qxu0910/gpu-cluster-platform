# M3 独立排空验收环境

`scripts/setup-drain-lab.ps1` 准备独立的 `gpu-platform-drain` kind 集群：一个控制平面、两个 worker，API 仅监听 `127.0.0.1:57443`。脚本只使用 `.local/kubeconfig-drain-lab`，不切换默认 kubeconfig、不重建现有 `gpu-platform`/Harbor 集群。它在独立集群安装平台 worker 的基础权限、Pod Eviction 权限以及仅能更新 `gpu-platform-drain-worker` 的节点维护权限，并检查第二个 worker 与其他命名空间不可被修改或驱逐。

Docker Desktop Linux 引擎就绪后，在仓库目录执行：

```powershell
./scripts/setup-drain-lab.ps1
kubectl --kubeconfig .local/kubeconfig-drain-lab --context kind-gpu-platform-drain get nodes -o wide
```

本脚本只完成集群与 RBAC（基于角色的访问控制）准备。后续使用独立数据库卷、独立项目身份与只保存在 `.local/` 的 24 小时受限 worker 令牌；Harbor 复用原本地测试仓库，但新节点单独安装项目 CA 和拉取配置。管理 API 绑定 `127.0.0.1:18082`，数据库绑定 `127.0.0.1:55433`，不占用原演示环境端口。

```powershell
./scripts/configure-drain-lab-worker-identity.ps1
./scripts/configure-drain-lab-registry.ps1
./scripts/run-drain-lab.ps1                # 默认关闭 drain，检查 API 与节点观测
./scripts/run-drain-lab.ps1 -EnableDrain   # 仅独立实验环境临时开启
./scripts/acceptance-drain-lab.ps1         # 真实上传、拉取、部署和迁移
./scripts/cleanup-drain-lab-acceptance.ps1 # 删除验收服务并恢复目标节点调度
./scripts/acceptance-drain-faults.ps1      # 非托管 Pod、PDB、幂等冲突与执行器重启恢复
./scripts/run-drain-lab.ps1                # 关闭实验 drain
```

验收脚本临时停止第二个 worker 的新调度，确保服务先在目标 worker 就绪，然后恢复第二个 worker；平台 drain 须观察原 Pod 离开，Kubernetes 须观察新 UID 的 Pod 在第二个 worker Ready。成功记录保存在忽略提交的 `.local/drain-acceptance-result.json`。脚本保留迁移后的服务与目标节点 cordon 状态供复核，随后执行清理脚本。脚本失败时应先检查实际 Pod 和节点状态，不要把残留对象算作通过。

2026-10-13 的真实 CPU 正常迁移已通过，平台操作为 `succeeded/drained`，迁移后服务返回 `v1`。同日独立故障脚本验证了跨命名空间非托管 Pod 阻止、PDB（Pod 中断预算）拒绝与解除、重复请求和冲突请求、执行器重启后的恢复；清理后三个节点均可调度。详情见 [测试记录](TESTING.md)。emptyDir/hostPath 的真实保护场景仍待验收，项目默认配置继续保持 `NODE_DRAIN_ENABLED=false`。

镜像预热不能靠启动任意用户镜像实现。Kubernetes 1.34 的 [image volume](https://v1-34.docs.kubernetes.io/docs/concepts/storage/volumes/#image) 可避免运行目标镜像入口，但其 ImageVolume 特性默认关闭且还依赖容器运行时支持。预热实现与验收需先选定并验证这一机制或等价的受控节点拉取接口；当前 `image_prewarm=false`。
