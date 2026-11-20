# 修复澎湃系统谷歌推送重连

去除澎湃系统对谷歌推送连接相关广播的限制。启用模块后，系统耗电可能会增加。

本分支的应用包名为 `io.reF1nd.hyperos.fcmlive`，可与原版分别安装。

## HyperOS 电源策略兼容

模块分别安装旧版 GMS 网络、闹钟和唤醒锁接口，以及 HyperOS 4 的网络状态接口。缺失的接口会记录 `Unavailable on this firmware`，不阻止其他兼容功能安装；网络限制参数改为 `false` 后仍执行系统原方法。

夜间联网白名单在读取时追加 `com.google.android.gms`，保留原有条目和后续云控内容，不写入持久化配置。模块使用系统消费名单时相同的 Context 和包名转 UID 方法；仅当原名单至少有一个包能解析出有效 UID，且 GMS 已安装时追加。空名单、全是未安装应用的名单和查询失败均保留原结果。

系统应用夜间联网规则时，模块会逐个检查已放行的 GMS UID。若该 UID 当时使用应用型 VPN，则把隧道所有者 UID 加入同一份临时名单，退出夜间模式时由系统清理。VPN 分应用规则让 GMS 直连时不会追加 VPN。豁免粒度为 UID，同一 VPN 进程的其他上游连接也会受益。

该白名单只影响系统通过应用 UID 过滤网络的休眠分支。如果系统直接关闭 Wi-Fi 或移动网络，添加白名单无法维持连接。模块不修改地震预警或夜间省电开关。

系统框架中的 GMS 闹钟投递、对齐和预算限制，以及小米的部分唤醒锁限制，按实际 GMS UID 豁免。闹钟重新计算时会清除已有的厂商 pending 延迟，并将实际时间变化交给系统重新调度；请求时间、Doze 和其他策略保持原值，因此热重载不表示所有已有闹钟会立即投递。共享唤醒锁的整体豁免要求所有实际归属 UID 均为 GMS；针对 GMS 的厂商禁用请求被忽略，不转换成启用请求。Doze 白名单通过追加副本兼容只读列表。

这些 GMS 电源兼容规则不受单个应用列表控制；设置界面中的推送和任务清理豁免继续按各自模式生效。作用域仍为“系统框架”和“电量和性能”。模块启用不代表所有接口均已适配；其他机型及后续固件仍需验证。

## 构建与 CI

使用 JDK 21 和 Android SDK 37，通过 Gradle Wrapper 构建并检查：

```sh
./gradlew :HyperFCMLive:assembleDebug :HyperFCMLive:assembleRelease :HyperFCMLive:testDebugUnitTest :HyperFCMLive:lintDebug
```

APK 位于 `HyperFCMLive/build/outputs/apk/{debug,release}/`。`versionCode` 为当前 `HEAD` 的提交计数，CI 保留完整 Git 历史；发布构建应避免浅克隆。

Android CI 在推送到 `main`、`reF1nd`，提交 PR 或手动触发时运行，上传两种 APK、单元测试和 Lint 报告。正式签名仅用于 `main`、`reF1nd` 的非 PR 构建；PR 和其他分支的手动构建使用调试签名。

Dependabot 的 Gradle 和 GitHub Actions 更新均指向 `reF1nd`。GitHub 从默认分支读取 `.github/dependabot.yml`；若默认分支仍为 `main`，需把此配置同步到 `main`，或将默认分支改为 `reF1nd`。CI 的分支触发和版本计数不依赖默认分支名称。

## 签名配置

在仓库的 **Settings → Secrets and variables → Actions → Repository secrets** 中设置：

| Secret | 内容 |
| --- | --- |
| `SIGNING_KEY` | 含私钥的 JKS/keystore 文件的 Base64 内容 |
| `KEY_STORE_PASSWORD` | 密钥库密码 |
| `ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 该别名对应私钥的密码 |

四项均未设置时，CI 使用调试签名生成测试 APK；部分设置会使构建失败。配置完整后，Debug 和 Release APK 都使用该密钥签名。CI 将密钥库写入 runner 临时目录，构建后删除；密码通过环境变量传递。

已有发布密钥时应继续使用原密钥。若首次创建密钥，可在仓库外执行以下命令，按提示设置密码和证书信息：

```sh
mkdir -p "$HOME/.android/keystores"
keytool -genkeypair -storetype JKS \
  -keystore "$HOME/.android/keystores/hyperfcm-release.jks" \
  -alias hyperfcm -keyalg RSA -keysize 3072 -validity 10000
```

此时 `ALIAS` 填 `hyperfcm`。若创建私钥密码时直接回车，私钥密码与密钥库密码相同，`KEY_PASSWORD` 和 `KEY_STORE_PASSWORD` 填相同值。在 macOS 上可将密钥库的 Base64 内容复制到剪贴板，再粘贴到 `SIGNING_KEY`：

```sh
base64 < "$HOME/.android/keystores/hyperfcm-release.jks" | tr -d '\n' | pbcopy
```

本地构建支持以下环境变量，优先于 `local.properties` 中对应的配置：

| 环境变量 | `local.properties` 键 |
| --- | --- |
| `SIGNING_STORE_FILE` | `storeFile` |
| `SIGNING_STORE_PASSWORD` | `storePassword` |
| `SIGNING_KEY_ALIAS` | `keyAlias` |
| `SIGNING_KEY_PASSWORD` | `keyPassword` |

`SIGNING_STORE_FILE`/`storeFile` 使用密钥库路径，支持绝对路径或相对仓库根目录的路径；本地不需要 Base64。四项需完整提供，未配置时使用调试签名。密钥库和 `local.properties` 已列入 Git 忽略规则。

保管好密钥库和密码。同一包名的 APK 覆盖更新需要使用相同签名，本分支后续发布应继续使用同一份密钥。若更换密钥，需先卸载已安装的同包名版本再安装，并重新配置模块。[Android 签名说明](https://developer.android.com/studio/publish/app-signing#considerations)
