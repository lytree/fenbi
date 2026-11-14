import test from 'node:test'
import assert from 'node:assert/strict'
import { ConfigError, configFromEnv, parseQbankUrl } from '../config.js'

test('地址就是 baseUrl，不再从 URL 里取口令', () => {
  const cfg = parseQbankUrl('http://192.168.1.23:8777/')
  assert.equal(cfg.baseUrl, 'http://192.168.1.23:8777')
  assert.equal(cfg.key, '')
})

test('老链接里的 ?t= 继续当 key 用，升级不用改配置', () => {
  const cfg = parseQbankUrl('http://192.168.1.23:8777/?t=k3m9x2q7ab4c')
  assert.equal(cfg.baseUrl, 'http://192.168.1.23:8777')
  assert.equal(cfg.key, 'k3m9x2q7ab4c')
})

test('QBANK_KEY 单独给，不拼进地址', () => {
  const cfg = configFromEnv({ QBANK_URL: 'http://10.0.0.1:8777/', QBANK_KEY: 'from-env' }, [])
  assert.equal(cfg.baseUrl, 'http://10.0.0.1:8777')
  assert.equal(cfg.key, 'from-env')
})

test('--url 比环境变量优先，--key 也能从命令行来', () => {
  const cfg = configFromEnv(
    { QBANK_URL: 'http://10.0.0.1:8777', QBANK_KEY: 'from-env' },
    ['--url', 'http://10.0.0.2:8777', '--key', 'from-flag']
  )
  assert.equal(cfg.baseUrl, 'http://10.0.0.2:8777')
  assert.equal(cfg.key, 'from-flag')
})

test('QBANK_HOST + QBANK_TOKEN 也能拼出配置（补 http://）', () => {
  const cfg = configFromEnv({ QBANK_HOST: '192.168.1.9:8777', QBANK_TOKEN: 'abc' }, [])
  assert.equal(cfg.baseUrl, 'http://192.168.1.9:8777')
  assert.equal(cfg.key, 'abc')
})

test('地址不合法时报错，且不把口令原样吐出来', () => {
  assert.throws(
    () => parseQbankUrl('192.168.1.23:8777/?t=secret-key'),
    (e: unknown) =>
      e instanceof ConfigError && !(e as Error).message.includes('secret-key')
  )
})

test('什么都没配时报错而不是静默连本机', () => {
  assert.throws(() => configFromEnv({}, []), /QBANK_URL/)
})
