# Termux-App 项目架构分析 & Fork 后 CI/CD 指南

## 1. 项目概览

**termux-app** 是 Android 上最流行的终端模拟器应用，采用纯 Java + NDK（C） 构建。

| 维度 | 详情 |
|------|------|
| 主语言 | Java（应用层）+ C（native 终端） |
| 构建工具 | Gradle 9.2.1 + Android Gradle Plugin 8.13.2 |
| JDK | 17（CI 环境），源码兼容性 Java 1.8 |
| 最低 SDK | 21（Android 5.0） |
| 目标 SDK | 28（Android 9） |
| 编译 SDK | 36 |
| NDK | 29.0.14206865 |
| 版本规范 | 语义化版本 2.0.0（`major.minor.patch(-prerelease)(+build)`） |

---

## 2. 模块架构

```
termux-app/
├── app/                    # 主应用模块 (com.android.application)
│   ├── src/main/cpp/       # NDK 原生代码 (Android.mk 构建终端)
│   ├── src/main/java/      # Java 应用代码
│   └── testkey_untrusted.jks  # Debug 签名密钥 (公开)
├── terminal-emulator/      # 终端模拟器核心库 (com.android.library)
├── terminal-view/          # 终端视图组件 (com.android.library, 支持 Maven 发布)
├── termux-shared/          # 共享常量 & 工具库 (com.android.library)
├── build.gradle            # 根项目构建脚本
├── settings.gradle         # 模块声明: app, termux-shared, terminal-emulator, terminal-view
├── gradle.properties       # SDK 版本、依赖版本
└── gradle/wrapper/         # Gradle Wrapper (9.2.1)
```

### 模块依赖关系

```
app ──→ terminal-view ──→ terminal-emulator
  └──→ termux-shared
```

---

## 3. 构建关键点

### 3.1 Bootstrap 包下载

构建时自动从 GitHub 下载 bootstrap zip（预编译的 apt 包），校验 SHA-256 后打包进 APK。这是 termux 的核心机制——APK 内嵌完整的 Linux 文件系统。

- 支持 `apt-android-7`（Android 7+，主要）和 `apt-android-5`（Android 5-6，旧版）两种 variant
- 通过 `TERMUX_PACKAGE_VARIANT` 环境变量切换
- 下载逻辑在 `app/build.gradle` 的 `downloadBootstraps` task 中

### 3.2 Split APKs

默认对 debug 构建启用 split APKs（按 ABI 分包）：

| APK | 架构 | 适用设备 |
|-----|------|---------|
| universal | 所有 | 通用 |
| arm64-v8a | ARM 64位 | 现代手机（主流） |
| armeabi-v7a | ARM 32位 | 旧手机 |
| x86_64 | Intel/AMD 64位 | 模拟器 |
| x86 | Intel 32位 | 旧模拟器 |

通过 `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS` / `TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS` 控制。

### 3.3 签名

- **Debug**: 使用仓库内公开的 `testkey_untrusted.jks`
- **Release**: 需要外部注入签名（通过 `android.injected.signing.*` 参数或 secrets）

### 3.4 版本号

`versionName` 遵循 semver 2.0.0，构建时通过 `TERMUX_APP_VERSION_NAME` 环境变量覆盖，格式：

```
v<version>+<commit_hash>       # CI 构建: v0.118.0+abc1234
v<version>                      # Release: v0.119.0
```

---

## 4. 测试体系

| 类型 | 框架 | 命令 |
|------|------|------|
| 单元测试 | JUnit 4.13.2 + Robolectric 4.10 | `./gradlew testDebugUnitTest` |
| Lint | Android Lint | `./gradlew lintDebug` |

Robolectric 配置了 `includeAndroidResources = true`，可在 JVM 上模拟 Android 环境。

---

## 5. CI/CD 工作流设计

### 5.1 整体流程

