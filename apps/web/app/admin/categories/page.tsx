"use client"

import { useState, useEffect, useRef, type PointerEvent } from "react"
import { Plus, Edit, Trash2, FolderTree, GripVertical, X, AlertCircle } from "lucide-react"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import { adminCategoryApi } from "@/services/api"
import { useLocale } from "@/lib/context"
import { Modal } from "@/components/ui/modal"
import type { Category } from "@/types"

export default function AdminCategoriesPage() {
  const { t } = useLocale()
  const [categories, setCategories] = useState<Category[]>([])
  const [savedCategories, setSavedCategories] = useState<Category[]>([])
  const [loadError, setLoadError] = useState("")
  const [reordering, setReordering] = useState(false)
  const [dragId, setDragId] = useState<string | null>(null)
  const [dropId, setDropId] = useState<string | null>(null)
  const dragRef = useRef<{ id: string; x: number; y: number; target: string | null; moved: boolean } | null>(null)
  const dirty = categories.map(c => c.id).join() !== savedCategories.map(c => c.id).join()
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [editId, setEditId] = useState<string | null>(null)
  const [showDeleteConfirm, setShowDeleteConfirm] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [formData, setFormData] = useState({ name: "", sort_order: "" })
  const [formErrors, setFormErrors] = useState<Record<string, boolean>>({})
  const nameRef = useRef<HTMLInputElement>(null)

  const fetchCategories = async () => {
    try {
      setLoadError("")
      const data = await adminCategoryApi.getList()
      setCategories(data)
      setSavedCategories(data)
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : "分类加载失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchCategories() }, [])

  const moveCategory = (id: string, target: string) => {
    setCategories(previous => {
      const from = previous.findIndex(c => c.id === id)
      const to = previous.findIndex(c => c.id === target)
      if (from < 0 || to < 0 || from === to) return previous
      const next = [...previous]
      next.splice(to, 0, next.splice(from, 1)[0])
      return next.map((category, index) => ({ ...category, sort_order: index }))
    })
  }

  const startDrag = (event: PointerEvent<HTMLButtonElement>, id: string) => {
    if (reordering || event.button !== 0) return
    event.currentTarget.setPointerCapture(event.pointerId)
    dragRef.current = { id, x: event.clientX, y: event.clientY, target: null, moved: false }
  }
  const updateDrag = (event: PointerEvent<HTMLButtonElement>) => {
    const drag = dragRef.current
    if (!drag) return
    if (Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 5 && !drag.moved) return
    drag.moved = true
    setDragId(drag.id)
    const target = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>("[data-category-id]")?.dataset.categoryId ?? null
    drag.target = target
    setDropId(target)
  }
  const finishDrag = (cancelled = false) => {
    const drag = dragRef.current
    if (!cancelled && drag?.moved && drag.target) moveCategory(drag.id, drag.target)
    dragRef.current = null
    setDragId(null)
    setDropId(null)
  }
  const saveOrder = async () => {
    setReordering(true)
    try {
      await adminCategoryApi.reorder(categories.map(c => c.id))
      setSavedCategories(categories)
      toast.success("分类排序已保存")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "排序保存失败，请重试")
    } finally { setReordering(false) }
  }

  const handleEdit = (category: Category) => {
    setEditId(category.id)
    setFormData({ name: category.name, sort_order: String(category.sort_order) })
    setShowModal(true)
  }

  const handleSave = async () => {
    if (!formData.name.trim()) {
      setFormErrors({ name: true })
      toast.error("请输入分类名称")
      nameRef.current?.focus()
      nameRef.current?.scrollIntoView({ behavior: "smooth", block: "center" })
      return
    }
    setFormErrors({})
    setSaving(true)
    try {
      if (editId) {
        await adminCategoryApi.update(editId, {
          name: formData.name,
          sort_order: formData.sort_order === "" ? undefined : Number(formData.sort_order),
        })
      } else {
        await adminCategoryApi.create({
          name: formData.name,
          sort_order: formData.sort_order === "" ? undefined : Number(formData.sort_order),
        })
      }
      toast.success("保存成功")
      handleCloseModal()
      await fetchCategories()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: string) => {
    try {
      await adminCategoryApi.delete(id)
      toast.success("删除成功")
      setShowDeleteConfirm(null)
      await fetchCategories()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "删除失败")
    }
  }

  const handleCloseModal = () => {
    setShowModal(false)
    setEditId(null)
    setFormData({ name: "", sort_order: "" })
    setFormErrors({})
  }

  if (loading) {
    return (
      <div className="flex flex-col gap-6">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t("admin.categories")}</h1>
          <p className="text-sm text-muted-foreground">{t("admin.manageCategories")}</p>
        </div>
        <div className="flex items-center justify-center py-24">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t("admin.categories")}</h1>
          <p className="text-sm text-muted-foreground">{t("admin.manageCategories")}</p>
        </div>
        <button
          type="button"
          className="flex items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground shadow-sm hover:bg-primary/90 transition-colors"
          disabled={dirty || reordering}
          onClick={() => {
            setEditId(null)
            setFormData({ name: "", sort_order: "" })
            setShowModal(true)
          }}
        >
          <Plus className="h-4 w-4" />
          {t("admin.addCategory")}
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-3 text-sm">
        <p className="text-muted-foreground">拖动分类左侧手柄调整顺序，也可聚焦手柄后使用方向键。</p>
        <span role="status" aria-live="polite" className="text-primary">{reordering ? "正在保存…" : dirty ? "排序已调整，尚未保存" : ""}</span>
        {dirty && <div className="ml-auto flex gap-2">
          <button disabled={reordering} onClick={() => setCategories(savedCategories)} className="rounded-lg border border-input px-3 py-2 disabled:opacity-50">取消</button>
          <button disabled={reordering} onClick={saveOrder} className="rounded-lg bg-primary px-3 py-2 text-primary-foreground disabled:opacity-50">保存排序</button>
        </div>}
      </div>
      {loadError && <div role="alert" className="text-sm text-destructive">{loadError} <button className="underline" onClick={fetchCategories}>重试</button></div>}

      {/* Category Cards */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {categories.map((cat) => (
          <div
            key={cat.id}
            data-category-id={cat.id}
            className={cn("group flex items-center gap-4 rounded-xl border border-border bg-card p-4 shadow-sm transition-colors", dragId === cat.id && "opacity-50", dropId === cat.id && dragId !== cat.id && "border-primary ring-2 ring-primary/30") }
          >
            <button
              type="button"
              aria-label={`调整 ${cat.name} 的顺序`}
              title="拖动排序，或使用方向键调整"
              disabled={reordering}
              className="touch-none select-none rounded-md p-1.5 text-muted-foreground/60 hover:bg-accent cursor-grab active:cursor-grabbing focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-wait"
              onPointerDown={event => startDrag(event, cat.id)}
              onPointerMove={updateDrag}
              onPointerUp={() => finishDrag()}
              onPointerCancel={() => finishDrag(true)}
              onLostPointerCapture={() => finishDrag(true)}
              onKeyDown={event => {
                if (event.key === "Escape") { finishDrag(true); return }
                const delta = ["ArrowLeft", "ArrowUp"].includes(event.key) ? -1 : ["ArrowRight", "ArrowDown"].includes(event.key) ? 1 : 0
                if (!delta) return
                event.preventDefault()
                const index = categories.findIndex(c => c.id === cat.id)
                const target = categories[index + delta]
                if (target) moveCategory(cat.id, target.id)
              }}
            >
              <GripVertical className="h-5 w-5" />
            </button>
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10">
              <FolderTree className="h-5 w-5 text-primary" />
            </div>
            <div className="flex-1 min-w-0">
              <h3 className="font-medium text-foreground">{cat.name}</h3>
              <p className="text-xs text-muted-foreground">{t("admin.sortOrderLabel") + ":"} {cat.sort_order}</p>
            </div>
            <div className="flex items-center gap-1 opacity-100 sm:opacity-0 sm:group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
              <button
                type="button"
                className="rounded-md p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
                onClick={() => handleEdit(cat)}
                disabled={dirty || reordering}
                title="编辑"
              >
                <Edit className="h-4 w-4" />
              </button>
              <button
                type="button"
                className="rounded-md p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors"
                onClick={() => setShowDeleteConfirm(cat.id)}
                disabled={dirty || reordering}
                title="删除"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* Add/Edit Category Modal */}
      <Modal open={showModal} onClose={handleCloseModal} className="max-w-md">
            <div className="border-b border-border px-6 py-4 flex items-center justify-between">
              <h2 className="text-lg font-semibold text-foreground">
                {editId ? t("admin.editCategory") : t("admin.addCategory")}
              </h2>
              <button
                type="button"
                onClick={handleCloseModal}
                className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>
            <div className="flex flex-col gap-4 p-6">
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">{t("admin.categoryNameReq")}</label>
                <input
                  ref={nameRef}
                  type="text"
                  className={cn("h-10 rounded-lg border bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2", formErrors.name ? "border-destructive ring-destructive/20" : "border-input focus:ring-ring")}
                  placeholder={t("admin.categoryNamePlaceholder")}
                  value={formData.name}
                  onChange={(e) => { setFormData({ ...formData, name: e.target.value }); setFormErrors(prev => ({ ...prev, name: false })) }}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">{t("admin.sortOrderLabel")}</label>
                <input
                  type="number"
                  className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder={t("admin.sortOrderHint")}
                  value={formData.sort_order}
                  onChange={(e) => setFormData({ ...formData, sort_order: e.target.value })}
                />
              </div>
            </div>
            <div className="flex justify-end gap-3 border-t border-border px-6 py-4">
              <button
                type="button"
                className="rounded-lg border border-input bg-transparent px-4 py-2 text-sm font-medium text-foreground hover:bg-accent transition-colors"
                onClick={handleCloseModal}
              >
                {t("admin.cancel")}
              </button>
              <button
                type="button"
                className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
                onClick={handleSave}
                disabled={saving}
              >
                {saving ? t("admin.saving") : t("admin.save")}
              </button>
            </div>
      </Modal>

      {/* Delete Confirmation Modal */}
      <Modal open={showDeleteConfirm !== null} onClose={() => setShowDeleteConfirm(null)} className="max-w-md">
            <div className="flex flex-col gap-4 p-6">
              <div className="flex items-start gap-3">
                <div className="rounded-full bg-destructive/10 p-2">
                  <AlertCircle className="h-5 w-5 text-destructive" />
                </div>
                <div className="flex-1">
                  <h3 className="text-base font-semibold text-foreground">{t("admin.deleteConfirm")}</h3>
                  <p className="mt-1 text-sm text-muted-foreground">
                    {t("admin.deleteCategoryMsg")}
                  </p>
                </div>
              </div>
              <div className="flex justify-end gap-3">
                <button
                  type="button"
                  className="rounded-lg border border-input bg-transparent px-4 py-2 text-sm font-medium text-foreground hover:bg-accent transition-colors"
                  onClick={() => setShowDeleteConfirm(null)}
                >
                  {t("admin.cancel")}
                </button>
                <button
                  type="button"
                  className="rounded-lg bg-destructive px-4 py-2 text-sm font-medium text-destructive-foreground hover:bg-destructive/90 transition-colors"
                  onClick={() => showDeleteConfirm && handleDelete(showDeleteConfirm)}
                >
                  {t("admin.delete")}
                </button>
              </div>
            </div>
      </Modal>
    </div>
  )
}
