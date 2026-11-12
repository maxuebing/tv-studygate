# 部署指南

> 示例 IP：服务器 `192.168.1.100`，电视 `192.168.1.200`。请替换为你的实际地址，
> 并在路由器后台为两台设备做 DHCP 静态绑定（IP 漂移会导致电视连不上服务器）。

## 1. 服务器（家庭服务器 / NAS / 旧笔记本，需 24 小时开机）

要求：Linux + Docker。以 Ubuntu 为例：

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-plugin
sudo usermod -aG docker $USER
sudo systemctl enable --now docker

cd server
sudo docker compose up -d --build
curl http://192.168.1.100:8090/health   # {"status":"ok"}
```

浏览器打开 `http://192.168.1.100:8090`：

- **题库管理**：按学科/单元筛选、增删改查、批量导入 JSON；
- **题库配置**：按学科勾选「出题单元范围」、管理独立题库文件；
- **手机远程放行**：手机打开同一地址 →「手机远程放行」→ 一键放行（电视 4 秒内自动执行，无密码输入）；
- **锁屏规则与密码**：观看时长（默认 10 分钟）、答错惩罚、家长应急密码（默认 `1234`，**部署后立即修改**）。

### 可选：AI 一键出题

支持任何 OpenAI 兼容接口。在 `server/` 下创建 `.env`：

```ini
AI_API_BASE=https://api.deepseek.com/v1
AI_API_KEY=sk-xxxxxxxx
AI_MODEL=deepseek-chat
```

重启容器后「AI 一键出题」可用。生成后请人工抽查答案再启用。

### 数据备份

题库与配置都在 `server/data/quiz.db`，定期备份这个文件即可。

## 2. 电视端（Android TV 8.0+，已在小米电视 A55 竞技版验证）

需要一台装了 Docker 的电脑来编译（无需 Android Studio）：

```bash
cd tv-app
./build-and-install.sh 192.168.1.200 192.168.1.100
```

前置条件：电视开启 ADB（设置 → 账号与安全 → ADB 调试 → 允许 → 回到桌面），
电脑 `adb connect` 后在电视授权框勾选「一律允许」。

脚本自动完成：Docker 内编译 APK → ADB 安装 → 一次性写入悬浮窗/无障碍/后台保活权限 →
下发服务器题库地址 → 拉起应用。

## 3. 首次配置清单

- [ ] 修改家长应急密码（默认 `1234`）
- [ ] 设置观看时长 / 答错惩罚上限
- [ ] 导入题库，按单元勾选出题范围
- [ ] 手机收藏 `http://服务器IP:8090`（远程放行入口）
- [ ] 断电重启实测一次恢复锁屏
