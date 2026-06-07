# Members Plugin 贡献指南

感谢你对 Members Plugin 项目的关注！我们欢迎任何形式的贡献。

## 📋 目录

- [行为准则](#行为准则)
- [如何贡献](#如何贡献)
- [开发环境设置](#开发环境设置)
- [代码规范](#代码规范)
- [提交 Pull Request](#提交-pull-request)

## 行为准则

- 尊重所有贡献者
- 建设性地讨论问题
- 遵循项目代码规范
- 编写清晰的提交信息

## 如何贡献

### 报告 Bug

1. 在 Issues 中搜索现有问题
2. 如果没有找到，创建新的 Issue
3. 提供详细的错误信息和复现步骤

### 提交功能请求

1. 创建新 Issue
2. 详细描述功能需求和用例
3. 说明预期的行为

### 提交代码

1. Fork 项目仓库
2. 创建功能分支
3. 进行开发并编写测试
4. 提交 Pull Request

## 开发环境设置

### 环境要求

- Java >= 21
- Node.js >= 16
- pnpm >= 8
- Halo >= 2.22.9

### 克隆仓库

```bash
git clone https://github.com/chenhufei/plugin-members.git
cd plugin-members
```

### 安装依赖

```bash
# 安装前端依赖
cd ui
pnpm install
```

### 运行开发模式

```bash
# 运行 Halo 开发环境
./gradlew haloRun
```

### 构建插件

```bash
./gradlew build
```

## 代码规范

### Java 代码规范

- 使用 4 空格缩进
- 类名使用大驼峰（PascalCase）
- 方法名和变量名使用小驼峰（camelCase）
- 常量使用全大写下划线分隔（UPPER_SNAKE_CASE）
- 每个公共方法必须有 Javadoc 注释

### JavaScript/TypeScript 代码规范

```bash
# 检查代码
cd ui
pnpm lint

# 自动修复
pnpm lint:fix

# 格式化代码
pnpm format
```

### 提交信息规范

使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

```
<type>(<scope>): <subject>

<body>

<footer>
```

**类型（type）：**
- `feat`: 新功能
- `fix`: Bug 修复
- `docs`: 文档更新
- `style`: 代码格式（不影响功能）
- `refactor`: 重构
- `perf`: 性能优化
- `test`: 测试相关
- `chore`: 构建/工具相关

**示例：**
```
feat(api): add public API endpoint

Add new anonymous API endpoints for frontend themes
to access member data without authentication.

Closes #123
```

## 提交 Pull Request

### PR 流程

1. 更新到最新主分支：
   ```bash
   git checkout master
   git pull upstream master
   ```

2. 创建功能分支：
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. 进行开发并提交

4. 推送到你的 Fork：
   ```bash
   git push origin feature/your-feature-name
   ```

5. 在 GitHub 上创建 Pull Request

### PR 检查清单

- [ ] 代码通过所有测试
- [ ] 代码符合项目规范
- [ ] 添加了必要的测试
- [ ] 更新了 API 文档（如需要）
- [ ] 提交信息符合规范
- [ ] PR 描述清晰完整
- [ ] 关联了相关 Issue

## 测试

运行测试：

```bash
./gradlew test
```

## 许可证

提交代码即表示你同意将代码以 GPL-3.0 许可证发布。

---

感谢你的贡献！🎉
