import {useEffect, useMemo, useRef, useState} from "preact/hooks";
import {renderItemIcons} from "./modelRenderer";
import {isRecipePath, newRecipe, parseRecipe, recipeType, recipeTypes, resultCount, setResultCount, setSlot,
    slotItem, type RecipeObject, type RecipeSlot, type RecipeType} from "./recipe";

interface RecipePreviewProps {
    path?: string;
    content?: string;
    token: string;
    readOnly: boolean;
    onContentChange: (content: string) => void;
}

interface PickerState {
    slot: RecipeSlot;
    x: number;
    y: number;
}

const menuPath = "assets/minecraft/textures/gui/container/";
const pageSize = 45;
const menuImages = new Map<string, Promise<string>>();

function menuImage(texture: string, token: string): Promise<string> {
    const key = `${token}:${texture}`;
    let pending = menuImages.get(key);
    if (!pending) {
        pending = fetch(`/api/asset-resolved?path=${encodeURIComponent(menuPath + texture)}`,
            {headers: {Authorization: `Bearer ${token}`}}).then(async response => {
            if (!response.ok) throw new Error(`Could not load ${texture} (${response.status}).`);
            return URL.createObjectURL(await response.blob());
        }).catch(reason => { menuImages.delete(key); throw reason; });
        menuImages.set(key, pending);
    }
    return pending;
}

function slotsFor(type: RecipeType): Array<{id: RecipeSlot; x: number; y: number; label: string}> {
    if (type === "minecraft:crafting_shaped" || type === "minecraft:crafting_shapeless") {
        return [
            ...Array.from({length: 9}, (_, index) => ({
                id: `grid${index}` as RecipeSlot, x: 30 + index % 3 * 18,
                y: 17 + Math.floor(index / 3) * 18, label: `Ingredient ${index + 1}`
            })),
            {id: "result", x: 124, y: 35, label: "Result"}
        ];
    }
    if (type === "minecraft:stonecutting") return [
        {id: "ingredient", x: 20, y: 33, label: "Ingredient"},
        {id: "result", x: 143, y: 33, label: "Result"}
    ];
    if (type === "minecraft:smithing_transform" || type === "minecraft:smithing_trim") return [
        {id: "template", x: 8, y: 48, label: "Template"},
        {id: "base", x: 26, y: 48, label: "Base"},
        {id: "addition", x: 44, y: 48, label: "Addition"},
        ...(type === "minecraft:smithing_transform" ? [{id: "result" as RecipeSlot, x: 129, y: 48, label: "Result"}] : [])
    ];
    return [
        {id: "ingredient", x: 56, y: 17, label: "Ingredient"},
        {id: "result", x: 116, y: 35, label: "Result"}
    ];
}

