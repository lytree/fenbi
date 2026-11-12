import { defineComponent } from 'vue'
import { ui } from '../store'

export default defineComponent({
  name: 'Toast',
  setup() {
    return () => (
      <div class={['toast', ui.toastKind, !ui.toastShow && 'hidden']}>
        {ui.toastText}
      </div>
    )
  },
})
