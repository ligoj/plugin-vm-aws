# plugin-vm-aws — Vue UI

Vue source for the **vm-aws** tool plugin (`service:vm:aws`), the AWS EC2
implementation of the `vm` service. Compiled by Vite into the Maven
plugin JAR at `../src/main/resources/META-INF/resources/webjars/vm-aws/vue/`,
served by the host at `/main/vm-aws/vue/index.js`.

This is a **tool-level** plugin — see the host's `app-ui/REWRITE_VUEJS.md`
("Tool-level (sub-plugin) variant") for the full contract. It ships:

- **i18n** — AWS parameter labels (`service:vm:aws:*`) used by the
  subscribe wizard's auto-rendered parameter form.
- **`renderFeatures`** — an external AWS Console link button.
- **`renderDetailsFeatures`** — a chip showing the AWS account.

It declares `requires: ['vm']`; the parent `plugin-vm` merges the row
features above into its subscription rows via its delegation hook.
(Parent `plugin-vm` is not migrated to Vue yet — when it is, the
delegation wires up automatically.)

## Commands

```bash
npm install
npm run build   # → ../src/main/resources/.../webjars/vm-aws/vue/
npm run lint
npm test        # vitest — manifest + feature contract tests
npm run dev     # standalone dev harness (rarely useful)
```

For real integration testing, run the host's vite dev server
(`ligoj/app-ui/src/main/webapp`, `npm run dev`) which proxies
`/ligoj/main/vm-aws/vue/*` to the freshly built bundle. The cycle:
edit source → `npm run build` here → host browser auto-reloads.
