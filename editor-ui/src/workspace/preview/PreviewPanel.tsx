import type {ComponentChildren} from "preact";
import {useRef, useState} from "preact/hooks";

interface PreviewPanelProps {
    children: ComponentChildren;
}

const openKey = "epherolib.editor.visualizerOpen";
const widthKey = "epherolib.editor.visualizerWidth";
const defaultWidth = 380;
const minWidth = 280;
const maxWidth = 900;

function readOpen() {
    try {
        return window.localStorage.getItem(openKey) !== "false";
    } catch {
        return true;
    }
}

function clampWidth(width: number) {
    const viewportMaximum = typeof window === "undefined" ? maxWidth : Math.max(minWidth, window.innerWidth - 400);
    return Math.round(Math.max(minWidth, Math.min(maxWidth, viewportMaximum, width)));
}

function readWidth() {
    try {
        const stored = Number(window.localStorage.getItem(widthKey));
        return clampWidth(Number.isFinite(stored) && stored > 0 ? stored : defaultWidth);
    } catch {
        return defaultWidth;
    }
}

export function PreviewPanel({children}: PreviewPanelProps) {
    const [open, setOpen] = useState(readOpen);
    const [width, setWidth] = useState(readWidth);
    const [resizing, setResizing] = useState(false);
    const resize = useRef<{pointerId: number; startX: number; startWidth: number}>();

    const toggle = () => {
        const next = !open;
        setOpen(next);
        try { window.localStorage.setItem(openKey, String(next)); } catch {}
    };
    const setAndStoreWidth = (nextWidth: number) => {
        const next = clampWidth(nextWidth);
        setWidth(next);
        try { window.localStorage.setItem(widthKey, String(next)); } catch {}
    };
    const finishResize = (event: PointerEvent) => {
        const current = resize.current;
        if (!current || current.pointerId !== event.pointerId) return;
        resize.current = undefined;
        setResizing(false);
        setAndStoreWidth(current.startWidth + current.startX - event.clientX);
    };

    return <aside class={`relative flex min-h-0 shrink-0 flex-col border-l border-black/50 bg-[#181818] text-zinc-200 ${resizing ? "sidebar-resizing" : ""}`}
                  style={{width: open ? `min(${width}px, max(${minWidth}px, calc(100vw - 400px)))` : "40px"}}
                  aria-label="Visualize Datapack File">
        {open && <div role="separator" aria-label="Resize visualizer" aria-orientation="vertical"
                      aria-valuemin={minWidth} aria-valuemax={maxWidth} aria-valuenow={width}
                      tabIndex={0} title="Drag to resize visualizer double-click to reset"
                      class="group absolute -left-1 top-0 z-20 h-full w-2 cursor-col-resize touch-none outline-none"
                      onPointerDown={event => {
                          event.preventDefault();
                          resize.current = {pointerId: event.pointerId, startX: event.clientX, startWidth: width};
                          event.currentTarget.setPointerCapture(event.pointerId);
                          setResizing(true);
                      }}
                      onPointerMove={event => {
                          const current = resize.current;
                          if (current?.pointerId === event.pointerId) {
                              setWidth(clampWidth(current.startWidth + current.startX - event.clientX));
                          }
                      }}
                      onPointerUp={finishResize} onPointerCancel={finishResize}
                      onDblClick={() => setAndStoreWidth(defaultWidth)}
                      onKeyDown={event => {
                          const step = event.shiftKey ? 64 : 16;
                          if (event.key === "ArrowLeft") setAndStoreWidth(width + step);
                          else if (event.key === "ArrowRight") setAndStoreWidth(width - step);
                          else if (event.key === "Home") setAndStoreWidth(minWidth);
                          else if (event.key === "End") setAndStoreWidth(maxWidth);
                          else return;
                          event.preventDefault();
                      }}>
            <span class="mx-auto block h-full w-px bg-transparent group-hover:bg-violet-400 group-focus:bg-violet-400"/>
        </div>}
        <div class="flex h-9 shrink-0 items-center border-b border-black/50">
            <button type="button" aria-label={open ? "Collapse visualizer" : "Expand visualizer"}
                    aria-expanded={open} title={open ? "Collapse visualizer" : "Expand visualizer"}
                    class="flex h-9 w-10 shrink-0 items-center justify-center text-zinc-400 hover:bg-white/5 hover:text-white"
                    onClick={toggle}>{open ? ">" : "<"}</button>
            {open && <span class="text-xs font-semibold uppercase tracking-wide text-zinc-400">Visualizer</span>}
        </div>
        {open && <div class="min-h-0 flex-1 overflow-y-auto px-4 py-5 [container-type:inline-size]">{children}</div>}
    </aside>;
}
