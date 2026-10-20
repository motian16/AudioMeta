# AudioMeta

Android 本地音乐标签管理与多源刮削客户端。

AudioMeta 直接读写手机里的音频文件，适合已经整理好曲库、希望补齐封面、艺术家图、流派、歌词和基础标签的用户。

## 功能

- 扫描本地音频目录，按歌曲 / 艺术家 / 流派浏览
- 编辑标题、专辑、艺术家、专辑艺术家、流派、作词、作曲、日期、语言、注释
- 多选批量修改标签，多首值不同时显示「未修改」
- 多源刮削：歌曲信息、专辑封面、艺术家图、歌词
- 所有写入按标题 / 艺术家 / 专辑三项严格匹配，匹配不上不写
- 专辑封面优先 3000×3000，其次 1:1
- Salt Player（Windows）网络遥控，查看播放状态、封面和歌词
- 桌面小组件：卡片、沉浸、紧凑、歌词四种布局
- 明暗主题、动态取色、可调网格布局

插件、标签规则和订阅资源不在本仓库内。客户端支持通过订阅地址安装插件和规则。

## 构建

环境：

- JDK 17
- Android SDK 34
- Build Tools 35.0.0

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

本地发行版签名读取根目录的 `keystore.properties`；没有该文件时退回 debug 签名。签名材料和 `keystore/` 不会提交到仓库。

单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

部分测试会读取同机 checkout 的 `MetaAudio-Repo` 订阅规则（本仓库不包含插件和规则），因此干净克隆里直接跑测试会缺少测试夹具；GitHub Actions 的发行构建只执行 `assembleRelease`。

## 发布

`.github/workflows/release.yml` 支持手动触发：

1. 在 GitHub Actions 中运行 **Release** 工作流
2. 填入版本 tag，例如 `v1.5.0`
3. 工作流构建 APK 并发布到 GitHub Releases

仓库需要在 Actions Secrets 中配置：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | release keystore 的 Base64 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key 密码 |

未配置签名 secret 时构建会失败，避免发布不同签名的安装包。

## 目录

```
app/src/main/java/com/audiometa/app/
  data/       Room、DataStore、仓库
  domain/     标签规则、匹配、刮削、歌词
  media/      读取/写入标签、图片、扫描
  plugin/     插件运行时与安装
  remote/     Salt Player 遥控
  ui/         Compose 界面
  widget/     桌面小组件
```

## 说明

- 音频文件的所有修改都在本机完成，不上传云端。
- 刮削依赖用户自行配置的插件源；插件行为由其作者负责。
- 本项目只包含 Android 客户端源码，不包含第三方插件、标签规则或订阅内容。

## 许可证

本项目源代码采用 [MIT License](LICENSE)。

第三方依赖、插件和标签规则分别遵循其各自许可证；本项目不重新授权这些内容。
