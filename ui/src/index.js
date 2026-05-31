/*
 * Plugin "vm-aws" — AWS EC2 implementation of plugin-vm.
 *
 * Tool-level plugin: lives at `service:vm:aws` in the node tree. It does
 * not own routes or a top-level component (the legacy `aws.html` was an
 * empty `<div>`). It augments the parent `plugin-vm` via:
 *
 *   - i18n: AWS-specific parameter labels (account, access/secret keys,
 *     region, instance id) so the subscribe wizard's auto-rendered
 *     parameter form shows friendly names.
 *   - feature('renderFeatures', subscription): the AWS account sign-in
 *     and EC2 instance console links.
 *
 * The parent `plugin-vm` merges these into its subscription-row output
 * through its `subPluginIdFor(...)` delegation hook once it is migrated.
 *
 * Authored as source — compiled to `/main/vm-aws/vue/index.js` by Vite.
 * Shared host surface (stores, components) is imported from `@ligoj/host`
 * and kept external at build so plugin and host share the same instances.
 */
import { useI18nStore } from '@ligoj/host'
import enMessages from './i18n/en.js'
import frMessages from './i18n/fr.js'
import service from './service.js'

const features = {
  renderFeatures: service.renderFeatures,
}

export default {
  id: 'vm-aws',
  label: 'VM AWS EC2',
  // Declared dependency on the parent service-level plugin: it provides
  // the subscription-row chrome and the delegation hook that pulls our
  // VNodes in. The loader awaits requires before calling our install(),
  // so the parent's i18n is in the store before our labels render.
  requires: ['vm'],
  // No routes / component — AWS screens and the parameter form come from
  // the parent's wizard.
  install() {
    const i18n = useI18nStore()
    i18n.merge(enMessages, 'en')
    i18n.merge(frMessages, 'fr')
  },
  feature(action, ...args) {
    const fn = features[action]
    if (!fn) throw new Error(`Plugin "vm-aws" has no feature "${action}"`)
    return fn(...args)
  },
  service,
  meta: { icon: 'mdi-aws', color: 'orange-darken-3' },
}

export { service }
