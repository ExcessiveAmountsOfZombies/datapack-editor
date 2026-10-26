import {useEffect, useMemo, useRef, useState} from "preact/hooks";
import {AddChildIcon} from "../../Codicon.tsx";
import {collectJsonFiles, type WorkspaceTree} from "../../model.ts";
import {AdvancementDetails, NewAdvancementForm} from "./AdvancementEditor.tsx";
import {advancementCompletions} from "./advancementSuggestions.ts";
import {advancementStructure, AdvancementTreeError, loadAdvancementTree} from "./advancementLoading.ts";
import {boundedBytes, boundedJson, mib} from "../memoryBudget.ts";
import {
    advancementStepX as nodeStepX, advancementStepY as nodeStepY, advancementMargin as margin,
    visibleAdvancements, advancementConnections, visibleConnectionPath, type AdvancementViewport
} from "./advancementViewport.ts";
import {renderItemIcons} from "../modelRenderer.ts";
import {
    advancementId, advancementText, buildAdvancementTrees, isAdvancementPath,
    layoutAdvancementTree, type AdvancementFile, type PositionedAdvancement
} from "./advancement.ts";

interface AdvancementPreviewProps {
    path?: string;
    workspace?: WorkspaceTree;
    datapacks?: WorkspaceTree;
    openContents: AdvancementFile[];
    savedRevision: number;
    readOnly: boolean;
    content?: string;
    onContentChange: (content: string) => void;
    onCreate: (path: string, content: string) => Promise<void>;
    onOpen: (path: string, source: "workspace" | "datapack") => void;
}

