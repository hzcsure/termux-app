# Keystore 配置说明

本文件由 GitHub Actions `Generate Keystore` workflow 自动生成。

## 文件说明

| 文件 | 说明 |
|------|------|
| `release-keystore.jks` | 签名密钥文件 (二进制) |
| `keystore-base64.txt` | base64 编码的密钥 (用于配置 GitHub Secret) |

## 配置 Release 签名 Secret

1. 打开仓库 **Settings → Secrets and variables → Actions**
2. 添加以下 4 个 Secret:

| Secret 名称 | 值 |
|-------------|---|
| `KEYSTORE_BASE64` | `keystore-base64.txt` 文件的完整内容 |
| `KEYSTORE_PASSWORD` | 生成时输入的 keystore 密码 |
| `KEY_ALIAS` | 生成时输入的 key 别名 |
| `KEY_PASSWORD` | 生成时输入的 key 密码 |

3. 配置完成后, 打 tag `v0.119.0` 即可触发 Release 工作流自动签名构建

## ⚠️ 安全警告

- **如果仓库是 public, 此 keystore 已暴露!** 任何人可以伪造你的 APK 签名
- 建议: 配置完 Secret 后删除 `release-keystore.jks` 和 `keystore-base64.txt`
- 或将仓库设为 private
