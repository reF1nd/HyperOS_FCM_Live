# 修复澎湃系统谷歌推送重连

去除澎湃系统对谷歌推送连接相关广播的限制。启用模块后，系统耗电可能会增加。

本分支的应用包名为 `io.reF1nd.hyperos.fcmlive`，可与原版分别安装。

## 构建与 CI

使用 JDK 21 和 Android SDK 37，通过 Gradle Wrapper 构建并检查：

```sh
./gradlew :HyperFCMLive:assembleDebug :HyperFCMLive:assembleRelease :HyperFCMLive:lintDebug
```

APK 位于 `HyperFCMLive/build/outputs/apk/{debug,release}/`。`versionCode` 为当前 `HEAD` 的提交计数，CI 保留完整 Git 历史；发布构建应避免浅克隆。

Android CI 在推送到 `main`、`reF1nd`，提交 PR 或手动触发时运行，上传两种 APK 和 Lint 报告。正式签名仅用于 `main`、`reF1nd` 的非 PR 构建；PR 和其他分支的手动构建使用调试签名。

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