function assetPath(id: string): string {
    const [namespace, path] = id.includes(":") ? id.split(":", 2) : ["minecraft", id];
    const texture = path.replace(/^textures\//, "").replace(/\.png$/, "");
    return `assets/${namespace}/textures/${texture}.png`;
}

function frameColor(frame?: string): string {
    if (frame === "challenge") return "#9d4dce";
    if (frame === "goal") return "#d7a329";
    return "#b2a9a0";
}

function fallbackTitle(node: PositionedAdvancement): string {
    return node.id.split(/[/:]/).at(-1)?.replaceAll("_", " ") ?? node.id;
}

export function AdvancementPreview({
                                       path,
                                       workspace,
                                       datapacks,
                                       openContents,
                                       savedRevision,
                                       readOnly,
                                       content,
                                       onContentChange,
                                       onCreate,
                                       onOpen
                                   }: AdvancementPreviewProps) {
    const [loaded, setLoaded] = useState<AdvancementFile[]>([]);
    const [rootOptions, setRootOptions] = useState<Array<{id: string; path: string}>>([]);
    const [workspaceFiles, setWorkspaceFiles] = useState<AdvancementFile[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>();
    const [workspaceError, setWorkspaceError] = useState<string>();
    const [selectedRoot, setSelectedRoot] = useState<string>();
    const [selectedNode, setSelectedNode] = useState<string>();
    const [creation, setCreation] = useState<{kind: "root"} | {kind: "child"; parentId: string}>();
    const [itemIds, setItemIds] = useState<string[]>([]);
    const [language, setLanguage] = useState<Record<string, string>>({});
    const [icons, setIcons] = useState<Record<string, string>>({});
    const [background, setBackground] = useState<string>();
    const viewport = useRef<HTMLDivElement>(null);
    const [viewBounds, setViewBounds] = useState<AdvancementViewport>({left: 0, top: 0, width: 900, height: 600});
    const drag = useRef<{ x: number; y: number; left: number; top: number; id: number }>();
    const workspacePaths = useMemo(() => collectJsonFiles(workspace?.entries ?? [])
        .map(entry => entry.path).filter(isAdvancementPath), [workspace]);

    useEffect(() => {
        let cancelled = false;
        void fetch("/api/recipe-items")
            .then(async response => response.ok ? await boundedJson<string[]>(response, 2 * mib) : [])
            .then(items => { if (!cancelled) setItemIds(items); })
            .catch(() => undefined);
        return () => { cancelled = true; };
    }, []);

    useEffect(() => {
        const controller = new AbortController();
        if (!workspacePaths.length) {
            setWorkspaceFiles([]);
            setWorkspaceError(undefined);
            return;
        }
        // Workspace advancements are usually few; bound requests for large workspaces.
        const result: AdvancementFile[] = [];
        let next = 0;
        let characters = 0;
        const worker = async () => {
            if (workspacePaths.length > 256) throw new Error(`Workspace advancement preview exceeds its 256-file limit`);
            while (!controller.signal.aborted && next < workspacePaths.length) {
                const filePath = workspacePaths[next++];
                const response = await fetch(`/api/file?path=${encodeURIComponent(filePath)}`, {signal: controller.signal});
                if (!response.ok) throw new Error(`Workspace advancement read failed (${response.status})`);
                const content = new TextDecoder().decode(await boundedBytes(response, 2 * mib));
                characters += content.length;
                if (characters > 2 * mib) throw new Error("Workspace advancements exceed the preview memory budget");
                result.push({path: filePath, content});
            }
        };
        void Promise.all(Array.from({length: Math.min(2, workspacePaths.length)}, worker))
            .then(() => {
                if (!controller.signal.aborted) {setWorkspaceFiles(result); setWorkspaceError(undefined);}
            }).catch(reason => {
                if (!controller.signal.aborted) {setWorkspaceFiles([]); setWorkspaceError(String(reason));}
                controller.abort();
            });
        return () => {
            controller.abort();
        };
    }, [workspacePaths.join("|"), savedRevision]);

    const overrides = useMemo(() => {
        const merged = new Map(workspaceFiles.map(file => [file.path, file]));
        for (const file of openContents) merged.set(file.path, file);
        try {return {structure: advancementStructure([...merged.values()]), error: undefined};}
        catch (reason) {return {structure: [], error: String(reason)};}
    }, [workspaceFiles, openContents]);
    const structureKey = JSON.stringify(overrides.structure);
    useEffect(() => {setSelectedRoot(undefined);}, [path]);
    useEffect(() => {
        const controller = new AbortController();
        setLoading(true);
        setLoaded([]);
        const timer = setTimeout(() => {
            if (overrides.error) {setError(overrides.error); setLoading(false); return;}
            void loadAdvancementTree(controller.signal, path, selectedRoot, overrides.structure).then(result => {
                if (!controller.signal.aborted) {
                    setLoaded(result.files);
                    setRootOptions(result.roots);
                    setError(result.unavailable ? `${result.unavailable} invalid or oversized advancements were excluded from the lookup.` : undefined);
                    setLoading(false);
                }
            }).catch(reason => {
                if (!controller.signal.aborted) {
                    if (reason instanceof AdvancementTreeError && reason.roots.length) setRootOptions(reason.roots);
                    setError(String(reason)); setLoading(false);
                }
            });
        }, 200);
        return () => {clearTimeout(timer); controller.abort();};
    }, [path, selectedRoot, structureKey, overrides.error]);

    const files = useMemo(() => {
        const merged = new Map([...loaded, ...workspaceFiles].map(file => [file.path, file]));
        for (const file of openContents) merged.set(file.path, file);
        return [...merged.values()];
    }, [loaded, workspaceFiles, openContents]);
    const completions = useMemo(() => advancementCompletions(files, itemIds, workspace, datapacks),
        [files, itemIds, workspace, datapacks]);
    const trees = useMemo(() => files.length <= 4352 ? buildAdvancementTrees(files, path) : [], [files, path]);
    const activeId = path && advancementId(path);
    const activeTree = trees.find(tree => tree.nodes.some(node => node.id === activeId));
    const tree = trees.find(candidate => candidate.root.id === selectedRoot)
        ?? activeTree ?? trees[0];
    const nodes = useMemo(() => tree ? layoutAdvancementTree(tree) : [], [tree]);
    const nodeMap = useMemo(() => new Map(nodes.map(node => [node.id, node])), [nodes]);
    const visibleNodes = useMemo(() => visibleAdvancements(nodes, viewBounds), [nodes, viewBounds]);
    const connections = useMemo(() => advancementConnections(nodes, nodeMap), [nodes, nodeMap]);
    const connectionPath = useMemo(() => visibleConnectionPath(connections, viewBounds), [connections, viewBounds]);
    const selected = nodeMap.get(selectedNode ?? "") ?? nodeMap.get(activeId ?? "") ?? nodeMap.get(tree?.root.id ?? "");
    const width = nodes.reduce((value, node) => Math.max(value, margin * 2 + node.x * nodeStepX + 32), 300);
    const height = nodes.reduce((value, node) => Math.max(value, margin * 2 + node.y * nodeStepY + 32), 260);
    const formParent = creation?.kind === "child" ? creation.parentId : undefined;
    const formNamespace = (formParent ?? selected?.id ?? activeId ?? tree?.root.id ?? "minecraft:root").split(":")[0];

    useEffect(() => {
        const panel = viewport.current;
        if (!panel) return;
        const update = () => setViewBounds({left: panel.scrollLeft, top: panel.scrollTop,
            width: panel.clientWidth, height: panel.clientHeight});
        const observer = new ResizeObserver(update);
        observer.observe(panel);
        update();
        return () => observer.disconnect();
    }, [tree?.root.id]);

    useEffect(() => {
        if (activeTree) {
            setSelectedNode(activeId);
            setCreation(undefined);
        }
    }, [path, activeTree?.root.id]);

    useEffect(() => {
        const namespaces = [...new Set(["minecraft", ...nodes.map(node => node.id.split(":")[0])])];
        const controller = new AbortController();
        void (async () => {
            const language: Record<string, string> = {};
            let characters = 0;
            if (namespaces.length > 64) throw new Error("Too many translation namespaces");
            for (const namespace of namespaces) {
                const response = await fetch(`/api/asset-resolved?path=${encodeURIComponent(`assets/${namespace}/lang/en_us.json`)}`, {signal: controller.signal});
                if (!response.ok) continue;
                const bytes = await boundedBytes(response);
                characters += bytes.length;
                if (characters > 2 * mib) throw new Error("Translation budget exceeded");
                Object.assign(language, JSON.parse(new TextDecoder().decode(bytes)));
            }
            if (!controller.signal.aborted) setLanguage(language);
        })().catch(() => {if (!controller.signal.aborted) setLanguage({});});
        return () => controller.abort();
    }, [tree?.root.id]);

    const iconNodes = selected && !visibleNodes.some(node => node.id === selected.id) ? [...visibleNodes, selected] : visibleNodes;
    const iconKey = iconNodes.map(node => JSON.stringify([node.id, node.display?.icon])).join("|");
    useEffect(() => {
        let cancelled = false;
        setIcons({});
        const items = iconNodes.map(node => {
            const icon = node.display?.icon;
            const id = icon?.id ?? icon?.item;
            return id ? {id, count: 1, components: icon?.components} : null;
        });
        void renderItemIcons(items, (index, image) => {
            if (!cancelled) setIcons(current => ({...current, [iconNodes[index].id]: image}));
        }, () => cancelled);
        return () => {
            cancelled = true;
        };
    }, [iconKey]);

    useEffect(() => {
        const source = tree?.root.display?.background;
        if (!source) {
            setBackground(undefined);
            return;
        }
        const controller = new AbortController();
        let url: string | undefined;
        setBackground(undefined);
        void fetch(`/api/asset-resolved?path=${encodeURIComponent(assetPath(source))}`, {signal: controller.signal}).then(async response => {
            if (!response.ok) return;
            url = URL.createObjectURL(new Blob([await boundedBytes(response, 8 * mib) as Uint8Array<ArrayBuffer>], {type: "image/png"}));
            if (controller.signal.aborted) URL.revokeObjectURL(url);
            else setBackground(url);
        }).catch(() => undefined);
        return () => {
            controller.abort();
            if (url) URL.revokeObjectURL(url);
        };
    }, [tree?.root.display?.background]);

    useEffect(() => {
        const panel = viewport.current;
        if (!panel) return;
        const node = nodeMap.get(activeId ?? "") ?? nodeMap.get(tree?.root.id ?? "");
        if (node) panel.scrollTo({
            left: Math.max(0, margin + node.x * nodeStepX - panel.clientWidth / 2),
            top: Math.max(0, margin + node.y * nodeStepY - panel.clientHeight / 2)
        });
    }, [tree?.root.id, activeId, nodes]);

    const label = (node: PositionedAdvancement) => advancementText(node.display?.title, language) || fallbackTitle(node);
    return <div class="flex h-full min-h-[440px] flex-col gap-3 text-xs text-zinc-300">
        <label class="block space-y-1">
            <span class="font-medium text-zinc-400">Advancement tree</span>
            <select class="w-full rounded border border-white/15 bg-[#252526] px-2 py-1.5 text-zinc-100"
                    value={tree?.root.id ?? ""} onChange={event => {
                setSelectedRoot(event.currentTarget.value);
                setSelectedNode(event.currentTarget.value);
            }}>
                {[...new Map([...rootOptions, ...trees.map(candidate => ({id: candidate.root.id, path: candidate.root.path}))].map(root => [root.id, root])).values()].map(candidate => <option key={candidate.id} value={candidate.id}>
                    {advancementText(trees.find(tree => tree.root.id === candidate.id)?.root.display?.title, language) || candidate.id}
                </option>)}
            </select>
        </label>
        <div class="flex shrink-0 gap-2">
            <button type="button" class="rounded bg-violet-700 px-2 py-1.5 font-medium text-white hover:bg-violet-600"
                    onClick={() => setCreation({kind: "root"})}>New root</button>
            <button type="button" disabled={!tree}
                    class="rounded bg-white/10 px-2 py-1.5 text-zinc-200 hover:bg-white/20 disabled:opacity-40"
                    onClick={() => {
                        const parentId = selected?.id ?? tree?.root.id;
                        if (parentId) setCreation({kind: "child", parentId});
                    }}>Add child</button>
        </div>
        {loading && <p role="status">Loading advancements…</p>}
        {error && <p class="text-amber-300">{error}</p>}
        {workspaceError && <p class="text-amber-300">{workspaceError}</p>}
        {!loading && !trees.length &&
            <p>No advancement trees found. A root needs a display and no parent; children link to it through parent
                IDs.</p>}
        {tree && <>
            <div class="flex items-center justify-between text-[11px] text-zinc-500">
                <span>{nodes.length} advancements</span><span>Drag to pan · click to open · green + to add child</span>
            </div>
            <div ref={viewport}
                 class="min-h-45 w-full flex-1 overflow-auto rounded border-2 border-[#4c4033] bg-[#242424] cursor-grab active:cursor-grabbing"
                 aria-label={`Advancement tree ${tree.root.id}`}
                 style={{
                     backgroundImage: background ? `url("${background}")` : undefined,
                     backgroundRepeat: "repeat", backgroundAttachment: "local", imageRendering: "pixelated"
                 }}
                 onScroll={event => {
                     const panel = event.currentTarget;
                     setViewBounds({left: panel.scrollLeft, top: panel.scrollTop,
                         width: panel.clientWidth, height: panel.clientHeight});
                 }}
                 onPointerDown={event => {
                     if (event.button !== 0 || !viewport.current ||
                         (event.target instanceof Element && event.target.closest("button"))) return;
                     drag.current = {
                         x: event.clientX, y: event.clientY,
                         left: viewport.current.scrollLeft, top: viewport.current.scrollTop, id: event.pointerId
                     };
                     viewport.current.setPointerCapture(event.pointerId);
                 }}
                 onPointerMove={event => {
                     const value = drag.current;
                     if (value?.id === event.pointerId && viewport.current) {
                         viewport.current.scrollLeft = value.left + value.x - event.clientX;
                         viewport.current.scrollTop = value.top + value.y - event.clientY;
                     }
                 }}
                 onPointerUp={() => {
                     drag.current = undefined;
                 }} onPointerCancel={() => {
                drag.current = undefined;
            }}>
                <div class="relative" style={{width: `${width}px`, height: `${height}px`}}>
                    <svg class="pointer-events-none absolute inset-0" width={width} height={height} aria-hidden="true">
                        <path d={connectionPath} fill="none" stroke="#17120d" stroke-width="5"/>
                        <path d={connectionPath} fill="none" stroke="#a99b83" stroke-width="2"/>
                    </svg>
                    {visibleNodes.map(node => <button key={node.id} type="button" title={`${label(node)}\n${node.id}`}
                                               aria-label={`${label(node)}${node.display?.hidden ? " (hidden)" : ""}`}
                                               class={`absolute flex h-8 w-8 items-center justify-center border-2 bg-[#34302b] shadow-[inset_2px_2px_#81786a,inset_-2px_-2px_#171512,2px_2px_#171512] hover:brightness-125 focus-visible:outline-2 focus-visible:outline-white ${
                                                   node.id === selected?.id ? "ring-2 ring-white" : ""}`}
                                               style={{
                                                   left: `${margin + node.x * nodeStepX}px`,
                                                   top: `${margin + node.y * nodeStepY}px`,
                                                   borderColor: frameColor(node.display?.frame),
                                                   borderRadius: node.display?.frame === "challenge" ? "50%" : "2px",
                                                   opacity: node.display?.hidden ? 0.55 : 1
                                               }}
                                               onClick={() => {
                                                   setSelectedNode(node.id);
                                                   onOpen(node.path, node.path.startsWith("data/") ? "workspace" : "datapack");
                                               }}>
                        {icons[node.id] ? <img src={icons[node.id]} alt="" class="h-6 w-6 object-contain translate-x-px translate-y-px"/> :
                            <span class="text-base text-white/70">?</span>}
                    </button>)}
                    {visibleNodes.map(node => <button key={`${node.id}:add`} type="button"
                        title={`Add a child advancement to ${label(node)}`}
                        aria-label={`Add child advancement to ${node.id}`}
                        class="group absolute z-10 flex h-4 w-4 items-center justify-center rounded-sm border border-[#14532d] bg-[#143d28] text-[13px] font-bold leading-none text-[#6ee7a0] shadow-[1px_1px_#171512] hover:bg-[#166534] hover:text-white focus-visible:outline-2 focus-visible:outline-white"
                        style={{left: `${margin + node.x * nodeStepX + 24}px`,
                            top: `${margin + node.y * nodeStepY - 10}px`}}
                        onClick={event => {
                            event.stopPropagation();
                            setSelectedNode(node.id);
                            setCreation({kind: "child", parentId: node.id});
                        }}><AddChildIcon/>
                        <span class="pointer-events-none absolute bottom-full left-1/2 mb-1 hidden w-max max-w-48 -translate-x-1/2 rounded bg-[#171717] px-2 py-1 text-[10px] font-normal text-white shadow-lg group-hover:block group-focus-visible:block">
                            Add child advancement to {label(node)}
                        </span>
                    </button>)}
                </div>
            </div>
            {selected && <div class="max-h-28 shrink-0 overflow-y-auto rounded border border-white/10 bg-[#252526] p-3">
                <div class="flex items-center gap-2">
                    {icons[selected.id] && <img src={icons[selected.id]} alt="" class="h-7 w-7 object-contain"/>}
                    <div class="min-w-0">
                        <div class="font-semibold text-white">{label(selected)}</div>
                        <div class="truncate text-[10px] text-zinc-500" title={selected.id}>{selected.id}</div>
                    </div>
                </div>
                <p class="mt-2 whitespace-pre-wrap text-zinc-300">{advancementText(selected.display?.description, language) || "No description"}</p>
                {selected.display?.hidden && <p class="mt-2 text-amber-300">Hidden advancement</p>}
            </div>}
        </>}
        {!readOnly && <section aria-label="Advancement editor"
                               class="flex h-[42%] min-h-50 max-h-85 shrink-0 flex-col overflow-hidden rounded border border-white/10 bg-[#252526]">
            <div class="flex shrink-0 items-center justify-between border-b border-white/10 px-3 py-2">
                <h2 class="font-semibold text-zinc-200">Advancement details</h2>
            </div>
            <div class="min-h-0 flex-1 overflow-y-auto p-3">
                {isAdvancementPath(path) && content !== undefined
                        ? <AdvancementDetails content={content} onContentChange={onContentChange}
                                              completions={completions}/> :
                        <p class="text-zinc-400">Open a workspace advancement to edit its details, or create a root
                            or child above.</p>}
            </div>
        </section>}
        {creation && <div role="dialog" aria-modal="true"
                          aria-label={creation.kind === "root" ? "New root advancement" : "New child advancement"}
                          class="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4"
                          onClick={() => setCreation(undefined)}>
            <section class="max-h-[min(90vh,700px)] w-full max-w-lg overflow-y-auto rounded border border-white/20 bg-[#252526] p-4 shadow-2xl"
                     onClick={event => event.stopPropagation()}>
                <h2 class="mb-3 text-sm font-semibold text-white">{creation.kind === "root" ? "New root advancement" : "New child advancement"}</h2>
                <NewAdvancementForm key={`${creation.kind}:${formParent ?? ""}`} parent={formParent}
                                    namespace={formNamespace} completions={completions}
                                    onCreate={async (newPath, newContent) => {
                                        await onCreate(newPath, newContent);
                                        setCreation(undefined);
                                    }} onCancel={() => setCreation(undefined)}/>
            </section>
        </div>}
    </div>;
}
