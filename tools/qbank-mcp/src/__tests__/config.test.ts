import test from 'node:test'
import assert from 'node:assert/strict'
import { ConfigError, configFromEnv, parseQbankUrl } from '../config.js'

test('把 App 里的完整地址拆成 baseUrl + token', () => {
  const cfg = parseQbankUrl('http://192.168.1.23:8777/?t=k3m9x2q7ab4c')
  assert.equal(cfg.baseUrl, 'http://192.168.1.23:8777')
  assert.equal(cfg.token, 'k3m9x2q7ab4c')
})

test('地址没带口令时给出可操作的提示', () => {
  assert.throws(
    () => parseQbankUrl('http://192.168.1.23:8777/'),
    (e: unknown) => e instanceof ConfigError && /口令/.test((e as Error).message)
  )
})

test('--url 比环境变量优先', () => {
  const cfg = configFromEnv(
    { QBANK_URL: 'http://10.0.0.1:8777/?t=from-env' },
    ['--url', 'http://10.0.0.2:8777/?t=from-flag']
  )
  assert.equal(cfg.baseUrl, 'http://10.0.0.2:8777')
  assert.equal(cfg.token, 'from-flag')
})

test('QBANK_HOST + QBANK_TOKEN 也能拼出配置（补 http://）', () => {
  const cfg = configFromEnv({ QBANK_HOST: '192.168.1.9:8777', QBANK_TOKEN: 'abc' }, [])
  assert.equal(cfg.baseUrl, 'http://192.168.1.9:8777')
  assert.equal(cfg.token, 'abc')
})

test('什么都没配时报错而不是静默连本机', () => {
  assert.throws(() => configFromEnv({}, []), /QBANK_URL/)
})
