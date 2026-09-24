import type {ComponentChildren} from "preact";
import {useRef, useState} from "preact/hooks";

export interface SidebarView {
    id: string;
    label: string;
    icon: ComponentChildren;
    content: ComponentChildren;
}

interface SidebarProps {
    views: SidebarView[];
    activeView: string | null;
    onActiveViewChange: (view: string | null) => void;
}

const sidebarWidthKey = "epherolib.editor.sidebarWidth";
const defaultSidebarWidth = 280;
const minSidebarWidth = 180;
const maxSidebarWidth = 640;

export function Sidebar({views, activeView, onActiveViewChange}: SidebarProps) {
    const resize = useRef<{pointerId: number; startX: number; startWidth: number}>();
    const [width, setWidth] = useState(readSidebarWidth);
    const [resizing, setResizing] = useState(false);

    const setAndStoreWidth = (nextWidth: number) => {
        const next = clampSidebarWidth(nextWidth);
        setWidth(next);
        try {
            window.localStorage.setItem(sidebarWidthKey, String(next));
        } catch {
            // Resizing still works when browser storage is unavailable.
        }
    };

    const finishResize = (event: PointerEvent) => {
        const current = resize.current;
        if (!current || current.pointerId !== event.pointerId) return;
        resize.current = undefined;
        setResizing(false);
        setAndStoreWidth(current.startWidth + event.clientX - current.startX);
    };

    return (
        <>
            <nav class="flex w-12 shrink-0 flex-col items-center border-r border-black/40 bg-[#181818] py-2"
                 aria-label="Workspace views">
                {views.map(view => (
                    <button key={view.id}
                            class={`relative flex h-12 w-12 items-center justify-center hover:text-white ${activeView === view.id ? "text-zinc-100" : "text-zinc-500"}`}
                            title={view.label} type="button" aria-label={view.label}
                            aria-expanded={activeView === view.id} aria-controls={`workspace-${view.id}`}
                            onClick={() => onActiveViewChange(activeView === view.id ? null : view.id)}>
                        {activeView === view.id && <span class="absolute left-0 h-9 w-0.5 bg-violet-400"/>}
                        {view.icon}
                    </button>
                ))}
            </nav>
            {views.map(view => (
                <aside key={view.id} id={`workspace-${view.id}`} aria-label={view.label}
                       class={`${activeView === view.id ? "flex" : "hidden"} relative min-h-0 min-w-0 shrink-0 flex-col border-r border-black/50 bg-[#181818] ${resizing ? "sidebar-resizing" : ""}`}
                       style={{width: `min(${width}px, calc(100vw - 112px))`}}>
                    {view.content}
                    <div role="separator" aria-label="Resize sidebar" aria-orientation="vertical"
                         aria-valuemin={minSidebarWidth} aria-valuemax={maxSidebarWidth} aria-valuenow={width}
                         tabIndex={0} title="Drag to resize sidebar · Double-click to reset"
                         class="group absolute -right-1 top-0 z-20 h-full w-2 cursor-col-resize touch-none outline-none"
                         onPointerDown={event => {
                             event.preventDefault();
                             resize.current = {pointerId: event.pointerId, startX: event.clientX, startWidth: width};
                             event.currentTarget.setPointerCapture(event.pointerId);
                             setResizing(true);
                         }}
                         onPointerMove={event => {
                             const current = resize.current;
                             if (current?.pointerId === event.pointerId) {
                                 setWidth(clampSidebarWidth(current.startWidth + event.clientX - current.startX));
                             }
                         }}
                         onPointerUp={finishResize} onPointerCancel={finishResize}
                         onDblClick={() => setAndStoreWidth(defaultSidebarWidth)}
                         onKeyDown={event => {
                             const step = event.shiftKey ? 64 : 16;
                             if (event.key === "ArrowLeft") setAndStoreWidth(width - step);
                             else if (event.key === "ArrowRight") setAndStoreWidth(width + step);
                             else if (event.key === "Home") setAndStoreWidth(minSidebarWidth);
                             else if (event.key === "End") setAndStoreWidth(maxSidebarWidth);
                             else return;
                             event.preventDefault();
                         }}>
                        <span class="mx-auto block h-full w-px bg-transparent group-hover:bg-violet-400 group-focus:bg-violet-400"/>
                    </div>
                </aside>
            ))}
        </>
    );
}

function clampSidebarWidth(width: number) {
    const viewportMaximum = typeof window === "undefined"
        ? maxSidebarWidth
        : Math.max(minSidebarWidth, window.innerWidth - 112);
    return Math.round(Math.max(minSidebarWidth, Math.min(maxSidebarWidth, viewportMaximum, width)));
}

function readSidebarWidth() {
    try {
        const stored = Number(window.localStorage.getItem(sidebarWidthKey));
        return clampSidebarWidth(Number.isFinite(stored) && stored > 0 ? stored : defaultSidebarWidth);
    } catch {
        return defaultSidebarWidth;
    }
}
