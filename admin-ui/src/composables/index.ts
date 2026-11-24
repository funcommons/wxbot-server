// 宿主 composables：SDK 组件依赖（FcHeader/FcSidebar/FcDialog/FcImagePicker）
import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'

export interface Responsive {
  /** < 768px */
  isMobile: Ref<boolean>
  /** 768px <= w < 1024px */
  isTablet: Ref<boolean>
  /** >= 1024px */
  isDesktop: Ref<boolean>
  width: Ref<number>
}

const width = ref(typeof window === 'undefined' ? 1024 : window.innerWidth)

if (typeof window !== 'undefined') {
  window.addEventListener('resize', () => { width.value = window.innerWidth }, { passive: true })
}

export function useResponsive(): Responsive {
  const isMobile = computed(() => width.value < 768)
  const isTablet = computed(() => width.value >= 768 && width.value < 1024)
  const isDesktop = computed(() => width.value >= 1024)
  return { isMobile, isTablet, isDesktop, width }
}

/** 事件绑定随作用域自动卸载（handler 支持具体事件类型，如 ClipboardEvent） */
export function useEventListener<T extends Event>(
  target: EventTarget,
  type: string,
  handler: (e: T) => void,
  options?: AddEventListenerOptions,
) {
  const listener = handler as EventListener
  target.addEventListener(type, listener, options)
  onScopeDispose(() => target.removeEventListener(type, listener))
}

/** 弹层打开时锁 body 滚动，关闭时恢复（计数式，兼容多层弹窗叠加） */
export function useBodyScrollLock(visible: Ref<boolean>) {
  let count = 0
  const prev: string[] = []

  watch(visible, (open) => {
    if (open) {
      prev.push(document.body.style.overflow)
      if (count === 0) document.body.style.overflow = 'hidden'
      count++
    } else {
      count = Math.max(0, count - 1)
      if (count === 0) document.body.style.overflow = prev.pop() ?? ''
    }
  })

  onScopeDispose(() => {
    count = Math.max(0, count - 1)
    if (count === 0) document.body.style.overflow = prev.pop() ?? ''
  })
}

async function decodeToCanvas(file: Blob): Promise<HTMLCanvasElement> {
  const url = URL.createObjectURL(file)
  try {
    const img = new Image()
    await new Promise<void>((resolve, reject) => {
      img.onload = () => resolve()
      img.onerror = () => reject(new Error('image decode failed'))
      img.src = url
    })
    const canvas = document.createElement('canvas')
    canvas.width = img.naturalWidth
    canvas.height = img.naturalHeight
    canvas.getContext('2d')!.drawImage(img, 0, 0)
    return canvas
  } finally {
    URL.revokeObjectURL(url)
  }
}

function canvasToBlob(canvas: HTMLCanvasElement, mime: string): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('toBlob failed'))), mime)
  })
}

/** 图片文件转目标格式，返回 Blob（SDK 上传管线契约） */
export async function convertImage(file: Blob | File, targetMime = 'image/png'): Promise<Blob> {
  if (file.type === targetMime) return file
  const canvas = await decodeToCanvas(file)
  return canvasToBlob(canvas, targetMime)
}

/** 等比缩到最长边不超 edge（纯降不放大），返回 Blob */
export async function downscaleToMaxEdge(file: Blob | File, edge: number): Promise<Blob> {
  const url = URL.createObjectURL(file)
  let img: HTMLImageElement
  try {
    img = new Image()
    await new Promise<void>((resolve, reject) => {
      img.onload = () => resolve()
      img.onerror = () => reject(new Error('image decode failed'))
      img.src = url
    })
  } finally {
    URL.revokeObjectURL(url)
  }
  const maxEdge = Math.max(img.naturalWidth, img.naturalHeight)
  if (maxEdge <= edge) return file
  const scale = edge / maxEdge
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(img.naturalWidth * scale)
  canvas.height = Math.round(img.naturalHeight * scale)
  canvas.getContext('2d')!.drawImage(img, 0, 0, canvas.width, canvas.height)
  return canvasToBlob(canvas, file.type || 'image/png')
}

export interface RecentImages {
  images: Ref<string[]>
  getAll: () => string[]
  add: (dataUrl: string) => void
  remove: (dataUrl: string) => void
  clear: () => void
}

/** 最近图片记录（localStorage，容量上限裁剪最旧） */
export function useRecentImages(storageKey: string, limit = 12): RecentImages {
  const images = ref<string[]>([])
  try { images.value = JSON.parse(localStorage.getItem(storageKey) || '[]') } catch { images.value = [] }

  function persist() {
    try { localStorage.setItem(storageKey, JSON.stringify(images.value.slice(0, limit))) } catch { /* 配额满静默 */ }
  }
  function add(dataUrl: string) {
    images.value = [dataUrl, ...images.value.filter((i) => i !== dataUrl)].slice(0, limit)
    persist()
  }
  function remove(dataUrl: string) {
    images.value = images.value.filter((i) => i !== dataUrl)
    persist()
  }
  function clear() { images.value = []; persist() }
  function getAll() { return images.value }

  return { images, getAll, add, remove, clear }
}
