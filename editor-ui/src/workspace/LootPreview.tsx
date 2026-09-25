import {useEffect, useMemo, useRef, useState} from "preact/hooks";
import {renderItemIcons} from "./modelRenderer";
import {chestLootError, isChestLootTablePath, type ChestSimulation} from "./lootTable";

interface LootPreviewProps {
    path?: string;
    content?: string;
    token: string;
    simulationRevision: number;
    simulateLoot: (content: string) => Promise<ChestSimulation>;
}

const chestSize = 27;
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

export function LootPreview({path, content, token, simulationRevision, simulateLoot}: LootPreviewProps) {
    const [open, setOpen] = useState(readOpen);
    const [width, setWidth] = useState(readWidth);
    const [resizing, setResizing] = useState(false);
    const resize = useRef<{pointerId: number; startX: number; startWidth: number}>();
    const [reroll, setReroll] = useState(0);
    const [simulation, setSimulation] = useState<ChestSimulation>();
    const [loading, setLoading] = useState(false);
    const [simulationError, setSimulationError] = useState<string>();
    const [icons, setIcons] = useState<{seed: string; values: Record<number, string>}>();
    const [assetError, setAssetError] = useState<string>();
    const eligible = isChestLootTablePath(path);
    const contentError = useMemo(() => eligible && content !== undefined ? chestLootError(content) : undefined,
        [eligible, content]);

    useEffect(() => {
        setSimulation(undefined);
        setSimulationError(undefined);
        if (!open || !eligible || !path || content === undefined || contentError) {
            setLoading(false);
            return;
        }
        let cancelled = false;
        setLoading(true);
        const timeout = window.setTimeout(() => {
            void simulateLoot(content).then(result => {
                if (!cancelled) {
                    setSimulation(result);
                    setLoading(false);
                }
            }).catch(reason => {
                if (!cancelled) {
                    setSimulationError(reason instanceof Error ? reason.message : String(reason));
                    setLoading(false);
                }
            });
        }, 250);
        return () => {
            cancelled = true;
            window.clearTimeout(timeout);
        };
    }, [open, eligible, path, content, contentError, simulationRevision, reroll, simulateLoot]);

    useEffect(() => {
        setIcons(undefined);
        setAssetError(undefined);
        if (!open || !simulation) return;
        let cancelled = false;
        void renderItemIcons(simulation.slots, token).then(result => {
            if (cancelled) return;
            setIcons({seed: simulation.seed, values: result.icons});
            if (result.failed.length) setAssetError(`Could not render ${result.failed.length} item models.`);
        }).catch(reason => {
            if (!cancelled) setAssetError(reason instanceof Error ? reason.message : String(reason));
        });
        return () => { cancelled = true; };
    }, [open, simulation, token]);

    const toggle = () => {
        const next = !open;
        setOpen(next);
        try {
            window.localStorage.setItem(openKey, String(next));
        } catch {}
    };
    const setAndStoreWidth = (nextWidth: number) => {
        const next = clampWidth(nextWidth);
        setWidth(next);
        try {
            window.localStorage.setItem(widthKey, String(next));
        } catch {}
    };
    const finishResize = (event: PointerEvent) => {
        const current = resize.current;
        if (!current || current.pointerId !== event.pointerId) return;
        resize.current = undefined;
        setResizing(false);
        setAndStoreWidth(current.startWidth + current.startX - event.clientX);
    };
    const filled = simulation?.slots.filter(stack => stack !== null).length ?? 0;

    return (
        <aside class={`relative flex min-h-0 shrink-0 flex-col border-l border-black/50 bg-[#181818] text-zinc-200 ${resizing ? "sidebar-resizing" : ""}`}
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
            {open && <div class="min-h-0 overflow-y-auto px-4 py-5 [container-type:inline-size]">
                {!eligible && <p class="text-xs text-zinc-500">Open a chest loot table to simulate its contents.</p>}
                {eligible && contentError && <p class="text-xs text-amber-300">{contentError}</p>}
                {eligible && !contentError && <>
                    <div class="mb-3 flex items-center justify-end text-xs text-zinc-400">
                        {loading && <span class="mr-auto" role="status">Rolling chest…</span>}
                        <button type="button" disabled={loading || content === undefined}
                                class="rounded px-2 py-1 text-zinc-300 hover:bg-white/10 disabled:opacity-40"
                                onClick={() => setReroll(value => value + 1)}>Roll</button>
                    </div>
                    <div class="w-full rounded border-2 border-[#716b5f] bg-[#c6c6c6] p-2 shadow-[inset_2px_2px_#fff,inset_-2px_-2px_#555]"
                         role="grid" aria-label="Simulated chest inventory">
                        <div class="grid w-full grid-cols-9 gap-0.5">
                            {Array.from({length: chestSize}, (_, index) => {
                                const stack = simulation?.slots[index];
                                const icon = icons && simulation && icons.seed === simulation.seed ? icons.values[index] : undefined;
                                return <div key={index} role="gridcell"
                                            title={stack ? `Slot ${index + 1}: ${stack.id} ×${stack.count}` : `Slot ${index + 1}: empty`}
                                            aria-label={stack ? `Slot ${index + 1}: ${stack.count} ${stack.id}` : `Slot ${index + 1}: empty`}
                                            class="relative flex aspect-square min-w-0 items-center justify-center overflow-hidden border border-[#8b8b8b] bg-[#8b8b8b] shadow-[inset_2px_2px_#373737,inset_-2px_-2px_#fff]">
                                    {icon ? <img src={icon} alt="" class="h-[90%] w-[90%] object-contain"/> :
                                        stack ? <span class="max-w-full truncate px-0.5 font-bold text-zinc-900"
                                                      style={{fontSize: "clamp(10px, 3.5cqw, 22px)"}}>?</span> : null}
                                    {stack && stack.count > 1 &&
                                        <span class="pointer-events-none absolute bottom-0 right-0 pr-px font-bold leading-none text-white [text-shadow:1px_1px_#333]"
                                              style={{fontSize: "clamp(10px, 3.5cqw, 22px)"}}>
                                            {stack.count}
                                        </span>}
                                </div>;
                            })}
                        </div>
                    </div>
                    {simulationError && <p class="mt-3 text-xs text-amber-300">{simulationError}</p>}
                    {assetError && <p class="mt-3 text-xs text-amber-300">Item preview unavailable: {assetError}</p>}
                    {simulation && !filled && <p class="mt-3 text-xs text-zinc-500">This roll produced no items.</p>}
                </>}
            </div>}
        </aside>
    );
}
