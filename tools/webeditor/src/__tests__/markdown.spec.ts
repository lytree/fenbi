import { describe, expect, it } from 'vitest'
import { plain, renderMaterial, renderRecite, renderStem } from '../markdown'

/**
 * 材料预览的渲染口径。
 *
 * 网页预览要和 App 的 MaterialRenderer 对齐：同一段材料（:::figure 图文排版 / 表格 / :::chart）
 * 在两边必须长得一样，所以这里把关键结构钉住。
 */
describe('renderMaterial', () => {
  it('左图右文：出左右两栏，图列宽度跟着 width 走', () => {
    const html = renderMaterial(
      [
        ':::figure',
        'layout: left',
        'width: 42%',
        '![图1](/sdcard/a.png)',
        '这段文字在图的右边',
        ':::',
      ].join('\n'),
    )
    expect(html).toContain('pv-fig pv-fig-left')
    expect(html).toContain('--fwr:42%')
    expect(html.indexOf('pv-fig-imgs')).toBeLessThan(html.indexOf('pv-fig-txt'))
    expect(html).toContain('这段文字在图的右边')
  })

  it('右图左文：DOM 顺序不变，靠 row-reverse 换边', () => {
    const html = renderMaterial(':::figure\nlayout: right\n![图1](/a.png)\n说明\n:::')
    expect(html).toContain('pv-fig pv-fig-right')
    expect(html.indexOf('pv-fig-imgs')).toBeLessThan(html.indexOf('pv-fig-txt'))
  })

  it('多图并排：一张 row 容器装下所有图', () => {
    const html = renderMaterial(
      ':::figure\nlayout: row\n![1](/1.png)\n![2](/2.png)\n![3](/3.png)\n:::',
    )
    expect(html).toContain('pv-fig pv-fig-row')
    expect(html.match(/pv-img/g)?.length).toBe(3)
  })

  it('图在上 / 图在下：文字与图的先后会换', () => {
    const top = renderMaterial(':::figure\nlayout: top\n![图1](/a.png)\n说明\n:::')
    const bottom = renderMaterial(':::figure\nlayout: bottom\n![图1](/a.png)\n说明\n:::')
    expect(top.indexOf('pv-fig-imgs')).toBeLessThan(top.indexOf('pv-fig-txt'))
    expect(bottom.indexOf('pv-fig-txt')).toBeLessThan(bottom.indexOf('pv-fig-imgs'))
  })

  it('单张图行尾的尺寸标记被吃掉，full 才铺满', () => {
    const natural = renderMaterial('![图1](/a.png)')
    expect(natural).toContain('pv-img"')
    expect(natural).not.toContain('pv-img-full')

    const full = renderMaterial('![图1](/a.png){size=full}')
    expect(full).toContain('pv-img-full')
    expect(full).not.toContain('{size=full}')
  })

  it('表格与 :::chart 仍然照旧渲染', () => {
    const table = renderMaterial('| 指标 | 数值 |\n| --- | --- |\n| 总收入 | 5280 |')
    expect(table).toContain('pv-tbl')
    expect(table).toContain('总收入')

    const chart = renderMaterial(':::chart\ntitle: 各月增速\ntype: line\n:::')
    expect(chart).toContain('pv-chart')
    expect(chart).toContain('各月增速')
  })

  it('正文里的冒号不会被当成排版指令', () => {
    const html = renderMaterial(
      ':::figure\nlayout: left\n2019年：全年增速 5.8%\n![图1](/a.png)\n:::',
    )
    expect(html).toContain('2019年：全年增速 5.8%')
    expect(html).toContain('pv-fig-left')
  })

  it('只有文字没有图时，按普通段落渲染（和 App 一致，不撑一个空壳）', () => {
    const html = renderMaterial(':::figure\nlayout: left\n只有文字\n:::')
    expect(html).toContain('只有文字')
    expect(html).not.toMatch(/class="pv-fig[\s"]/)
  })

  it('摘要里图片收成「［图片］」', () => {
    expect(plain('见下图 ![图](/tmp/1.png)', 50)).toBe('见下图 ［图片］')
  })
})

describe('renderStem（题干里的表格）', () => {
  it('题干里的 Markdown 表格排成真表格，不再露出一排竖线', () => {
    const html = renderStem('根据下表回答：\n\n| 年份 | 收入 |\n| --- | --- |\n| 2024 | 5280 |')
    expect(html).toContain('<table class="pv-tbl">')
    expect(html).not.toContain('| 年份')
    expect(html).toContain('<td>5280</td>')
    // 表格前面的那句还得在，且段落挂的是题干的类
    expect(html).toContain('根据下表回答：')
    expect(html).toContain('pv-stem-p')
  })

  it('纯文字题干照旧：段内换行保留、没有表格', () => {
    const html = renderStem('第一行\n第二行')
    expect(html).toContain('第一行<br>第二行')
    expect(html).not.toContain('<table')
  })

  it('题干里的图片与材料同一套写法', () => {
    const html = renderStem('看图作答：\n\n![图1](/a.png)')
    expect(html).toContain('class="pv-img')
  })
})

describe('renderRecite（背诵卡正反面）', () => {
  const text = '我国现行宪法是 ____ 年颁布的，共 ____ 章。'

  it('正面只给编号空位，答案不露出来', () => {
    const front = renderRecite(text, ['1982', '四'], false)
    expect(front.match(/rcp-blank/g)).toHaveLength(2)
    expect(front).not.toContain('1982')
    expect(front).toContain('data-n="1"')
    expect(front).toContain('data-n="2"')
  })

  it('背面把答案填进空位、按出现顺序对应', () => {
    const back = renderRecite(text, ['1982', '四'], true)
    expect(back).toContain('1982')
    expect(back).toContain('四')
    expect(back).not.toContain('data-n="1">1<') // 编号不再显示成占位
  })

  it('答案没填的空位标「未填」，不吞掉正文', () => {
    const back = renderRecite('空缺 ____ 的情况', [], true)
    expect(back).toContain('（未填）')
    expect(back).toContain('空缺')
  })

  it('正文里的表格照排，表格里的空位也算一个', () => {
    const back = renderRecite('| 项目 | 值 |\n| --- | --- |\n| 颁布年 | ____ |', ['1982'], true)
    expect(back).toContain('<table class="pv-tbl">')
    expect(back).toContain('1982')
  })
})