export function RecipePreview({path, content, token, readOnly, onContentChange}: RecipePreviewProps) {
    const recipe = useMemo(() => parseRecipe(content), [content]);
    const type = recipeType(recipe);
    const [textureUrl, setTextureUrl] = useState<string>();
    const [textureError, setTextureError] = useState<string>();
    const [items, setItems] = useState<string[]>([]);
    const [itemError, setItemError] = useState<string>();
    const [tags, setTags] = useState<Record<string, string[]>>();
    const [tagError, setTagError] = useState<string>();
    const [tagFrame, setTagFrame] = useState(0);
    const [picker, setPicker] = useState<PickerState>();
    const [pickerMode, setPickerMode] = useState<"items" | "tags">("items");
    const [query, setQuery] = useState("");
    const [hoveredItem, setHoveredItem] = useState<string>();
    const [limit, setLimit] = useState(pageSize);
    const [icons, setIcons] = useState<Record<string, string>>({});
    const [iconError, setIconError] = useState<string>();
    const [requestedIcons, setRequestedIcons] = useState<string[]>([]);
    const input = useRef<HTMLInputElement>(null);
    const itemList = useRef<HTMLDivElement>(null);
    const loadMore = useRef<HTMLDivElement>(null);
    const activeType = recipeTypes.find(candidate => candidate.id === type);
    const slots = type ? slotsFor(type) : [];
    const hasTagSlots = slots.some(slot => recipe && slotItem(recipe, slot.id)?.startsWith("#"));
    const needsTags = hasTagSlots || Boolean(picker && pickerMode === "tags");
    const displayedItem = (id: string | undefined, offset = 0) => {
        if (!id?.startsWith("#")) return id;
        const members = tags?.[id.slice(1)] ?? [];
        return members.length ? members[(tagFrame + offset) % members.length] : undefined;
    };

    useEffect(() => {
        if (!activeType) return;
        let cancelled = false;
        setTextureUrl(undefined);
        setTextureError(undefined);
        void menuImage(activeType.texture, token).then(value => {
            if (!cancelled) setTextureUrl(value);
        }).catch(reason => {
            if (!cancelled) setTextureError(reason instanceof Error ? reason.message : String(reason));
        });
        return () => { cancelled = true; };
    }, [activeType?.texture, token]);

    useEffect(() => {
        if (!picker || pickerMode !== "items" || items.length) return;
        let cancelled = false;
        void fetch("/api/recipe-items", {headers: {Authorization: `Bearer ${token}`}})
            .then(async response => {
                if (!response.ok) throw new Error(`Item list unavailable (${response.status})`);
                return response.json() as Promise<string[]>;
            }).then(result => { if (!cancelled) setItems(result); })
            .catch(reason => { if (!cancelled) setItemError(reason instanceof Error ? reason.message : String(reason)); });
        return () => { cancelled = true; };
    }, [picker, pickerMode, token, items.length]);

    useEffect(() => {
        if (!needsTags || tags) return;
        let cancelled = false;
        void fetch("/api/recipe-tags", {headers: {Authorization: `Bearer ${token}`}})
            .then(async response => {
                if (!response.ok) throw new Error(`Item tags unavailable (${response.status})`);
                return response.json() as Promise<Record<string, string[]>>;
            }).then(result => { if (!cancelled) setTags(result); })
            .catch(reason => { if (!cancelled) setTagError(reason instanceof Error ? reason.message : String(reason)); });
        return () => { cancelled = true; };
    }, [needsTags, tags, token]);

    useEffect(() => {
        if (!needsTags || !tags) return;
        const timer = window.setInterval(() => setTagFrame(frame => frame + 1), 1600);
        return () => window.clearInterval(timer);
    }, [needsTags, tags]);

    useEffect(() => { if (picker) input.current?.focus(); }, [picker]);
    useEffect(() => { setPicker(undefined); }, [path]);

    const filtered = useMemo(() => {
        const search = query.trim().toLowerCase().replaceAll(" ", "_");
        const options = pickerMode === "items" ? items : Object.keys(tags ?? {}).map(id => `#${id}`);
        return search ? options.filter(id => id.toLowerCase().includes(search)) : options;
    }, [items, tags, pickerMode, query]);
    const visible = filtered.slice(0, limit);
    useEffect(() => {
        if (!picker || visible.length >= filtered.length || !itemList.current || !loadMore.current) return;
        const observer = new IntersectionObserver(entries => {
            if (entries.some(entry => entry.isIntersecting)) setLimit(current => Math.min(current + pageSize, filtered.length));
        }, {root: itemList.current, rootMargin: "80px"});
        observer.observe(loadMore.current);
        return () => observer.disconnect();
    }, [picker, pickerMode, query, filtered.length, limit]);

    useEffect(() => {
        if (!picker || !itemList.current) return;
        const observer = new IntersectionObserver(entries => {
            const entered = entries.filter(entry => entry.isIntersecting)
                .map(entry => (entry.target as HTMLElement).dataset.iconId).filter((id): id is string => Boolean(id));
            if (entered.length) setRequestedIcons(current => {
                const next = [...new Set([...current, ...entered])];
                return next.length === current.length ? current : next;
            });
        }, {root: itemList.current, rootMargin: "80px"});
        itemList.current.querySelectorAll<HTMLElement>("[data-icon-id]").forEach(element => observer.observe(element));
        return () => observer.disconnect();
    }, [picker, pickerMode, query, limit, items, tags]);

    const iconIds = useMemo(() => [...new Set([
        ...slots.flatMap(slot => recipe ? [displayedItem(slotItem(recipe, slot.id)),
            displayedItem(slotItem(recipe, slot.id), 1)] : []),
        ...requestedIcons
    ].filter((id): id is string => Boolean(id && !id.startsWith("#"))))],
    [recipe, type, tags, requestedIcons, tagFrame]);

    useEffect(() => {
        const missing = iconIds.filter(id => !icons[id]);
        if (!missing.length) return;
        let cancelled = false;
        void renderItemIcons(missing.map(id => ({id, count: 1})), token, (index, icon) => {
            if (!cancelled) setIcons(current => ({...current, [missing[index]]: icon}));
        }).then(result => {
            if (cancelled) return;
            if (result.failed.length) setIconError(`${result.failed.length} item icons could not be rendered.`);
        }).catch(reason => { if (!cancelled) setIconError(reason instanceof Error ? reason.message : String(reason)); });
        return () => { cancelled = true; };
    }, [iconIds.join("|"), token]);

    const write = (next: RecipeObject) => onContentChange(`${JSON.stringify(next, null, 2)}\n`);
    const changeType = (selected: RecipeType) => {
        if (selected === type) return;
        if (content?.trim() && !window.confirm("Replace the current recipe with a new recipe of this type?")) return;
        write(newRecipe(selected));
        setPicker(undefined);
    };
    const put = (slot: RecipeSlot, id: string | undefined) => {
        if (!recipe || readOnly) return;
        write(setSlot(recipe, slot, id));
        setPicker(undefined);
    };
    const openPicker = (event: MouseEvent, slot: RecipeSlot) => {
        event.preventDefault();
        if (readOnly) return;
        setPickerMode(slotItem(recipe ?? {}, slot)?.startsWith("#") ? "tags" : "items");
        setQuery("");
        setLimit(pageSize);
        setRequestedIcons([]);
        setPicker({slot, x: Math.max(8, Math.min(event.clientX, window.innerWidth - 340)),
            y: Math.max(8, Math.min(event.clientY, window.innerHeight - 430))});
    };

    if (!isRecipePath(path)) return null;
    return <div class="space-y-3 text-xs text-zinc-300">
        <label class="block space-y-1">
            <span class="font-medium text-zinc-400">Recipe type</span>
            <select value={type ?? ""} disabled={readOnly} onChange={event => changeType(event.currentTarget.value as RecipeType)}
                    class="w-full rounded border border-white/15 bg-[#252526] px-2 py-1.5 text-zinc-100 disabled:opacity-50">
                <option value="" disabled>Select a recipe type…</option>
                {recipeTypes.map(candidate => <option key={candidate.id} value={candidate.id}>{candidate.label}</option>)}
            </select>
        </label>
        {!recipe && content?.trim() && <p class="text-amber-300">Fix the JSON or select a recipe type to start a new recipe.</p>}
        {recipe && !type && <p class="text-amber-300">This recipe type is not supported by the visualizer.</p>}
        {type && recipe && <>
            {type === "minecraft:crafting_shapeless" && <p class="text-zinc-500">Shapeless ingredients fill the grid in list order.</p>}
            {type === "minecraft:campfire_cooking" && <p class="text-zinc-500">Campfires have no menu; the furnace image shows the ingredient and result positions.</p>}
            <div class="rounded border border-white/10 bg-[#111] p-2">
                <div class="relative mx-auto aspect-[176/166] w-full max-w-[352px]"
                     style={{backgroundImage: textureUrl ? `url("${textureUrl}")` : undefined,
                         backgroundSize: "145.4545% 154.2169%", imageRendering: "pixelated",
                         backgroundColor: textureUrl ? undefined : "#c6c6c6"}}>
                    {slots.map(slot => {
                        const id = slotItem(recipe, slot.id);
                        const shown = displayedItem(id);
                        return <button key={slot.id} type="button" aria-label={`${slot.label}: ${id ?? "empty"}`}
                                       title={`${slot.label}: ${id ?? "empty"}${id?.startsWith("#") && shown ? ` (${shown})` : ""}`}
                                       class="absolute flex items-center justify-center outline-none hover:bg-white/20 focus-visible:ring-2 focus-visible:ring-violet-500"
                                       style={{left: `${(slot.x - 1) / 176 * 100}%`, top: `${(slot.y - 1) / 166 * 100}%`,
                                           width: `${18 / 176 * 100}%`, height: `${18 / 166 * 100}%`}}
                                       onContextMenu={event => openPicker(event, slot.id)}
                                       onClick={event => openPicker(event, slot.id)}>
                            {shown && icons[shown] ? <img src={icons[shown]} alt="" class="h-[90%] w-[90%] object-contain"/> :
                                id ? <span class="max-w-full truncate px-0.5 font-bold text-zinc-800">{id.startsWith("#") ? "#" : "?"}</span> : null}
                            {id?.startsWith("#") && <span class="absolute bottom-0 left-0 rounded-sm bg-violet-800 px-0.5 text-[8px] font-bold leading-none text-white">#</span>}
                            {slot.id === "result" && resultCount(recipe) > 1 &&
                                <span class="absolute bottom-0 right-0 font-bold text-white [text-shadow:1px_1px_#333]">{resultCount(recipe)}</span>}
                        </button>;
                    })}
                </div>
            </div>
            {textureError && <p class="text-amber-300">{textureError}</p>}
            {iconError && <p class="text-amber-300">{iconError}</p>}
            {"result" in recipe && type !== "minecraft:smithing_trim" && <label class="flex items-center gap-2">Result count
                <input type="number" min="1" max="99" value={resultCount(recipe)} disabled={readOnly}
                       onChange={event => {
                           const count = Number(event.currentTarget.value);
                           if (Number.isInteger(count) && count >= 1 && count <= 99) write(setResultCount(recipe, count));
                       }} class="w-16 rounded bg-[#252526] px-2 py-1"/>
            </label>}
            {["minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking"].includes(type) && <div class="flex flex-wrap gap-3">
                <label class="flex items-center gap-2">Cook ticks <input type="number" min="1" value={Number(recipe.cookingtime ?? 200)} disabled={readOnly}
                    onChange={event => { const value = Number(event.currentTarget.value); if (value > 0) write({...recipe, cookingtime: value}); }}
                    class="w-20 rounded bg-[#252526] px-2 py-1"/></label>
                <label class="flex items-center gap-2">XP <input type="number" min="0" step="0.1" value={Number(recipe.experience ?? 0)} disabled={readOnly}
                    onChange={event => { const value = Number(event.currentTarget.value); if (value >= 0) write({...recipe, experience: value}); }}
                    class="w-20 rounded bg-[#252526] px-2 py-1"/></label>
            </div>}
            {type === "minecraft:smithing_trim" && <label class="block space-y-1">Trim pattern
                <input type="text" value={String(recipe.pattern ?? "")} disabled={readOnly}
                       onChange={event => write({...recipe, pattern: event.currentTarget.value})}
                       class="w-full rounded bg-[#252526] px-2 py-1"/>
            </label>}
        </>}
        {picker && <div class="fixed inset-0 z-50" onClick={() => setPicker(undefined)}
                        onKeyDown={event => { if (event.key === "Escape") setPicker(undefined); }}>
            <div role="dialog" aria-label={`Choose ingredient for ${picker.slot}`} aria-modal="true"
                 class="fixed flex max-h-[420px] w-[328px] flex-col rounded border border-white/20 bg-[#252526] p-2 shadow-2xl shadow-black/70"
                 style={{left: `${picker.x}px`, top: `${picker.y}px`}} onClick={event => event.stopPropagation()}>
                <div role="tablist" aria-label="Ingredient kind" class="mb-2 flex rounded bg-[#181818] p-0.5">
                    <button type="button" role="tab" aria-selected={pickerMode === "items"}
                            onClick={() => { setPickerMode("items"); setQuery(""); setLimit(pageSize); setRequestedIcons([]); itemList.current?.scrollTo(0, 0); }}
                            class={`flex-1 rounded px-2 py-1.5 ${pickerMode === "items" ? "bg-violet-700 text-white" : "text-zinc-400 hover:text-white"}`}>
                        Items
                    </button>
                    {picker.slot !== "result" && <button type="button" role="tab" aria-selected={pickerMode === "tags"}
                            onClick={() => { setPickerMode("tags"); setQuery(""); setLimit(pageSize); setRequestedIcons([]); itemList.current?.scrollTo(0, 0); }}
                            class={`flex-1 rounded px-2 py-1.5 ${pickerMode === "tags" ? "bg-violet-700 text-white" : "text-zinc-400 hover:text-white"}`}>
                        Tags
                    </button>}
                </div>
                <input ref={input} type="search" value={query} placeholder={`Search loaded ${pickerMode}…`}
                       onInput={event => { setQuery(event.currentTarget.value); setLimit(pageSize); setRequestedIcons([]); setHoveredItem(undefined); itemList.current?.scrollTo(0, 0); }}
                       class="mb-2 w-full rounded border border-white/20 bg-[#181818] px-2 py-1.5 text-white outline-none focus:border-violet-400"/>
                <div ref={itemList} class={`${pickerMode === "items" ? "grid grid-cols-9 content-start gap-1" : "flex flex-col gap-1"} h-64 min-h-0 shrink-0 overflow-y-auto`}>
                    {visible.map(id => {
                        const shown = id.startsWith("#") ? tags?.[id.slice(1)]?.[0] : id;
                        const currentMember = displayedItem(id);
                        const members = id.startsWith("#") ? tags?.[id.slice(1)] ?? [] : [];
                        return <button key={id} type="button" title={id} aria-label={id} data-icon-id={shown}
                            onClick={() => put(picker.slot, id)}
                            onMouseEnter={() => setHoveredItem(id)} onFocus={() => setHoveredItem(id)}
                            class={pickerMode === "items"
                                ? "flex aspect-square items-center justify-center rounded border border-white/10 bg-[#8b8b8b] hover:border-violet-400 hover:bg-[#aaa]"
                                : "flex min-h-12 items-center gap-2 rounded border border-white/10 bg-[#333] px-2 text-left hover:border-violet-400 hover:bg-[#444]"}>
                            {shown && icons[shown] ? <img src={icons[shown]} alt="" class={pickerMode === "items" ? "h-[90%] w-[90%] object-contain" : "h-10 w-10 shrink-0 object-contain"}/> :
                                <span class={pickerMode === "items" ? "flex h-[90%] w-[90%] items-center justify-center font-bold" : "flex h-10 w-10 shrink-0 items-center justify-center font-bold"}>{pickerMode === "tags" ? "#" : "?"}</span>}
                            {pickerMode === "tags" && <span class="min-w-0 flex-1">
                                <span class="block truncate text-zinc-100">{id}</span>
                                <span class="block truncate text-[10px] text-zinc-400">{members.length} items{currentMember ? ` · ${currentMember}` : ""}</span>
                            </span>}
                        </button>;
                    })}
                    {visible.length < filtered.length && <div ref={loadMore} class={pickerMode === "items" ? "col-span-9 h-1" : "h-1"}/>}
                </div>
                <p class="mt-1 h-4 truncate text-zinc-400" aria-live="polite">{hoveredItem ?? `${filtered.length} matching ${pickerMode}`}</p>
                {pickerMode === "items" && itemError && <p class="mt-2 text-amber-300">{itemError}</p>}
                {pickerMode === "tags" && tagError && <p class="mt-2 text-amber-300">{tagError}</p>}
                {pickerMode === "items" && !itemError && !items.length && <p class="mt-2 text-zinc-400">Loading items…</p>}
                {pickerMode === "tags" && !tagError && !tags && <p class="mt-2 text-zinc-400">Loading tags…</p>}
                {(pickerMode === "items" ? items.length > 0 : Boolean(tags)) && !filtered.length &&
                    <p class="mt-2 text-zinc-400">No matching {pickerMode}.</p>}
                <div class="mt-2 flex justify-end border-t border-white/10 pt-2">
                    {recipe && slotItem(recipe, picker.slot) && <button type="button" onClick={() => put(picker.slot, undefined)} class="mr-auto text-zinc-300 hover:text-white">Clear slot</button>}
                    <button type="button" onClick={() => setPicker(undefined)} class="text-zinc-300 hover:text-white">Close</button>
                </div>
            </div>
        </div>}
    </div>;
}
