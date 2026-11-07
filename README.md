# 远程音乐

单管理员、单台安卓播放机。网页可从任意浏览器登录，VPS 中转控制指令并保存曲库索引。声音由安卓设备播放。索引使用文件名和曲目 ID，原始文件和目录路径留在手机上。手机重启后手动开启待命。

## Docker Compose

Compose 只运行音乐服务，默认将端口绑定到 VPS 的 127.0.0.1:8080。同一台 VPS 上运行的 cloudflared 提供公网入口与 HTTPS。Tunnel 使用出站连接，音乐服务无需开放公网入站端口。

1. 将 compose.yaml 和 .env.example 放到 VPS 的同一目录。将 .env.example 复制为 .env，填写公网域名 MUSIC_DOMAIN 和至少 16 字节的随机 ADMIN_PASSWORD。可用 openssl rand -hex 24 生成密码，并用 chmod 600 .env 限制权限。
2. 执行 docker compose pull 和 docker compose up -d --remove-orphans。使用旧版 Compose 时沿用原目录或原项目名，以保留曲库和配对数据。用 curl -fsS http://127.0.0.1:8080/ 检查本机入口。若修改 MUSIC_PORT，请同步修改下方 Tunnel 回源端口。
3. 在 [Cloudflare Tunnel](https://developers.cloudflare.com/tunnel/get-started/) 添加 Published application。公开主机名填写 MUSIC_DOMAIN，Service URL 填 http://127.0.0.1:8080。启用公网 HTTPS 并允许 WebSocket。若 cloudflared 也在容器里，需使用宿主机网络才能通过这个回环地址访问。
4. 打开公网 HTTPS 地址登录，在「设置」里生成一次性配对码。在 APK 的「设置」输入同一 HTTPS 地址和配对码，选择本地音乐子目录，开启远程待命。该域名的设备接口使用播放器自己的配对鉴权，APK 无法通过 Cloudflare Access 的交互式登录页。

本地构建时，可执行 docker build -t remote-music:local .，在 .env 中设 MUSIC_IMAGE=remote-music:local，再执行 docker compose up -d --remove-orphans。

VPS 的持久卷保存曲库索引和设备凭据摘要。重启容器后需重新登录网页。换机或凭据泄露可在网页解绑，已连接的设备会立即断开。更换管理员密码后重建容器会清除现存网页会话。

## 安卓待命

- 在手机上新建或选择歌单，再到「本地曲库」点按曲目加入。歌单保存在手机上。手机和网页都能选择现有歌单、点播其中的歌曲和切换循环模式。顺序播放到最后一首后停止，列表循环会从第一首重播，单曲循环在歌曲播完后重播当前曲目。

- 允许通知，在应用打开时手动开启待命。服务持有部分唤醒锁以便处理熄屏指令，屏幕照常熄灭。结束使用时可从通知关闭待命。
- 待命连接使用 Wi-Fi 网络的套接字。手机可继续本地播放，控制通道掉线期间 VPS 不缓存指令。
- 红米 Turbo 4 Max 上验收时，插电、连 Wi-Fi，确认在线后暂停并熄屏至少一夜。次日从另一网络登录网页点歌，观察手机无需点亮屏幕即可出声。然后在待命中断开 Wi-Fi 再恢复，确认可以重新点歌。若失败，记录 Android / HyperOS 版本和通知状态，再分别测试应用电池设为无限制以及最近任务加锁。
- 服务被强行停止或手机重启后，从手机重新打开应用并开启待命。

## 构建和校验

GitHub Actions 在推送和 PR 上检查 Go、网页脚本以及安卓 Lint，并编译 APK。主分支构建通过后把网页管理端镜像推送到 GHCR。推送 v 开头的版本标签后，工作流会把 remote-music.apk 发布到 [GitHub Releases](https://github.com/Sora3QwQ/music-player/releases)，可直接下载并安装。

默认 APK 使用 CI 调试密钥签名，覆盖安装需要相同签名。长期自用可通过仓库 Actions Secrets 配置 APK_KEYSTORE_BASE64、APK_STORE_PASSWORD、APK_KEY_ALIAS 和 APK_KEY_PASSWORD，让后续标签构建使用稳定的发布签名。保管好签名密钥。

本地服务测试运行 go test ./...。安卓构建使用 JDK 17、Gradle 8.11.1 和 Android SDK 35，命令为 gradle -p android lintDebug assembleDebug。该仓库的自动化测试模拟设备协议，熄屏和 Wi-Fi 恢复以真机结果为准。

## 安全边界

网页 API 使用安全会话 Cookie 与同源写入校验。设备使用独立的随机凭据，配对码五分钟有效且使用一次。安卓服务仅处理固定的播放器命令，只能播放已选目录内扫描到的曲目。VPS 可查看曲库索引并向已配对设备发播放指令，因此管理员密码和 VPS 访问权限应单独保管。
