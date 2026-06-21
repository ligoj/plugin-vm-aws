/*
 * Service layer for plugin "vm-aws".
 *
 * Tool-level plugin (lives at `service:vm:aws`). It augments the parent
 * `plugin-vm` subscription rows. The host's `PluginFeatures` resolves a
 * row to the *service*-level plugin (segment 2 of the node id, i.e.
 * `vm`), then the parent's renderer delegates tool-specific VNodes to us
 * via `subPluginIdFor(...)` (see REWRITE_VUEJS.md "Parent-to-child
 * delegation").
 *
 * `renderFeatures` mirrors the legacy AMD `aws.js#renderFeatures`: two
 * external links built purely from subscription parameters —
 *   - an AWS account sign-in page (when the account is known), and
 *   - the EC2 instance console (when region + instance id are known).
 * Both use the host's shared `renderServiceLink` (tooltip promoted
 * implicitly by the host).
 *
 * The legacy `renderDetailsKey` / `renderDetailsFeatures` carousels read
 * live `subscription.data.vm` (AZ, VPC, CPU/RAM, networks) — that data
 * contract belongs to the not-yet-migrated parent `plugin-vm`, so those
 * hooks are intentionally deferred until the parent is ported.
 *
 * Kept free of Vue SFC imports so it can be unit-tested without a DOM.
 */
import { renderServiceLink, useI18nStore } from '@ligoj/host'

const PARAM_ACCOUNT = 'service:vm:aws:account'
const PARAM_REGION = 'service:vm:aws:region'
const PARAM_ID = 'service:vm:aws:id'

/**
 * AWS console shortcuts for a subscription row. Mirrors the legacy
 * `renderFeatures`: the account sign-in page and the EC2 instance view,
 * each shown only when its required parameters are present.
 */
function renderFeatures(subscription) {
  const params = subscription?.parameters
  if (!params) return []
  const { t } = useI18nStore()
  const buttons = []

  const account = params[PARAM_ACCOUNT]
  if (account) {
    buttons.push(renderServiceLink({
      icon: 'mdi-login',
      href: `https://${account}.signin.aws.amazon.com/console`,
      title: t('service:vm:aws:signin'),
    }))
  }

  const region = params[PARAM_REGION]
  const id = params[PARAM_ID]
  if (region && id) {
    buttons.push(renderServiceLink({
      icon: 'mdi-monitor',
      href: `https://${region}.console.aws.amazon.com/ec2/v2/home?region=${region}#Instances:search=${id}`,
      title: t('service:vm:aws:console'),
    }))
  }

  return buttons
}

export default { renderFeatures }
