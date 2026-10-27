# Poko annotations 0.23.1 的 OHOS 入口

本目录只构建上游 `poko-annotations/src/commonMain/kotlin` 原始三份 Kotlin 源；上游仓库 `drewhamilton/Poko`、tag `0.23.1` 与 commit `7f2bb9beeea0b9590b27056a613d9fa42b20f068` 不变。`source-inputs.json` 固定三源、Maven Central sources JAR/GMM/POM 及 Apache-2.0 LICENSE 的大小和 SHA256；源码 JAR 和 GMM 同时与应用冻结 Git commit `fe48ee6140d9224adf56b4b09efcfdc468ae8aa0` 中原件一致。官方 POM 明确属于 `dev.drewhamilton.poko:poko-annotations:0.23.1`，其上游 stdlib runtime 为 2.4.0；本 OHOS 配方使用 CPF `.08` stdlib，不改写原 POM。源 JAR 只用于核对原源，不解包为第二份生产源码。

工具固定 JDK 17、Gradle 9.5.0 和 Kotlin/KGP/Native `2.4.20-0.1.0-08`。`compileCommonMainKotlinMetadata`、`compileKotlinOhosX64`、`compileKotlinOhosArm64` 都使用标准 KGP；不预加 metadata CLI fallback。公共 host 工具由 `tooling-lock.json` 固定到工具仓 commit `d0cff88fd1ea2e4f08bb4409f1c802cca0c7f285`（A），逐文件核大小和 SHA256；默认匿名取得该 commit 的归档，压缩容器哈希不参与身份，`--tooling <directory>` 也必须通过相同逐文件检查。运行 workflow 的工具仓 commit（B）单独记录，不能把 B 当成 A。公共锁分别限定 commonizer 的 9 项与 Native 编译器的 14 项 JVM 工具；annotations 13 和 23 仅各自在宿主配置内选用，不作为库运行依赖。

发布 root 为 `io.github.qsxs.cpf.poko:poko-annotations`，版本格式为 `0.23.1-cpf11-preview-<producerSHA12>-<runID>-<attempt>`；两 Native leaf 通过 root GMM 选择。Poko annotations 的 producer 不使用 Poko 编译器插件；下述 Gradle 9 consumer 使用 0.23.2 插件，它不是 annotations 库的 runtime 依赖。仓库中没有自动部署或凭据。

手动工作流在全新 Linux runner 隔离 Gradle/Konan/work/temp，先核对 SHA、来源和工具，再执行 Tasks、Resolve、标准 Metadata、NativeX64、NativeArm64、Publish、Verify，只有验证成功才上传 staging 供人工审核。显式参数的本地入口格式如下；实际 Gradle 和 Native 执行须在已授权的远端资源窗口运行：

```sh
python -B ohos/PokoPort.py Prepare --source-sha <40-hex> --run-id <id> --run-attempt <attempt> --work <isolated-work> --repository <staging>
# 用已核内容的 A 运行 bootstrap，取得独立 native home 后依次执行：
python -B ohos/PokoPort.py Tasks --source-sha <40-hex> --run-id <id> --run-attempt <attempt> --work <isolated-work> --repository <staging> --native-home <native-home>
# 将 Tasks 替换为 Resolve、Metadata、NativeX64、NativeArm64、Publish、Verify，按顺序执行。
```

## 已验公开版本

已验根 GAV：`io.github.qsxs.cpf.poko:poko-annotations:0.23.1-cpf11-preview-cbaa758d0b76-40451679040-1`；GMM 选择 OHOS x64 和 arm64 leaf。[Fork source `cbaa758d0b76`](https://github.com/qsxs/Poko/commit/cbaa758d0b7610e0e445b8fd50739aa05afaea6e) 经 [producer 40451679040/1](https://github.com/qsxs/cpf-port-tooling/actions/runs/40451679040/attempts/1) 完成 metadata、双 Native 编译和 staging Verify；[Pages 制品提交 `7c7abe30bb95`](https://github.com/qsxs/cpf-maven/commit/7c7abe30bb9510d0290794522471cb99469f6dac) 的 110 个文件、158468 字节与 staging 匿名逐字节对齐。临时 Maven 根为 `https://qsxs.github.io/cpf-maven/`（[制品仓库](https://github.com/qsxs/cpf-maven)），App 尚未切换。

独立 consumer 的 [40457201932 第 1 次](https://github.com/qsxs/cpf-port-tooling/actions/runs/40457201932/attempts/1) Gradle 8.11.1 job 和 [第 2 次](https://github.com/qsxs/cpf-port-tooling/actions/runs/40457201932/attempts/2) Gradle 9.5 job 均真实编译 metadata、OHOS x64 和 arm64。Gradle 8 不加载 Poko 插件；Gradle 9 使用官方 0.23.2 插件，检查自有 probe 的 `equals`、`hashCode`、`toString` 声明与签名，双 Native 检查非空 IR。第 2 次的两个 job 均报告成功；详细 artifact 证据分别以 Gradle 8 第 1 次和 Gradle 9 第 2 次为准。

第 1 次 Gradle 9 在下载官方 OHOS sysroot 时超时，不能将整次运行记为成功；保留原失败记录后，第 2 次重试成功。未检查方法体，也未验证 link、runtime、设备或 Android/iOS 回归。Gradle 8 使用冻结的应用 Harmony 宿主 wrapper，未修改应用构建。
