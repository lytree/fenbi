import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SCALE_LEVELS, initScale, resetScale, scalePercent, uiScale, zoomIn, zoomOut } from '../scale'

describe('界面缩放', () => {
  beforeEach(() => {
    localStorage.clear()
    resetScale()
  })

  it('默认 100%，放大逐档走、到顶不动', () => {
    expect(uiScale.level).toBe(1)
    zoomIn()
    expect(uiScale.level).toBe(SCALE_LEVELS[SCALE_LEVELS.indexOf(1) + 1])
    zoomIn(); zoomIn(); zoomIn()
    expect(uiScale.level).toBe(SCALE_LEVELS[SCALE_LEVELS.length - 1])
    zoomIn()
    expect(uiScale.level).toBe(SCALE_LEVELS[SCALE_LEVELS.length - 1])
  })

  it('缩小逐档走、到底不动；文案按四舍五入给百分比', () => {
    zoomOut()
    expect(uiScale.level).toBe(SCALE_LEVELS[0])
    zoomOut()
    expect(uiScale.level).toBe(SCALE_LEVELS[0])
    zoomIn()
    expect(scalePercent()).toBe('100%')
    zoomIn()
    expect(scalePercent()).toBe('115%')
  })

  it('档位写进 localStorage，initScale 能恢复', () => {
    zoomIn()
    expect(localStorage.getItem('fenbi.lan-editor.ui-scale')).toBe('1.15')
    uiScale.level = 1 // 模拟重开页面：状态归零再看恢复
    initScale()
    expect(uiScale.level).toBe(1.15)
  })

  it('界外值当没调过；100% 时摘掉 zoom、非 100% 才设', () => {
    localStorage.setItem('fenbi.lan-editor.ui-scale', '9')
    initScale()
    expect(uiScale.level).toBe(1)
    // jsdom 不认 zoom 这个属性，直接看 setProperty 的调用记录
    const calls: Array<[string, string]> = []
    const spy = vi.spyOn(document.documentElement.style, 'setProperty')
      .mockImplementation(((k: string, v: string) => { calls.push([k, v ?? '']) }) as never)
    resetScale()
    zoomIn()
    spy.mockRestore()
    expect(calls.filter(([k]) => k === 'zoom')).toEqual([['zoom', ''], ['zoom', '1.15']])
  })
})
