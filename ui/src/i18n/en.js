/*
 * English labels for plugin-vm-aws.
 *
 * Flat keys (the host's vue-i18n uses messageResolver: obj => obj[path],
 * no dot/colon traversal) so `service:vm:aws:*` ids resolve as literal
 * lookups. The keys mirror the parameter ids declared in the plugin's
 * src/main/resources/csv/parameter.csv so the subscribe wizard's
 * auto-rendered parameter form shows friendly labels.
 */
export default {
  'service:vm:aws:account': 'Account',
  'service:vm:aws:access-key-id': 'Access key',
  'service:vm:aws:secret-access-key': 'Secret key',
  'service:vm:aws:region': 'Region',
  'service:vm:aws:id': 'Instance ID',
  'service:vm:aws:signin': 'AWS Sign-in for this account',
  'service:vm:aws:console': 'AWS Console',
}