```
                    ┌──────────────────────────────────────────┐
                    │              开发者 push / PR             │
                    └──────────────┬───────────────────────────┘
                                   │
                    ┌──────────────▼───────────────────────────┐
                    │           ci.yml (CI 构建)               │
                    │                                          │
                    │  Job: test (单元测试 + Lint)             │
                    │         │                                │
                    │         ▼                                │
                    │  Job: build (Debug APK x2 variant)      │
                    │         │                                │
                    │         ▼                                │
                    │  上传 artifact (APK + sha256)            │
                    └──────────────────────────────────────────┘
                                   │
                    ┌──────────────▼───────────────────────────┐
                    │        开发者打 tag v0.119.0             │
                    └──────────────┬───────────────────────────┘
                                   │
                    ┌──────────────▼───────────────────────────┐
                    │         release.yml (CD 发布)            │
                    │                                          │
                    │  Job: build-release                      │
                    │    - 解码签名 keystore                   │
                    │    - 构建签名 Release APK (x2 variant)   │
                    │    - 生成 sha256sums                     │
                    │         │                                │
                    │         ▼                                │
                    │  Job: publish-release                    │
                    │    - 创建 GitHub Release                 │
                    │    - 上传所有 APK + 校验文件              │
                    └──────────────────────────────────────────┘
```

### 5.2 工作流文件

| 文件 | 触发 | 作用 |
|------|------|------|
| `.github/workflows/ci.yml` | push/PR 到 master | 单元测试 + Lint + 构建 Debug APK |
| `.github/workflows/release.yml` | push tag `v*` | 签名 Release 构建 + GitHub Release |
| `.github/workflows/code-quality.yml` | push/PR 到 master | Lint + 依赖提交 |

### 5.3 CI 关键优化

1. **并发取消**: 同分支新 push 自动取消旧 run（`concurrency.cancel-in-progress: true`）
2. **Gradle 缓存**: 使用 `gradle/actions/setup-gradle@v4` 自动缓存依赖
3. **缓存读写控制**: 只有 master/main 分支可写缓存，其他分支只读
4. **矩阵构建**: 两种 package variant 并行构建
5. **超时保护**: 每个 job 设置 timeout 防止卡死

---

## 6. Fork 后配置步骤

### 6.1 必做：配置 Release 签名密钥

1. 生成签名 keystore（本地执行）：

```bash
keytool -genkey -v \
  -keystore release-keystore.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias your-alias
```

2. Base64 编码：

```bash
base64 release-keystore.jks > keystore-base64.txt
# macOS: base64 -i release-keystore.jks
```

3. 在 fork 仓库 **Settings → Secrets and variables → Actions** 添加以下 Secrets：

| Secret 名称 | 值 | 说明 |
|-------------|---|------|
| `KEYSTORE_BASE64` | keystore-base64.txt 的内容 | base64 编码的 jks 文件 |
| `KEYSTORE_PASSWORD` | keystore 密码 | 生成时输入的 store password |
| `KEY_ALIAS` | 别名 | 如 `your-alias` |
| `KEY_PASSWORD` | key 密码 | 生成时输入的 key password |

### 6.2 触发 CI

- **自动构建**: push 到 `master` / `main` 或提交 PR
- **发布 Release**: 创建 tag `git tag v0.119.0 && git push origin v0.119.0`

### 6.3 可选：修改应用包名

如果需要改包名避免和官方冲突，修改 `app/build.gradle` 中的 `manifestPlaceholders`：

```groovy
manifestPlaceholders.TERMUX_PACKAGE_NAME = "com.yourname.termux"
```

---

## 7. 本地验证命令

```bash
# 构建 Debug APK (universal, 不分包)
TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0 ./gradlew assembleDebug

# 运行单元测试
./gradlew testDebugUnitTest

# 构建 Release APK (需签名参数)
export TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS=1
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file="$(pwd)/app/release-keystore.jks" \
  -Pandroid.injected.signing.store.password=YOUR_PASSWORD \
  -Pandroid.injected.signing.key.alias=YOUR_ALIAS \
  -Pandroid.injected.signing.key.password=YOUR_KEY_PASSWORD

# 查看 APK 输出
ls app/build/outputs/apk/debug/
ls app/build/outputs/apk/release/
```

---

## 8. 注意事项

1. **NDK 下载大**: 首次构建会下载 NDK（~1GB），CI 需 10-15 分钟
2. **Bootstrap 下载**: 构建时自动从 GitHub 下载 bootstrap zip，需网络通畅
3. **F-Droid 兼容**: F-Droid 不支持 split APKs，所以 release 默认 `TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS=0`；我们的 CI 强制设为 `1` 以生成多架构包
4. **Java 17 必须**: AGP 8.x 要求 JDK 17+，工作流中使用 `temurin` 17
5. **Android 12+ 幻影进程**: Android 12+ 可能杀死超过 32 个的子进程，这是系统限制非 bug
