import {useEffect, useState} from "preact/hooks";
import {renderItemIcons} from "../modelRenderer.ts";
import type {ChestStack} from "../loot/lootTable.ts";

interface RecipeDisplay {
    title: string;
    slots: Array<{label: string; items: ChestStack[]; error?: string}>;
}

export function ServerRecipePreview({content}: {content: string}) {
    const [preview, setPreview] = useState<{content: string; enabled: boolean; displays?: RecipeDisplay[]}>();
    const [error, setError] = useState<string>();
    const [frame, setFrame] = useState(0);
    const [icons, setIcons] = useState<Record<string, string>>({});
    const current = preview?.content === content ? preview : undefined;

    useEffect(() => {
        setError(undefined);
        const controller = new AbortController();
        const timer = window.setTimeout(() => {
            void fetch("/api/recipe-preview", {
                method: "POST", signal: controller.signal,
                headers: {"Content-Type": "application/json"}, body: content
            }).then(async response => {
                const result = await response.json();
                if (!response.ok) throw new Error(result.error ?? "Recipe display unavailable");
                if (!controller.signal.aborted) setPreview({...result, content});
            }).catch(reason => { if (!controller.signal.aborted) setError(String(reason)); });
        }, 250);
        return () => { controller.abort(); window.clearTimeout(timer); };
    }, [content]);

    useEffect(() => {
        const timer = window.setInterval(() => setFrame(value => value + 1), 1600);
        return () => window.clearInterval(timer);
    }, []);

    const stacks = current?.displays?.flatMap(display => display.slots.flatMap(slot =>
        slot.items.length ? [slot.items[frame % slot.items.length]] : [])) ?? [];
    const stackKey = JSON.stringify(stacks);
    useEffect(() => {
        const missing = stacks.filter(stack => !icons[JSON.stringify(stack)]);
        if (!missing.length) return;
        let cancelled = false;
        void renderItemIcons(missing, (index, icon) => {
            if (!cancelled) setIcons(value => ({...value, [JSON.stringify(missing[index])]: icon}));
        }).catch(reason => { if (!cancelled) setError(String(reason)); });
        return () => { cancelled = true; };
    }, [stackKey]);

    return <div class="space-y-3">
        {error && <p class="text-amber-300">{error}</p>}
        {!error && !current && <p role="status">Loading recipe display…</p>}
        {current && !current.enabled && <p class="text-amber-300">This recipe's conditions do not match the running server.</p>}
        {current?.enabled && !current.displays?.length && <p class="text-amber-300">This recipe provides no display. Its mod can register an editor recipe adapter. Edit it in JSON.</p>}
        {current?.displays?.map((display, index) => <div key={index} class="space-y-2 rounded border border-white/10 p-2">
            <p class="font-medium">{display.title}</p>
            <div class="grid grid-cols-3 gap-2">
                {display.slots.map((slot, at) => {
                    const stack = slot.items[frame % slot.items.length];
                    const icon = stack ? icons[JSON.stringify(stack)] : undefined;
                    return <div key={at} class="rounded bg-white/5 p-2" title={slot.error ?? (stack ? JSON.stringify(stack) : "Empty display")}>
                        <p class="mb-1 text-zinc-400">{slot.label}</p>
                        <div class="relative flex h-10 items-center justify-center">
                            {icon ? <img src={icon} alt={stack?.id} class="h-10 w-10 object-contain"/> : <span>{stack ? "?" : "—"}</span>}
                            {stack && stack.count > 1 && <span class="absolute bottom-0 right-0">{stack.count}</span>}
                        </div>
                        {stack && <p class="mt-1 truncate">{stack.id}</p>}
                        {slot.error && <p class="text-amber-300">{slot.error}</p>}
                    </div>;
                })}
            </div>
        </div>)}
        {current?.enabled && Boolean(current.displays?.length) && <p class="text-zinc-500">Display supplied by the running server. Edit this recipe in JSON.</p>}
    </div>;
}
