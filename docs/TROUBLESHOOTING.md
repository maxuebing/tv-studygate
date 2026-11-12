# 故障排查手册

以下在小米电视 A55 竞技版（Android 9）实测。示例 IP：服务器 `192.168.1.100`、电视 `192.168.1.200`。

## 快速定位：电视"不锁屏"了

先在电视上打开 App 主界面看「关键权限自检」：

| 现象 | 结论 | 修复 |
|---|---|---|
| 手动测试锁屏能弹出，但平时不触发 | 进程被系统杀后未自启 | 见 4.8 SOP 重写权限并拉起服务 |
| 悬浮窗权限显示未开启 | 遮罩弹不出 | 见 4.8 SOP 第 3 步 |
| 无障碍权限显示未开启 | 防逃逸与开机拉起失效 | 见 4.8 SOP 第 3 步 |
| 都正常但不出题 | 出题范围配置为空 / 题库被全部停用 | 网页题库配置页检查 |

## 1. ADB 连接问题

### 1.1 `failed to authenticate` / `unauthorized`

- 电视必须停在系统桌面（授权弹框不出现在设置页/HDMI 信号源/应用内）；
- 关闭儿童模式/青少年模式（会拦截授权弹框）；
- 清空电脑端密钥重试：`rm ~/.android/adbkey* && adb kill-server && adb start-server && adb connect 电视IP:5555`；
- 电视断电重启后再试（adbd 偶发卡死）。

### 1.2 `device not found`

电视 ADB 开关被关了（部分固件断电后自动重置）→ 重新打开 → `adb connect`。

## 2. 安装问题

### 2.1 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不一致）

两次构建使用了不同签名密钥。解决：固定一台编译机使 debug 签名一致，或自建正式密钥；
临时处理：`adb uninstall 包名` 后重装（会清空电视端已落盘进度）。

## 3. 电视端功能问题

### 3.1 断电重启后守护进程没有自动拉起

```bash
adb -s 电视IP:5555 shell "dumpsys package 包名 | grep 'User 0:'; ps -A | grep 包名; settings get secure enabled_accessibility_services; logcat -d | grep -i 你的TAG | tail -n 20"
```

- `stopped=true`：应用被厂商管家强杀。修复：重写权限 + 拉起；
- 无障碍服务不在列表：`settings put secure enabled_accessibility_services 包名/.服务名; settings put secure accessibility_enabled 1`；
- `SYSTEM_ALERT_WINDOW` 被撤销：`appops set 包名 SYSTEM_ALERT_WINDOW allow`。

## 4. 服务器问题

### 4.1 接口题数与预期不符

改过 seed 文件后必须 `docker compose up -d --build` 重建镜像（`up -d` 沿用旧镜像）。
重置题库：`docker compose down && rm -f server/data/*.db && docker compose up -d --build`。

### 4.2 电视拉不到题库 JSON

- 确认电视能 ping 通服务器；
- 确认接口：`curl http://服务器IP:8090/api/quiz.json`；
- 电视端题库地址可在 App 主界面重新配置。

## 5. 设计边界（已知不做的事）

- 不阻止卸载 App、不阻止使用其他设备；
- 视频暂停是尽力而为的系统级请求；
- 悬浮窗无法覆盖系统级弹窗（语音助手等）弹出的瞬间，但会在 1 秒内重新置顶；
- 无障碍服务被系统撤销时（部分品牌升级后发生），防逃逸降级，需重新授权。
