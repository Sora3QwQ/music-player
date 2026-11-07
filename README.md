# 远程音乐

单管理员、单台安卓播放机。网页可从任意浏览器登录，VPS 中转控制指令并保存曲库索引。声音由安卓设备播放。索引使用文件名和曲目 ID，原始文件和目录路径留在手机上。手机重启后手动开启待命。

## Docker Compose

将域名解析到 VPS，并允许入站 TCP 80 和 443。Compose 由 Caddy 获取证书，应用端口只在 Compose 网络内使用。已有反向代理的 VPS 可自行复用 HTTPS 入口，代理需支持 WebSocket，PUBLIC_ORIGIN 应与浏览器访问的 HTTPS 源完全相同。

1. 将 compose.yaml、Caddyfile 和 .env.example 放到 VPS 的同一目录。
2. 将 .env.example 复制为 .env，填写 MUSIC_DOMAIN 和至少 16 字节的随机 ADMIN_PASSWORD。可以用 openssl rand -hex 24 生成密码，并用 chmod 600 .env 限制权限。
3. 执行 docker compose pull 和 docker compose up -d。GHCR 包若是私有的，先用有 read:packages 权限的凭据登录 ghcr.io。也可执行 docker build -t remote-music:local . 并在 .env 中设 MUSIC_IMAGE=remote-music:local，再执行 docker compose up -d。
4. 打开 HTTPS 地址并登录。生成一次性配对码，在 APK 输入同一 HTTPS 地址和配对码。选择一个安卓本地音乐子目录，开启远程待命。网页显示在线后即可点歌。

VPS 的持久卷保存曲库索引和设备凭据摘要。重启容器后需重新登录网页。换机或凭据泄露可在网页解绑，已连接的设备会立即断开。更换管理员密码后重建容器会清除现存网页会话。

## 安卓待命

- 允许通知，在应用打开时手动开启待命。服务持有部分唤醒锁以便处理熄屏指令，屏幕照常熄灭。结束使用时可从通知关闭待命。
- 待命连接使用 Wi-Fi 网络的套接字。手机可继续本地播放，控制通道掉线期间 VPS 不缓存指令。
- 红米 Turbo 4 Max 上验收时，插电、连 Wi-Fi，确认在线后暂停并熄屏至少一夜。次日从另一网络登录网页点歌，观察手机无需点亮屏幕即可出声。然后在待命中断开 Wi-Fi 再恢复，确认可以重新点歌。若失败，记录 Android / HyperOS 版本和通知状态，再分别测试应用电池设为无限制以及最近任务加锁。
- 服务被强行停止或手机重启后，从手机重新打开应用并开启待命。

## 构建和校验

GitHub Actions 在推送和 PR 上检查 Go、网页脚本以及安卓 Lint，编译 APK 和 Docker。主分支构建通过后推送镜像到 GHCR，并按提交哈希打标签。Actions 运行页可下载测试 APK。测试 APK 的签名可能随构建机器变化，覆盖安装需使用同一签名。长期自用可通过仓库 Actions Secrets 配置 APK_KEYSTORE_BASE64、APK_STORE_PASSWORD、APK_KEY_ALIAS 和 APK_KEY_PASSWORD 构建稳定签名的发布 APK。保管好签名密钥。

本地服务测试运行 go test ./...。安卓构建使用 JDK 17、Gradle 8.11.1 和 Android SDK 35，命令为 gradle -p android lintDebug assembleDebug。该仓库的自动化测试模拟设备协议，熄屏和 Wi-Fi 恢复以真机结果为准。

## 安全边界

网页 API 使用安全会话 Cookie 与同源写入校验。设备使用独立的随机凭据，配对码五分钟有效且使用一次。安卓服务仅处理固定的播放器命令，只能播放已选目录内扫描到的曲目。VPS 可查看曲库索引并向已配对设备发播放指令，因此管理员密码和 VPS 访问权限应单独保管。
