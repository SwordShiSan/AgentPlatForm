# Frontend

R0.1 Vue 3 + TypeScript + Vite 应用，当前只显示真实API健康检查，未交付的用户/管理能力不展示假入口。目录按app/features/shared组织，服务端状态由Vue Query持有，Pinia只保存视图状态和草稿。

Node版本见根目录 `.node-version`；pnpm固定10.34.7。在本目录执行：

```sh
pnpm install --frozen-lockfile
pnpm contracts:check
pnpm contracts:test
pnpm typecheck
pnpm test
pnpm build
pnpm dev
```

开发代理指向本机8080；最终验收使用固定dist和Docker API，见 [部署说明](../deploy/README.md)。生成文件在src/shared/api/generated，仅修改contracts再执行 `pnpm contracts:generate`。`pnpm test:e2e` 需要R0.1 Compose已启动及Playwright Chromium；其中恢复场景只停止/启动精确的agentplatform-app API。
