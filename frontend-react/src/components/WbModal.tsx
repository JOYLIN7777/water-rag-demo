/** 弹框宽度分档：对齐旧版 modal-box 的 460 / 560(md) / 680(wide) / 820(分片预览) */
import { Modal } from 'antd'
import type { ModalProps } from 'antd'

/** 旧版四档宽度（px） */
export const MODAL_W = { sm: 460, md: 560, wide: 680, chunk: 820 } as const
export type ModalSize = keyof typeof MODAL_W

type Props = ModalProps & { size?: ModalSize }

/**
 * 后台/问答统一弹框。
 *
 * <p>宽度通过 CSS 变量 --wb-modal-w 传给 .ant-modal（adapt.css 里用 !important 消费），
 * 避免依赖 AntD 内联 width 与自定义样式的加载顺序。</p>
 */
export default function WbModal({ size = 'sm', style, cancelText, cancelButtonProps, ...rest }: Props) {
  // 旧版 cancelText:'' 表示「不显示取消按钮」（只有关闭/确定）；AntD 的 cancelText=null 不一定生效，
  // 这里显式把按钮隐藏掉，避免出现「取消 + 关闭」两个按钮并排。
  const hideCancel = cancelText === null
  return (
    <Modal
      {...rest}
      cancelText={cancelText as string | undefined}
      cancelButtonProps={hideCancel
        ? { ...(cancelButtonProps || {}), style: { display: 'none' } }
        : cancelButtonProps}
      style={{ ['--wb-modal-w' as string]: `min(${MODAL_W[size]}px, 92vw)`, ...(style as object) }}
    />
  )
}
