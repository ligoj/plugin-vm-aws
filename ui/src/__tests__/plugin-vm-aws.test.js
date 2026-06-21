/*
 * Contract tests for plugin-vm-aws.
 *
 * Asserts the tool-level plugin manifest stays valid, that install()
 * merges the AWS parameter labels, and that the legacy row feature
 * (account sign-in + EC2 console links) still renders VNodes.
 *
 * The parent `plugin-vm` is not migrated to Vue yet, so — unlike
 * plugin-id-ldap — there is no parent index.js to import for an
 * end-to-end delegation test. When plugin-vm gains a `ui/`, add a
 * delegation test mirroring plugin-id-ldap.test.js.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useI18nStore } from '@ligoj/host'
import pluginVmAwsDef from '../index.js'

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('plugin-vm-aws manifest', () => {
  it('exposes a valid tool-level manifest', () => {
    expect(pluginVmAwsDef.id).toBe('vm-aws')
    expect(typeof pluginVmAwsDef.label).toBe('string')
    expect(pluginVmAwsDef.requires).toEqual(['vm'])
    expect(pluginVmAwsDef.routes).toBeUndefined()
    expect(typeof pluginVmAwsDef.install).toBe('function')
    expect(typeof pluginVmAwsDef.feature).toBe('function')
    expect(pluginVmAwsDef.service).toBeTypeOf('object')
    expect(pluginVmAwsDef.meta).toMatchObject({ icon: expect.any(String), color: expect.any(String) })
  })

  it('merges i18n on install', () => {
    const i18n = useI18nStore()
    pluginVmAwsDef.install()
    expect(i18n.t('service:vm:aws:account')).toBe('Account')
    expect(i18n.t('service:vm:aws:console')).toBe('AWS Console')
  })

  it('throws for an unknown feature', () => {
    expect(() => pluginVmAwsDef.feature('nope')).toThrow(/no feature "nope"/)
  })

  it('renderFeatures renders the account sign-in link when the account is set', () => {
    pluginVmAwsDef.install()
    const vnodes = pluginVmAwsDef.feature('renderFeatures', {
      node: { id: 'service:vm:aws:instance' },
      parameters: { 'service:vm:aws:account': '012345678901' },
    })
    expect(vnodes).toHaveLength(1)
    expect(vnodes[0].__v_isVNode).toBe(true)
    expect(vnodes[0].props.href).toBe('https://012345678901.signin.aws.amazon.com/console')
    expect(vnodes[0].props.target).toBe('_blank')
  })

  it('renderFeatures renders the EC2 console link when region + id are set', () => {
    pluginVmAwsDef.install()
    const vnodes = pluginVmAwsDef.feature('renderFeatures', {
      node: { id: 'service:vm:aws:instance' },
      parameters: { 'service:vm:aws:region': 'eu-west-1', 'service:vm:aws:id': 'i-0123456789abcdef0' },
    })
    expect(vnodes).toHaveLength(1)
    expect(vnodes[0].props.href).toContain('eu-west-1.console.aws.amazon.com/ec2/v2/home')
    expect(vnodes[0].props.href).toContain('search=i-0123456789abcdef0')
  })

  it('renderFeatures renders both links when all parameters are set', () => {
    pluginVmAwsDef.install()
    const vnodes = pluginVmAwsDef.feature('renderFeatures', {
      node: { id: 'service:vm:aws:instance' },
      parameters: {
        'service:vm:aws:account': '012345678901',
        'service:vm:aws:region': 'eu-west-1',
        'service:vm:aws:id': 'i-0123456789abcdef0',
      },
    })
    expect(vnodes).toHaveLength(2)
  })

  it('renderFeatures returns an empty list without parameters', () => {
    pluginVmAwsDef.install()
    expect(pluginVmAwsDef.feature('renderFeatures', { node: { id: 'service:vm:aws:x' }, parameters: {} })).toEqual([])
    expect(pluginVmAwsDef.feature('renderFeatures', {})).toEqual([])
  })
})
