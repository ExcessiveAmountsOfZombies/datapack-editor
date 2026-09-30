import {useEffect, useMemo, useRef, useState} from "preact/hooks";
import {AddChildIcon} from "../Codicon";
import {collectJsonFiles, type WorkspaceTree} from "../model";
import {AdvancementDetails, NewAdvancementForm} from "./AdvancementEditor";
import {advancementCompletions} from "./advancementSuggestions";
import {renderItemIcons} from "./modelRenderer";
import {
    advancementId, advancementText, buildAdvancementTrees, isAdvancementPath,
    layoutAdvancementTree, type AdvancementFile, type PositionedAdvancement
} from "./advancement";

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

const nodeStepX = 56;
const nodeStepY = 54;
const margin = 32;

function assetPath(id: string): string {
    const [namespace, path] = id.includes(":") ? id.split(":", 2) : ["minecraft", id];
    return `assets/${namespace}/textures/${path}.png`;
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
    const [workspaceFiles, setWorkspaceFiles] = useState<AdvancementFile[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>();
    const [selectedRoot, setSelectedRoot] = useState<string>();
    const [selectedNode, setSelectedNode] = useState<string>();
    const [creation, setCreation] = useState<{kind: "root"} | {kind: "child"; parentId: string}>();
    const [itemIds, setItemIds] = useState<string[]>([]);
    const [language, setLanguage] = useState<Record<string, string>>({});
    const [icons, setIcons] = useState<Record<string, string>>({});
    const [background, setBackground] = useState<string>();
    const viewport = useRef<HTMLDivElement>(null);
    const drag = useRef<{ x: number; y: number; left: number; top: number; id: number }>();
    const workspacePaths = useMemo(() => collectJsonFiles(workspace?.entries ?? [])
        .map(entry => entry.path).filter(isAdvancementPath), [workspace]);

    useEffect(() => {
        let cancelled = false;
        void fetch("/api/recipe-items")
            .then(async response => response.ok ? await response.json() as string[] : [])
            .then(items => { if (!cancelled) setItemIds(items); })
            .catch(() => undefined);
        return () => { cancelled = true; };
    }, []);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        void fetch("/api/advancements")
            .then(async response => {
                if (!response.ok) throw new Error(`Advancements unavailable (${response.status})`);
                return response.json() as Promise<AdvancementFile[]>;
            }).then(files => {
                if (!cancelled) {
                    setLoaded(files);
                    setError(undefined);
                    setLoading(false);
                }
            })
            .catch(reason => {
                if (!cancelled) {
                    setError(String(reason));
                    setLoading(false);
                }
            });
        return () => {
            cancelled = true;
        };
    }, []);

    useEffect(() => {
        let cancelled = false;
        if (!workspacePaths.length) {
            setWorkspaceFiles([]);
            return;
        }
        // Workspace advancements are usually few; bound requests for large workspaces.
        const result: AdvancementFile[] = [];
        let next = 0;
        const worker = async () => {
            while (next < workspacePaths.length) {
                const filePath = workspacePaths[next++];
                try {
                    const response = await fetch(`/api/file?path=${encodeURIComponent(filePath)}`);
                    if (response.ok) result.push({path: filePath, content: await response.text()});
                } catch { /* Keep other advancements available if one read fails. */
                }
            }
        };
        void Promise.all(Array.from({length: Math.min(4, workspacePaths.length)}, worker))
            .then(() => {
                if (!cancelled) setWorkspaceFiles(result);
            });
        return () => {
            cancelled = true;
        };
    }, [workspacePaths.join("|"), savedRevision]);

    const files = useMemo(() => {
        const merged = new Map([...loaded, ...workspaceFiles].map(file => [file.path, file]));
        for (const file of openContents) merged.set(file.path, file);
        return [...merged.values()];
    }, [loaded, workspaceFiles, openContents]);
    const completions = useMemo(() => advancementCompletions(files, itemIds, workspace, datapacks),
        [files, itemIds, workspace, datapacks]);
    const trees = useMemo(() => buildAdvancementTrees(files, path), [files, path]);
    const activeId = path && advancementId(path);
    const activeTree = trees.find(tree => tree.nodes.some(node => node.id === activeId));
    const tree = trees.find(candidate => candidate.root.id === selectedRoot)
        ?? activeTree ?? trees[0];
    const nodes = useMemo(() => tree ? layoutAdvancementTree(tree) : [], [tree]);
    const nodeMap = useMemo(() => new Map(nodes.map(node => [node.id, node])), [nodes]);
    const selected = nodeMap.get(selectedNode ?? "") ?? nodeMap.get(activeId ?? "") ?? nodeMap.get(tree?.root.id ?? "");
    const width = Math.max(300, ...nodes.map(node => margin * 2 + node.x * nodeStepX + 32));
    const height = Math.max(260, ...nodes.map(node => margin * 2 + node.y * nodeStepY + 32));
    const formParent = creation?.kind === "child" ? creation.parentId : undefined;
    const formNamespace = (formParent ?? selected?.id ?? activeId ?? tree?.root.id ?? "minecraft:root").split(":")[0];

    useEffect(() => {
        if (activeTree) {
            setSelectedRoot(activeTree.root.id);
            setSelectedNode(activeId);
            setCreation(undefined);
        }
    }, [path, activeTree?.root.id]);

    useEffect(() => {
        const namespaces = [...new Set(["minecraft", ...nodes.map(node => node.id.split(":")[0])])];
        let cancelled = false;
        void Promise.all(namespaces.map(async namespace => {
            const response = await fetch(`/api/asset-resolved?path=${encodeURIComponent(`assets/${namespace}/lang/en_us.json`)}`);
            return response.ok ? await response.json() as Record<string, string> : {};
        })).then(values => {
            if (!cancelled) setLanguage(Object.assign({}, ...values));
        })
            .catch(() => {
                if (!cancelled) setLanguage({});
            });
        return () => {
            cancelled = true;
        };
    }, [tree?.root.id]);

    const iconKey = nodes.map(node => `${node.id}:${node.display?.icon?.id ?? node.display?.icon?.item ?? ""}`).join("|");
    useEffect(() => {
        let cancelled = false;
        setIcons({});
        const items = nodes.map(node => {
            const icon = node.display?.icon;
            const id = icon?.id ?? icon?.item;
            return id ? {id, count: 1, components: icon?.components} : null;
        });
        void renderItemIcons(items, (index, image) => {
            if (!cancelled) setIcons(current => ({...current, [nodes[index].id]: image}));
        });
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
        let cancelled = false;
        let url: string | undefined;
        setBackground(undefined);
        void fetch(`/api/asset-resolved?path=${encodeURIComponent(assetPath(source))}`).then(async response => {
            if (!response.ok) return;
            url = URL.createObjectURL(await response.blob());
            if (cancelled) URL.revokeObjectURL(url);
            else setBackground(url);
        }).catch(() => undefined);
        return () => {
            cancelled = true;
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
                {trees.map(candidate => <option key={candidate.root.id} value={candidate.root.id}>
                    {advancementText(candidate.root.display?.title, language) || candidate.root.id}
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
                <div class="relative" style={{
                    width: `${width}px`, height: `${height}px`,
                    backgroundImage: background ? `url("${background}")` : undefined,
                    backgroundRepeat: "repeat", imageRendering: "pixelated"
                }}>
                    <svg class="pointer-events-none absolute inset-0" width={width} height={height} aria-hidden="true">
                        {nodes.map(node => {
                            const parent = node.visibleParent && nodeMap.get(node.visibleParent);
                            if (!parent) return null;
                            const x1 = margin + parent.x * nodeStepX + 16;
                            const y1 = margin + parent.y * nodeStepY + 16;
                            const x2 = margin + node.x * nodeStepX + 16;
                            const y2 = margin + node.y * nodeStepY + 16;
                            const points = `${x1},${y1} ${(x1 + x2) / 2},${y1} ${(x1 + x2) / 2},${y2} ${x2},${y2}`;
                            return <g key={node.id}>
                                <polyline points={points} fill="none" stroke="#17120d" stroke-width="5"/>
                                <polyline points={points} fill="none" stroke="#a99b83" stroke-width="2"/>
                            </g>;
                        })}
                    </svg>
                    {nodes.map(node => <button key={node.id} type="button" title={`${label(node)}\n${node.id}`}
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
                    {nodes.map(node => <button key={`${node.id}:add`} type="button"
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
