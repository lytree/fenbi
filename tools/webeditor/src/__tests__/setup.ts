/**
 * 测试环境补丁。
 *
 * jsdom 不带 Canvas 2D，而 markmap 要靠它量文字宽度（`getContext('2d')` 拿不到就直接抛
 * 「Canvas 2D is not available」）。预览里的导图会在挂载时就建图，所以这里必须补上——
 * 只按字数估个宽度就够，测试不关心真实排版。
 */
type Fake2D = { font: string; measureText: (text: string) => { width: number } }

if (typeof HTMLCanvasElement !== 'undefined') {
  HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement): Fake2D {
    return {
      font: '',
      measureText: (text: string) => ({ width: text.length * 7 }),
    }
  } as unknown as typeof HTMLCanvasElement.prototype.getContext
}
