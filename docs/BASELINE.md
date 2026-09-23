# 软件与环境基线

| 组件 | 固定版本 | 用途 |
|---|---|---|
| Java | 21 | 编译与运行；本机已验证 Temurin 21.0.12.1 |
| Spring Boot | 3.5.16 | 接口、安全、数据库与健康 |
| Maven | 3.9.11 | 构建 |
| PostgreSQL | 17.6 | 本地真实数据库 |
| Kubernetes Java client | 25.0.0 | 与 Kubernetes 1.34 对应 |
| kind | 0.30.0 | 独立本地 CPU 集群 |
| kind node | 1.34.0 | Kubernetes/containerd |
| Helm | 3.18.6 | Harbor 安装 |
| Harbor chart | 1.18.0 | Harbor 2.14 系列 |
| springdoc | 2.8.16 | OpenAPI |
| BusyBox | 1.37.0 | 小型 CPU 验收服务 |
| Skopeo | 1.20.0 | 带受限授权和 CA 校验的镜像推送 |
| 本地服务容器 JRE | eclipse-temurin:21.0.8_9-jre-alpine | Dockerfile.local 实际运行镜像 |
| Docker Desktop / Engine | 4.90.0 / 29.7.2 | 本地验收宿主 |
| containerd | 2.1.3 | kind 节点运行时，显式启用 certs.d |

部署模板禁用浮动 latest。Flyway、JUnit 和安全依赖随 Spring Boot BOM 固定。容器补丁版本与本机 Java 补丁可能不同，完整记录以实际验收镜像 digest 为准。

官方依据：[Spring Boot 要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Java 客户端兼容矩阵](https://github.com/kubernetes-client/java/wiki/2.-Versioning-and-Compatibility)、[Harbor 机器人账号](https://goharbor.io/docs/2.14.0/working-with-projects/project-configuration/create-robot-accounts/)。

以下项目尚未取得现场事实：B300 整机/互联、每节点卡数、驱动/CUDA/GPU Operator/固件兼容组合、生产网段与出口、带外管理、额外控制平面硬件。不得用本地 CPU 基线替代生产兼容基线。
