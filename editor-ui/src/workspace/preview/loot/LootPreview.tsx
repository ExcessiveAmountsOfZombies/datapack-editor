import {useEffect, useMemo, useState} from "preact/hooks";
import {renderItemIcons} from "../modelRenderer.ts";
import {chestLootError, type ChestSimulation} from "./lootTable.ts";

interface LootPreviewProps {
    content?: string;
    simulationRevision: number;
    simulateLoot: (content: string) => Promise<ChestSimulation>;
}


const chestSize = 27;

export function LootPreview({content, simulationRevision, simulateLoot}: LootPreviewProps) {
    const [reroll, setReroll] = useState(0);
    const [simulation, setSimulation] = useState<ChestSimulation>();
    const [loading, setLoading] = useState(false);
    const [simulationError, setSimulationError] = useState<string>();
    const [icons, setIcons] = useState<{seed: string; values: Record<number, string>}>();
    const [assetError, setAssetError] = useState<string>();
    const contentError = useMemo(() => content !== undefined ? chestLootError(content) : undefined, [content]);

    useEffect(() => {
        setSimulation(undefined);
        setSimulationError(undefined);
        if (content === undefined || contentError) {
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
    }, [content, contentError, simulationRevision, reroll, simulateLoot]);

    useEffect(() => {
        setIcons(undefined);
        setAssetError(undefined);
        if (!simulation) return;
        let cancelled = false;
        void renderItemIcons(simulation.slots, (index, icon) => {
            if (cancelled) return;
            setIcons(current => ({seed: simulation.seed,
                values: {...(current?.seed === simulation.seed ? current.values : {}), [index]: icon}}));
        }).then(result => {
            if (cancelled) return;
            if (result.failed.length) setAssetError(`Could not render ${result.failed.length} item models.`);
        }).catch(reason => {
            if (!cancelled) setAssetError(reason instanceof Error ? reason.message : String(reason));
        });
        return () => { cancelled = true; };
    }, [simulation]);

    const filled = simulation?.slots.filter(stack => stack !== null).length ?? 0;

    return (
        <div class="text-xs text-zinc-300">
                {contentError && <p class="text-amber-300">{contentError}</p>}
                {!contentError && <>
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
        </div>
    );
}
