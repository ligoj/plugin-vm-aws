/*
 * The subscribe wizard's input of `service:vm:aws:id`: the virtual machines of the selected region of the
 * selected instance, searched as the user types (the host LjRemoteSearchField).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import def from '../index.js'

const PARAMETER = { id: 'service:vm:aws:id', type: 'TEXT', mandatory: true }

function jsonResponse(body) {
  return { ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => body }
}
// Expose the bindings of the field; the test types through `update:search`
const inputStub = (name) => ({ name, props: ['items'], emits: ['update:search', 'update:modelValue'], template: '<input />' })
const stubs = { LigojAutocomplete: inputStub('LigojAutocomplete'), LigojCombobox: inputStub('LigojCombobox') }

beforeEach(() => {
  // The search waits for a pause in the typing
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  setActivePinia(createPinia())
  def.install()
  globalThis.fetch = vi.fn(async () => jsonResponse([{ id: 'i-0123456789abcdef0', name: 'web-1' }]))
})
afterEach(() => { vi.useRealTimers() })

describe('service:vm:aws:id parameter field', () => {
  it('is a remote search to subscribe, the default input to edit a node or for another parameter', () => {
    const field = def.feature('parameterField', { parameter: PARAMETER, isNode: false })
    expect(field).toBeTruthy()
    // The same component on each render, else the wizard would remount the input
    expect(def.feature('parameterField', { parameter: PARAMETER, isNode: false })).toBe(field)
    expect(def.feature('parameterField', { parameter: PARAMETER, isNode: true })).toBeNull()
    expect(def.feature('parameterField', { parameter: { id: 'service:vm:aws:account' }, isNode: false })).toBeNull()
  })

  it('searches the virtual machines of the selected region and stores the instance identifier', async () => {
    const Field = def.feature('parameterField', { parameter: PARAMETER, isNode: false })
    const w = mount(Field, { props: { modelValue: '', parameter: PARAMETER, formValues: { 'service:vm:aws:region': 'eu-west-1' }, mode: 'link', isNode: false, instanceNodeId: 'service:vm:aws:local' }, global: { stubs } })
    const input = w.findComponent({ name: 'LigojAutocomplete' })
    input.vm.$emit('update:search', 'web')
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(fetch.mock.calls[0][0]).toBe('rest/service/vm/aws/service%3Avm%3Aaws%3Alocal/web?service%3Avm%3Aaws%3Aregion=eu-west-1')
    expect(input.props('items')).toEqual([{ title: 'web-1', subtitle: 'i-0123456789abcdef0', value: 'i-0123456789abcdef0' }])
  })
})
