# 位置助手 (Location Helper)

一款 Android 位置伪装工具，基于 NetBare HTTP 注入，支持腾讯地图地理编码接口的位置替换。

## ✨ 功能

- 🗺️ **预设位置选择** - 内置全国主要省市地区（北京、上海、广州、深圳、杭州、成都等 20+ 城市）
- 📍 **省/市/区三级联动** - 一键选择目标位置
- 📢 **公告系统** - 从服务器拉取公告，支持重要公告标记
- 🔄 **版本更新** - 支持强制更新提示
- ⚙️ **后台管理** - Web 端管理面板，管理公告和配置

## 📦 构建

```bash
# 使用 Android Studio 打开项目
# 或命令行构建
./gradlew assembleRelease
```

## 🚀 部署

### 1. 上传后台管理面板

将 `admin/` 目录下的文件上传到 `http://dl.wzydqq.icu/`:
- `index.html` - 后台管理页面
- `config.json` - 公告配置文件

### 2. 修改配置

访问 `http://dl.wzydqq.icu/index.html` 进入后台管理面板：
- 添加/删除公告
- 设置版本号和下载地址
- 生成并导出 `config.json`

### 3. APK 下载地址

将构建好的 APK 上传到 `http://dl.wzydqq.icu/app.apk`

## 📁 项目结构

```
app/src/main/java/com/wzydqq/icu/
├── App.kt                    # Application 入口
├── AnnouncementConfig.kt     # 公告配置数据 & 管理器
├── injector/
│   └── Link.kt               # HTTP 注入器（核心）
├── location/
│   ├── SelectedLocation.kt   # 位置数据模型
│   ├── LocationStore.kt      # 位置存储
│   └── PresetLocations.kt    # 预设位置数据
└── ui/
    ├── MainActivity.kt        # 主界面
    ├── LocationPickerActivity.kt  # 位置选择器
    └── AnnouncementActivity.kt    # 公告页面
```

## 📋 config.json 格式

```json
{
  "enabled": true,
  "title": "公告标题",
  "version": "1.0.0",
  "update_url": "http://dl.wzydqq.icu/app.apk",
  "force_update": false,
  "notices": [
    {
      "id": 1,
      "title": "公告标题",
      "content": "公告内容",
      "time": "2026-04-23 23:00",
      "important": true
    }
  ]
}
```

## 包名

`com.wzydqq.icu`
