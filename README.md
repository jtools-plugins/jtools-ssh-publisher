# SSH Publisher

IntelliJ IDEA 插件，提供 SSH 连接管理、终端操作和文件上传功能。

## 功能特性

### SSH 连接管理
- 支持密码和密钥两种认证方式
- 配置分组管理
- 连接测试功能
- 配置复制/编辑/删除

### SSH 终端
- 基于 JediTerm 的终端模拟器
- 自动适配 IDEA 主题颜色
- 支持常用快捷键
- 多终端标签页管理

### 文件上传 (SFTP)
- 支持自定义远程文件名（解决中文文件名乱码问题）
- 上传进度显示
- 前置/后置脚本支持
  - 可配置多个脚本
  - 上传时选择执行哪些脚本
  - 支持临时脚本（一次性执行）
- 脚本编辑器支持 Shell 语法高亮

## 数据存储

配置数据使用 SQLite 存储，路径：
```
~/.ssh-publisher/db.data
```

## 安装

1. 从 JetBrains Marketplace 安装
2. 或下载 zip 包手动安装

## 构建

```bash
./gradlew buildPlugin
```

构建产物位于 `build/distributions/` 目录。

## 使用说明

1. 打开右侧工具栏 "SSH Publisher"
2. 点击工具栏"新建配置"添加 SSH 连接
3. 填写主机、端口、用户名等信息
4. 选择认证方式（密码/密钥）
5. 可选配置前置/后置脚本
6. 双击配置打开终端，右键菜单可上传文件

## 兼容性

- IntelliJ IDEA 2022.3+
- 需要 Terminal 插件

## License

MIT
