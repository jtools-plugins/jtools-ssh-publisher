# JTools SSH Publisher

一个用于 JTools 的 SSH 客户端插件，支持 SSH 连接管理、终端操作和文件上传功能。

## 功能特性

### SSH 连接管理
- 支持密码和密钥两种认证方式
- 配置分组管理
- 连接测试功能
- 配置复制/编辑/删除

### SSH 终端
- 基于 JediTerm 的终端模拟器
- 自动适配 IDEA 主题颜色
- 支持常用快捷键（Ctrl+C/V、Tab 等）
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
~/.jtools/jtools-ssh-publisher/db.data
```

## 依赖

- Apache MINA SSHD (SSH/SFTP 客户端)
- SQLite JDBC (数据存储)
- JediTerm (终端模拟)
- IntelliJ Terminal Plugin

## 构建

```bash
./gradlew shadowJar
```

## 使用说明

1. 点击工具栏"新建配置"添加 SSH 连接
2. 填写主机、端口、用户名等信息
3. 选择认证方式（密码/密钥）
4. 可选配置前置/后置脚本
5. 双击配置打开终端，右键菜单可上传文件

## License

MIT
